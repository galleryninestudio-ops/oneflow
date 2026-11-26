package ai.oneflow.execution.flowcode

import ai.oneflow.core.contracts.ArtifactId
import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.FlowError
import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.ScriptEngine
import ai.oneflow.core.contracts.ScriptRequest
import ai.oneflow.core.contracts.ScriptResult
import ai.oneflow.core.contracts.SecretRedactor
import ai.oneflow.core.contracts.ToolBroker
import ai.oneflow.core.contracts.ToolRegistry
import ai.oneflow.core.contracts.ToolRequest
import ai.oneflow.core.contracts.ToolRequestId
import ai.oneflow.core.contracts.ToolResultStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Isolated QuickJS-NG process-based ScriptEngine (§6.2).
 * Spawns `qjs_worker` with cleared environment, enforces wall-clock watchdog (SIGKILL),
 * dispatches `__hostCall` invocations through `ToolBroker`, serializes exclusive writes,
 * caps inline output size, and redacts secrets.
 */
class QuickJsProcessScriptEngine(
    private val workerBinary: Path,
    private val toolRegistry: ToolRegistry,
    private val toolBroker: ToolBroker,
    private val activeToolIds: () -> Collection<String>,
    private val missionGrantsProvider: (MissionId) -> Set<String>,
    private val sessionApprovalsProvider: (MissionId) -> Set<String> = { emptySet() },
    private val largeOutputSink: ((content: String, mimeType: String) -> ArtifactId)? = null,
    private val maxInlineOutputBytes: Int = 16 * 1024,
    private val maxToolCallsPerScript: Int = 50,
    private val knownSecrets: () -> Collection<String> = { emptyList() },
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ScriptEngine {

    private val writeMutex = Mutex()

    override suspend fun execute(request: ScriptRequest): ScriptResult = withContext(Dispatchers.IO) {
        if (!Files.isExecutable(workerBinary)) {
            return@withContext ScriptResult(
                ok = false,
                error = FlowError(ErrorCode.UNAVAILABLE, "QuickJS worker binary unavailable at $workerBinary")
            )
        }

        val startMs = System.currentTimeMillis()
        val prelude = FlowSdkGenerator.generateRuntimePrelude(activeToolIds())
        val fullCode = "$prelude\n${request.script}"

        val pb = ProcessBuilder(workerBinary.toAbsolutePath().toString())
        pb.environment().clear()
        pb.redirectErrorStream(false)
        val process = pb.start()

        val callCount = AtomicInteger(0)
        val stdinMutex = Mutex()
        val callScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        try {
            val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
            val reader = process.inputStream.bufferedReader(Charsets.UTF_8)

            val initPayload = buildJsonObject {
                put("code", JsonPrimitive(fullCode))
                put("timeoutMs", JsonPrimitive(request.timeoutMs))
                put("maxMemoryBytes", JsonPrimitive(request.maxMemoryBytes))
            }.toString()
            writeLineLocked(stdinMutex, writer, initPayload)

            val finalResult = withTimeout(request.timeoutMs + 1_500L) {
                var outcome: ScriptResult? = null
                while (outcome == null) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val msg = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    when (msg["type"]?.jsonPrimitive?.contentOrNull) {
                        "call" -> {
                            val callId = msg["callId"]?.jsonPrimitive?.intOrNull ?: continue
                            val toolId = msg["toolId"]?.jsonPrimitive?.contentOrNull ?: ""
                            val argsRaw = msg["argsJson"]?.jsonPrimitive?.contentOrNull ?: "{}"
                            val seq = callCount.incrementAndGet()

                            callScope.launch {
                                val reply = handleToolCall(
                                    request = request,
                                    seq = seq,
                                    callId = callId,
                                    toolId = toolId,
                                    argsRaw = argsRaw
                                )
                                runCatching { writeLineLocked(stdinMutex, writer, reply) }
                            }
                        }
                        "result" -> {
                            val ok = msg["ok"]?.jsonPrimitive?.booleanOrNull == true
                            val interrupted = msg["interrupted"]?.jsonPrimitive?.booleanOrNull == true
                            val duration = System.currentTimeMillis() - startMs
                            if (ok) {
                                val rawOut = msg["output"]?.jsonPrimitive?.contentOrNull ?: "null"
                                val redactedOut = SecretRedactor.redact(rawOut, knownSecrets())
                                val boundedOut = enforceOutputLimit(redactedOut)
                                outcome = ScriptResult(ok = true, outputJson = boundedOut, durationMs = duration)
                            } else {
                                val errMsg = SecretRedactor.redact(
                                    msg["error"]?.jsonPrimitive?.contentOrNull ?: "JS execution failed",
                                    knownSecrets()
                                )
                                val errCode = if (interrupted) ErrorCode.TIMEOUT else ErrorCode.FAILED
                                outcome = ScriptResult(
                                    ok = false,
                                    error = FlowError(errCode, errMsg),
                                    durationMs = duration
                                )
                            }
                        }
                    }
                }
                outcome ?: ScriptResult(
                    ok = false,
                    error = FlowError(ErrorCode.FAILED, "QuickJS worker terminated unexpectedly without result"),
                    durationMs = System.currentTimeMillis() - startMs
                )
            }
            finalResult
        } catch (_: TimeoutCancellationException) {
            process.destroyForcibly()
            ScriptResult(
                ok = false,
                error = FlowError(ErrorCode.TIMEOUT, "FlowCode script exceeded ${request.timeoutMs}ms watchdog"),
                durationMs = System.currentTimeMillis() - startMs
            )
        } catch (ce: CancellationException) {
            process.destroyForcibly()
            throw ce
        } finally {
            callScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            runCatching { process.destroyForcibly() }
        }
    }

    private suspend fun handleToolCall(
        request: ScriptRequest,
        seq: Int,
        callId: Int,
        toolId: String,
        argsRaw: String
    ): String {
        if (seq > maxToolCallsPerScript) {
            return replyJson(callId, ok = false, payloadJson = "Script tool-call budget ($maxToolCallsPerScript) exceeded")
        }

        val parsedArgs: JsonObject = try {
            json.parseToJsonElement(argsRaw).jsonObject
        } catch (e: Exception) {
            return replyJson(callId, ok = false, payloadJson = "Invalid JSON arguments: ${e.message}")
        }

        val manifest = toolRegistry.get(toolId)
        val version = manifest?.version ?: "1.0.0"
        val isExclusive = manifest?.execution?.concurrency?.contains("exclusive") == true

        val toolReq = ToolRequest(
            requestId = ToolRequestId("fc-$callId-${UUID.randomUUID().toString().take(8)}"),
            idempotencyKey = "${request.missionId.value}:fc:$seq:$callId:$toolId",
            missionId = request.missionId,
            toolId = toolId,
            toolVersion = version,
            arguments = parsedArgs
        )

        val result = if (isExclusive) {
            writeMutex.withLock {
                toolBroker.invoke(toolReq, missionGrantsProvider(request.missionId), sessionApprovalsProvider(request.missionId))
            }
        } else {
            toolBroker.invoke(toolReq, missionGrantsProvider(request.missionId), sessionApprovalsProvider(request.missionId))
        }

        return if (result.status == ToolResultStatus.OK) {
            val safe = SecretRedactor.redact(result.data?.toString() ?: "{}", knownSecrets())
            replyJson(callId, ok = true, payloadJson = safe)
        } else {
            val err = SecretRedactor.redact("${result.status}: ${result.error?.message ?: "Tool failed"}", knownSecrets())
            replyJson(callId, ok = false, payloadJson = err)
        }
    }

    private fun enforceOutputLimit(output: String): String {
        val bytes = output.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxInlineOutputBytes) return output
        val artifactId = largeOutputSink?.invoke(output, "application/json")
        return buildJsonObject {
            put("truncated", JsonPrimitive(true))
            put("byteSize", JsonPrimitive(bytes.size))
            if (artifactId != null) put("artifactId", JsonPrimitive(artifactId.value))
            put("preview", JsonPrimitive(output.take(512)))
        }.toString()
    }

    private fun replyJson(callId: Int, ok: Boolean, payloadJson: String): String =
        buildJsonObject {
            put("type", JsonPrimitive("reply"))
            put("callId", JsonPrimitive(callId))
            put("ok", JsonPrimitive(ok))
            put("payloadJson", JsonPrimitive(payloadJson))
        }.toString()

    private suspend fun writeLineLocked(mutex: Mutex, writer: BufferedWriter, line: String) {
        mutex.withLock {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }
}
