package ai.oneflow.core.models

import ai.oneflow.core.contracts.ChatToolCall
import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.FlowError
import ai.oneflow.core.contracts.ModelEvent
import ai.oneflow.core.contracts.SecretRedactor
import ai.oneflow.core.contracts.TokenUsage
import ai.oneflow.core.contracts.UsageAccuracy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.BufferedSource

/**
 * Incremental parser for OpenAI-compatible Chat Completions SSE streams.
 * Safely handles fragmented UTF-8 sequences (via OkHttp BufferedSource),
 * fragmented tool_calls arguments indexed by `index`, duplicate/conflicting ID rejection,
 * optional `usage`, and `[DONE]` termination.
 */
class OpenAiSseParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val knownSecrets: () -> Collection<String> = { emptyList() }
) {
    private data class ToolCallAccumulator(
        var id: String? = null,
        var name: String? = null,
        val args: StringBuilder = StringBuilder()
    )

    fun parse(source: BufferedSource): Flow<ModelEvent> = flow {
        val textBuffer = StringBuilder()
        val toolAccumulators = linkedMapOf<Int, ToolCallAccumulator>()
        val seenDistinctIds = mutableMapOf<String, Int>()
        var lastFinishReason: String? = null
        var reportedUsage: TokenUsage? = null
        var doneSeen = false
        val eventDataLines = mutableListOf<String>()

        suspend fun processEventData(rawData: String): Boolean {
            val trimmed = rawData.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed == "[DONE]") {
                doneSeen = true
                return true
            }

            val root = try {
                json.parseToJsonElement(trimmed).jsonObject
            } catch (e: Exception) {
                emit(
                    ModelEvent.Failed(
                        FlowError(
                            code = ErrorCode.PROVIDER_PROTOCOL_ERROR,
                            message = SecretRedactor.redact("Malformed SSE JSON chunk: ${e.message}", knownSecrets())
                        )
                    )
                )
                return true
            }

            val errObj = root["error"] as? JsonObject
            if (errObj != null) {
                val msg = errObj["message"]?.jsonPrimitive?.contentOrNull ?: "Provider stream error"
                emit(
                    ModelEvent.Failed(
                        FlowError(
                            code = ErrorCode.PROVIDER_PROTOCOL_ERROR,
                            message = SecretRedactor.redact(msg, knownSecrets())
                        )
                    )
                )
                return true
            }

            (root["usage"] as? JsonObject)?.let { usageObj ->
                val prompt = usageObj["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                val completion = usageObj["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
                val total = usageObj["total_tokens"]?.jsonPrimitive?.longOrNull ?: (prompt + completion)
                val usage = TokenUsage(
                    promptTokens = prompt,
                    completionTokens = completion,
                    totalTokens = total,
                    accuracy = UsageAccuracy.PROVIDER_REPORTED
                )
                reportedUsage = usage
                emit(ModelEvent.UsageReported(usage))
            }

            val choices = root["choices"] as? JsonArray
            val firstChoice = choices?.firstOrNull() as? JsonObject
            if (firstChoice != null) {
                firstChoice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                    if (reason.isNotBlank()) lastFinishReason = reason
                }

                val delta = firstChoice["delta"] as? JsonObject
                if (delta != null) {
                    val content = (delta["content"] as? JsonPrimitive)?.contentOrNull
                    if (!content.isNullOrEmpty()) {
                        textBuffer.append(content)
                        emit(ModelEvent.TextDelta(content))
                    }

                    val reasoning = ((delta["reasoning_content"] ?: delta["reasoning"]) as? JsonPrimitive)?.contentOrNull
                    if (!reasoning.isNullOrEmpty()) {
                        emit(ModelEvent.ReasoningDelta(reasoning))
                    }

                    val toolCalls = delta["tool_calls"] as? JsonArray
                    if (toolCalls != null) {
                        for (tcElem in toolCalls) {
                            val tc = tcElem as? JsonObject ?: continue
                            val idx = tc["index"]?.jsonPrimitive?.intOrNull
                            if (idx == null || idx < 0) {
                                emit(
                                    ModelEvent.Failed(
                                        FlowError(
                                            code = ErrorCode.PROVIDER_PROTOCOL_ERROR,
                                            message = "Missing or invalid tool_call index in SSE delta"
                                        )
                                    )
                                )
                                return true
                            }
                            val acc = toolAccumulators.getOrPut(idx) { ToolCallAccumulator() }
                            val incomingId = (tc["id"] as? JsonPrimitive)?.contentOrNull
                            if (!incomingId.isNullOrBlank()) {
                                val priorIndexForId = seenDistinctIds[incomingId]
                                if (priorIndexForId != null && priorIndexForId != idx) {
                                    emit(
                                        ModelEvent.Failed(
                                            FlowError(
                                                code = ErrorCode.PROVIDER_PROTOCOL_ERROR,
                                                message = "Duplicate tool_call id '$incomingId' across indices $priorIndexForId and $idx"
                                            )
                                        )
                                    )
                                    return true
                                }
                                if (acc.id != null && acc.id != incomingId) {
                                    emit(
                                        ModelEvent.Failed(
                                            FlowError(
                                                code = ErrorCode.PROVIDER_PROTOCOL_ERROR,
                                                message = "Conflicting tool_call id at index $idx: '${acc.id}' vs '$incomingId'"
                                            )
                                        )
                                    )
                                    return true
                                }
                                acc.id = incomingId
                                seenDistinctIds[incomingId] = idx
                            }

                            val fn = tc["function"] as? JsonObject
                            if (fn != null) {
                                val fnName = (fn["name"] as? JsonPrimitive)?.contentOrNull
                                if (!fnName.isNullOrEmpty()) {
                                    acc.name = (acc.name ?: "") + fnName
                                }
                                val argsFrag = (fn["arguments"] as? JsonPrimitive)?.contentOrNull
                                if (!argsFrag.isNullOrEmpty()) {
                                    acc.args.append(argsFrag)
                                }
                            }
                        }
                    }
                }
            }
            return false
        }

        var failed = false
        while (!doneSeen) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            if (line.startsWith(":")) continue
            if (line.isEmpty()) {
                if (eventDataLines.isNotEmpty()) {
                    val payload = eventDataLines.joinToString("\n")
                    eventDataLines.clear()
                    if (processEventData(payload)) {
                        if (!doneSeen) failed = true
                        if (failed || doneSeen) break
                    }
                }
                continue
            }
            if (line.startsWith("data:")) {
                val dataContent = line.removePrefix("data:").let {
                    if (it.startsWith(" ")) it.substring(1) else it
                }
                eventDataLines += dataContent
            }
        }

        if (!failed && !doneSeen && eventDataLines.isNotEmpty()) {
            val payload = eventDataLines.joinToString("\n")
            eventDataLines.clear()
            if (processEventData(payload) && !doneSeen) {
                failed = true
            }
        }

        if (failed) return@flow

        val assembledCalls = mutableListOf<ChatToolCall>()
        for ((idx, acc) in toolAccumulators.entries.sortedBy { it.key }) {
            val id = acc.id
            val name = acc.name
            if (id.isNullOrBlank() || name.isNullOrBlank()) {
                emit(
                    ModelEvent.Failed(
                        FlowError(
                            code = ErrorCode.PROVIDER_PROTOCOL_ERROR,
                            message = "Incomplete tool call at index $idx (id=$id, name=$name)"
                        )
                    )
                )
                return@flow
            }
            val argsJson = acc.args.toString().ifBlank { "{}" }
            try {
                json.parseToJsonElement(argsJson).jsonObject
            } catch (e: Exception) {
                emit(
                    ModelEvent.Failed(
                        FlowError(
                            code = ErrorCode.PROVIDER_PROTOCOL_ERROR,
                            message = "Invalid tool_call arguments JSON for '$name': ${e.message}"
                        )
                    )
                )
                return@flow
            }
            val completedCall = ChatToolCall(id = id, name = name, argumentsJson = argsJson)
            assembledCalls += completedCall
            emit(ModelEvent.ToolCallCompleted(completedCall))
        }

        emit(
            ModelEvent.Completed(
                finishReason = lastFinishReason,
                fullText = textBuffer.toString(),
                toolCalls = assembledCalls,
                usage = reportedUsage
            )
        )
    }
}
