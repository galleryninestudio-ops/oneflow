package ai.oneflow.core.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ModelCapabilities(
    val modelId: ModelId,
    val streamingSupported: Boolean = true,
    val toolsSupported: Boolean = true,
    val parallelToolCalls: Boolean = true,
    val visionSupported: Boolean = false,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val usageReported: Boolean = false,
    val probed: Boolean = false
)

@Serializable
enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

@Serializable
data class ChatToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ChatToolCall> = emptyList()
)

@Serializable
data class ModelToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject
)

@Serializable
data class ProviderConfig(
    val id: ProviderId,
    val name: String,
    val baseUrl: String,
    val keyAlias: String,
    val defaultModel: ModelId? = null
)

@Serializable
data class ModelRequest(
    val model: ModelId,
    val messages: List<ChatMessage>,
    val tools: List<ModelToolSpec> = emptyList(),
    val stream: Boolean = true,
    val maxTokens: Int? = null,
    val temperature: Double? = null
)

@Serializable
data class TokenUsage(
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val accuracy: UsageAccuracy
)

sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class ReasoningDelta(val text: String) : ModelEvent
    data class ToolCallCompleted(val toolCall: ChatToolCall) : ModelEvent
    data class UsageReported(val usage: TokenUsage) : ModelEvent
    data class Completed(
        val finishReason: String?,
        val fullText: String,
        val toolCalls: List<ChatToolCall>,
        val usage: TokenUsage?
    ) : ModelEvent
    data class Failed(val error: FlowError) : ModelEvent
}

@Serializable
data class ModelCatalogResult(
    val models: List<ModelId>,
    val source: String
)
