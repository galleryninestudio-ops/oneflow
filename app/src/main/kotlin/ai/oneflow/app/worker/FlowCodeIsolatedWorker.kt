package ai.oneflow.app.worker

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
import ai.oneflow.execution.flowcode.FlowSdkGenerator
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

interface HostCallBridge {
    fun onHostCall(toolId: String, argsJson: String): String
}

object QuickJsNativeBridge {
    init {
        System.loadLibrary("oneflow_qjs")
    }

    @JvmStatic
    external fun nativeExecute(
        code: String,
        timeoutMs: Long,
        maxMemoryBytes: Long,
        bridge: HostCallBridge
    ): String
}

/**
 * Isolated Android process service (`android:isolatedProcess="true"`) hosting QuickJS-NG JNI.
 * Runs under a separate OS UID with zero direct access to app-private files or network.
 */
class FlowCodeIsolatedWorkerService : Service() {

    companion object {
        const val DESCRIPTOR = "ai.oneflow.app.worker.IFlowCodeWorker"
        const val CB_DESCRIPTOR = "ai.oneflow.app.worker.IFlowCodeHostCallback"
        const val TX_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
        const val TX_HOST_CALL = IBinder.FIRST_CALL_TRANSACTION
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    true
                }
                TX_EXECUTE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val script = data.readString().orEmpty()
                    val timeoutMs = data.readLong()
                    val maxMem = data.readLong()
                    val callbackBinder = data.readStrongBinder()

                    val bridge = object : HostCallBridge {
                        override fun onHostCall(toolId: String, argsJson: String): String {
                            val reqP = Parcel.obtain()
                            val repP = Parcel.obtain()
                            return try {
                                reqP.writeInterfaceToken(CB_DESCRIPTOR)
                                reqP.writeString(toolId)
                                reqP.writeString(argsJson)
                                callbackBinder.transact(TX_HOST_CALL, reqP, repP, 0)
                                repP.readException()
                                repP.readString() ?: """{"ok":false,"payloadJson":"Empty IPC reply"}"""
                            } finally {
                                reqP.recycle()
                                repP.recycle()
                            }
                        }
                    }

