package ai.oneflow.core.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class RiskTier {
    READ_ONLY,
    REVERSIBLE_WRITE,
    DISRUPTIVE_LOCAL,
    EXTERNAL_SIDE_EFFECT,
    SENSITIVE_DATA,
    IRREVERSIBLE
}

@Serializable
enum class PolicyMode {
    ASK_SENSITIVE,
    SESSION_APPROVAL,
    TRUSTED_WORKSPACE,
    SCOPED_AUTONOMOUS,
    CUSTOM
}

@Serializable
enum class ToolResultStatus {
    OK,
    UNAVAILABLE,
    INVALID_ARGUMENT,
    DENIED,
    APPROVAL_REQUIRED,
    STALE_STATE,
    TIMEOUT,
    CANCELLED,
    FAILED,
    UNKNOWN_OUTCOME,
    BUDGET_EXCEEDED
}

@Serializable
enum class RetryDisposition {
    NO_RETRY,
    SAFE_RETRY,
    RECONCILE_FIRST
}

@Serializable
data class ToolExecutionPolicy(
    val timeoutMs: Long = 15_000L,
    val cancellable: Boolean = true,
    val retry: RetryDisposition = RetryDisposition.RECONCILE_FIRST,
    val concurrency: String = "exclusive_resource"
)

@Serializable
data class ToolManifest(
    val id: String,
    val version: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val requiredCapabilities: Set<String> = emptySet(),
    val risk: RiskTier = RiskTier.READ_ONLY,
    val runtime: String = "broker",
    val availability: String = "always",
    val resources: List<String> = emptyList(),
    val execution: ToolExecutionPolicy = ToolExecutionPolicy(),
    val tags: Set<String> = emptySet(),
    val exampleUsage: String? = null
)

@Serializable
data class ToolQuery(
    val query: String,
    val limit: Int = 10,
    val namespace: String? = null
) {
    init {
        require(limit in 1..50) { "limit must be between 1 and 50" }
    }
}

@Serializable
data class ToolDefinition(
    val id: String,
    val version: String,
    val description: String,
    val inputSchema: JsonObject,
    val requiredCapabilities: Set<String>,
    val risk: RiskTier,
    val available: Boolean,
    val exampleUsage: String? = null
)

@Serializable
data class ToolRequest(
    val requestId: ToolRequestId,
    val idempotencyKey: String,
    val missionId: MissionId,
    val taskId: TaskId? = null,
    val agentId: AgentId? = null,
    val toolId: String,
    val toolVersion: String,
    val arguments: JsonObject,
    val scope: Set<String> = emptySet(),
    val expectedStateRevision: Long? = null,
    val deadlineEpochMs: Long? = null,
    val correlationId: CorrelationId? = null
)

@Serializable
data class ToolResult(
    val requestId: ToolRequestId,
    val status: ToolResultStatus,
    val data: JsonObject? = null,
    val error: FlowError? = null,
    val artifactRefs: List<ArtifactId> = emptyList(),
    val durationMs: Long = 0L,
    val retryDisposition: RetryDisposition = RetryDisposition.NO_RETRY
)

@Serializable
data class ToolExecutionReceipt(
    val requestId: ToolRequestId,
    val idempotencyKey: String,
    val toolId: String,
    val toolVersion: String,
    val status: ToolResultStatus,
    val timestampEpochMs: Long,
    val redactedSummary: String
)
