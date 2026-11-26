package ai.oneflow.core.contracts

import kotlinx.serialization.Serializable

@Serializable
data class MissionLease(
    val missionId: MissionId,
    val leaseId: String,
    val holderId: String,
    val fencingToken: Long,
    val acquiredAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

interface LeaseManager {
    suspend fun acquire(missionId: MissionId, holderId: String, ttlMs: Long = 30_000L): MissionLease?
    suspend fun renew(lease: MissionLease, ttlMs: Long = 30_000L): MissionLease?
    suspend fun release(lease: MissionLease)
    suspend fun current(missionId: MissionId): MissionLease?
}

@Serializable
data class PendingApproval(
    val requestId: ToolRequestId,
    val toolId: String,
    val toolVersion: String,
    val risk: RiskTier,
    val reason: String,
    val redactedTarget: String,
    val argumentsJson: String
)

@Serializable
data class MissionConversationEntry(
    val turnIndex: Int,
    val message: ChatMessage,
    val timestampEpochMs: Long
)

@Serializable
data class ContextCompileSpec(
    val maxEstimatedTokens: Int = 8_000,
    val systemPolicyInstructions: String,
    val trustedWorkspaceInstructions: String? = null
)

@Serializable
data class CompiledContextResult(
    val request: ModelRequest,
    val estimatedTokens: Int,
    val omittedMessageCount: Int,
    val preservedPendingApproval: Boolean
)

@Serializable
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val notes: String
)

object ProviderPresets {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            defaultBaseUrl = "https://api.openai.com/v1",
            notes = "Official OpenAI Chat Completions endpoint"
        ),
        ProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            notes = "OpenAI-compatible multi-provider gateway"
        ),
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            defaultBaseUrl = "https://api.deepseek.com",
            notes = "DeepSeek OpenAI-compatible API"
        ),
        ProviderPreset(
            id = "gemini_compat",
            displayName = "Gemini (OpenAI Compat)",
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            notes = "Google Gemini OpenAI compatibility endpoint"
        ),
        ProviderPreset(
            id = "groq",
            displayName = "Groq",
            defaultBaseUrl = "https://api.groq.com/openai/v1",
            notes = "Groq OpenAI-compatible endpoint"
        ),
        ProviderPreset(
            id = "custom",
            displayName = "Custom / Self-Hosted",
            defaultBaseUrl = "http://127.0.0.1:8080/v1",
            notes = "User-defined OpenAI-compatible server"
        )
    )
}