                    val jsonResult = QuickJsNativeBridge.nativeExecute(script, timeoutMs, maxMem, bridge)
                    reply?.writeNoException()
                    reply?.writeString(jsonResult)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

/**
 * Trusted-process ScriptEngine client that dispatches FlowCode scripts into
 * `FlowCodeIsolatedWorkerService` over Binder IPC and routes tool calls through `SafeToolBroker`.
 */
class AndroidIsolatedQuickJsEngine(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val toolBroker: ToolBroker,
    private val activeToolIds: () -> Collection<String>,
    private val missionGrantsProvider: (MissionId) -> Set<String>,
    private val sessionApprovalsProvider: (MissionId) -> Set<String> = { emptySet() },
    private val knownSecrets: () -> Collection<String> = { emptyList() },
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ScriptEngine {

    override suspend fun execute(request: ScriptRequest): ScriptResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val connected = CompletableDeferred<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) connected.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(IllegalStateException("Isolated worker disconnected"))
                }
            }
        }

        val intent = Intent(context, FlowCodeIsolatedWorkerService::class.java)
        val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!bound) {
            return@withContext ScriptResult(
                ok = false,
                error = FlowError(ErrorCode.UNAVAILABLE, "Failed to bind FlowCodeIsolatedWorkerService")
            )
        }

        try {
            val workerBinder = withTimeout(5_000L) { connected.await() }
            val prelude = FlowSdkGenerator.generateRuntimePrelude(activeToolIds())
            val fullCode = "$prelude\n${request.script}"

            val hostCallback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != FlowCodeIsolatedWorkerService.TX_HOST_CALL) {
                        return super.onTransact(code, data, reply, flags)
                    }
                    data.enforceInterface(FlowCodeIsolatedWorkerService.CB_DESCRIPTOR)
                    val toolId = data.readString().orEmpty()
                    val argsRaw = data.readString().orEmpty()

                    val replyJson = runBlocking {
                        dispatchBrokerCall(request.missionId, toolId, argsRaw)
                    }
                    reply?.writeNoException()
                    reply?.writeString(replyJson)
                    return true
                }
            }

            val rawJson = withTimeout(request.timeoutMs + 2_000L) {
                val reqP = Parcel.obtain()
                val repP = Parcel.obtain()
                try {
                    reqP.writeInterfaceToken(FlowCodeIsolatedWorkerService.DESCRIPTOR)
                    reqP.writeString(fullCode)
                    reqP.writeLong(request.timeoutMs)
                    reqP.writeLong(request.maxMemoryBytes)
                    reqP.writeStrongBinder(hostCallback)
                    workerBinder.transact(FlowCodeIsolatedWorkerService.TX_EXECUTE, reqP, repP, 0)
                    repP.readException()
                    repP.readString() ?: """{"ok":false,"error":"Empty worker response"}"""
                } finally {
                    reqP.recycle()
                    repP.recycle()
                }
            }

            val parsed = json.parseToJsonElement(rawJson).jsonObject
            val ok = parsed["ok"]?.jsonPrimitive?.booleanOrNull == true
            val interrupted = parsed["interrupted"]?.jsonPrimitive?.booleanOrNull == true
            val duration = System.currentTimeMillis() - startMs
            if (ok) {
                val out = SecretRedactor.redact(parsed["output"]?.jsonPrimitive?.contentOrNull ?: "null", knownSecrets())
                ScriptResult(ok = true, outputJson = out, durationMs = duration)
            } else {
                val err = SecretRedactor.redact(parsed["error"]?.jsonPrimitive?.contentOrNull ?: "Worker error", knownSecrets())
                ScriptResult(
                    ok = false,
                    error = FlowError(if (interrupted) ErrorCode.TIMEOUT else ErrorCode.FAILED, err),
                    durationMs = duration
                )
            }
        } catch (_: TimeoutCancellationException) {
            ScriptResult(
                ok = false,
                error = FlowError(ErrorCode.TIMEOUT, "FlowCode isolated worker timed out after ${request.timeoutMs}ms"),
                durationMs = System.currentTimeMillis() - startMs
            )
        } catch (e: Exception) {
            ScriptResult(
                ok = false,
                error = FlowError(ErrorCode.FAILED, SecretRedactor.redact(e.message ?: "Worker IPC error", knownSecrets())),
                durationMs = System.currentTimeMillis() - startMs
            )
        } finally {
            runCatching { context.unbindService(conn) }
        }
    }

    private suspend fun dispatchBrokerCall(missionId: MissionId, toolId: String, argsRaw: String): String {
        val parsedArgs: JsonObject = try {
            json.parseToJsonElement(argsRaw).jsonObject
        } catch (e: Exception) {
            return buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("payloadJson", JsonPrimitive("Invalid JSON args: ${e.message}"))
            }.toString()
        }

        val manifest = toolRegistry.get(toolId)
        val version = manifest?.version ?: "1.0.0"
        val result = toolBroker.invoke(
            request = ToolRequest(
                requestId = ToolRequestId("iso-${UUID.randomUUID().toString().take(8)}"),
                idempotencyKey = "${missionId.value}:iso:${UUID.randomUUID()}:$toolId",
                missionId = missionId,
                toolId = toolId,
                toolVersion = version,
                arguments = parsedArgs
            ),
            missionGrants = missionGrantsProvider(missionId),
            sessionApprovals = sessionApprovalsProvider(missionId)
        )
        return if (result.status == ToolResultStatus.OK) {
            val safe = SecretRedactor.redact(result.data?.toString() ?: "{}", knownSecrets())
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("payloadJson", JsonPrimitive(safe))
            }.toString()
        } else {
            val err = SecretRedactor.redact("${result.status}: ${result.error?.message ?: "Denied"}", knownSecrets())
            buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("payloadJson", JsonPrimitive(err))
            }.toString()
        }
    }
}
