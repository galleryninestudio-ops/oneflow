package ai.oneflow.core.contracts

import kotlinx.serialization.Serializable

@Serializable
enum class MissionState {
    CREATED,
    READY,
    RUNNING,
    VERIFYING,
    COMPLETED,
    WAITING_PERMISSION,
    WAITING_USER,
    WAITING_DEPENDENCY,
    PAUSED,
    PAUSED_BUDGET,
    RECOVERING,
    BLOCKED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isWaiting: Boolean
        get() = this == WAITING_PERMISSION ||
            this == WAITING_USER ||
            this == WAITING_DEPENDENCY ||
            this == PAUSED ||
            this == PAUSED_BUDGET ||
            this == BLOCKED
}

object MissionTransitions {
    private val allowed: Map<MissionState, Set<MissionState>> = mapOf(
        MissionState.CREATED to setOf(
            MissionState.READY,
            MissionState.CANCELLED,
            MissionState.FAILED
        ),
        MissionState.READY to setOf(
            MissionState.RUNNING,
            MissionState.PAUSED,
            MissionState.CANCELLED,
            MissionState.FAILED
        ),
        MissionState.RUNNING to setOf(
            MissionState.VERIFYING,
            MissionState.WAITING_PERMISSION,
            MissionState.WAITING_USER,
            MissionState.WAITING_DEPENDENCY,
            MissionState.PAUSED,
            MissionState.PAUSED_BUDGET,
            MissionState.RECOVERING,
            MissionState.BLOCKED,
            MissionState.FAILED,
            MissionState.CANCELLED
            // NOTE: RUNNING -> COMPLETED is explicitly forbidden; must pass VERIFYING gate.
        ),
        MissionState.VERIFYING to setOf(
            MissionState.COMPLETED,
            MissionState.RUNNING,
            MissionState.RECOVERING,
            MissionState.BLOCKED,
            MissionState.FAILED,
            MissionState.CANCELLED
        ),
        MissionState.WAITING_PERMISSION to setOf(
            MissionState.RUNNING,
            MissionState.PAUSED,
            MissionState.BLOCKED,
            MissionState.CANCELLED,
            MissionState.FAILED
        ),
        MissionState.WAITING_USER to setOf(
            MissionState.RUNNING,
            MissionState.PAUSED,
            MissionState.BLOCKED,
            MissionState.CANCELLED,
            MissionState.FAILED
        ),
        MissionState.WAITING_DEPENDENCY to setOf(
            MissionState.RUNNING,
            MissionState.PAUSED,
            MissionState.BLOCKED,
            MissionState.CANCELLED,
            MissionState.FAILED
        ),
        MissionState.PAUSED to setOf(
            MissionState.READY,
            MissionState.RUNNING,
            MissionState.CANCELLED,
            MissionState.FAILED
        ),
        MissionState.PAUSED_BUDGET to setOf(
            MissionState.READY,
            MissionState.RUNNING,
            MissionState.CANCELLED,
            MissionState.FAILED
        ),
        MissionState.RECOVERING to setOf(
            MissionState.RUNNING,
            MissionState.VERIFYING,
            MissionState.BLOCKED,
            MissionState.PAUSED,
            MissionState.FAILED,
            MissionState.CANCELLED
        ),
        MissionState.BLOCKED to setOf(
            MissionState.READY,
            MissionState.RUNNING,
            MissionState.CANCELLED,
            MissionState.FAILED
        ),
        MissionState.COMPLETED to emptySet(),
        MissionState.FAILED to emptySet(),
        MissionState.CANCELLED to emptySet()
    )

    fun canTransition(from: MissionState, to: MissionState): Boolean =
        allowed[from]?.contains(to) == true

    fun allowedTargets(from: MissionState): Set<MissionState> =
        allowed[from].orEmpty()
}

@Serializable
enum class UsageAccuracy {
    PROVIDER_REPORTED,
    ESTIMATED,
    UNKNOWN
}

@Serializable
data class BudgetSpec(
    val maxModelTurns: Int = 20,
    val maxToolCalls: Int = 100,
    val maxWallSeconds: Long = 600,
    val maxProviderSpendMicros: Long? = null
) {
    init {
        require(maxModelTurns > 0) { "maxModelTurns must be positive" }
        require(maxToolCalls > 0) { "maxToolCalls must be positive" }
        require(maxWallSeconds > 0) { "maxWallSeconds must be positive" }
        require(maxProviderSpendMicros == null || maxProviderSpendMicros >= 0) {
            "maxProviderSpendMicros must be non-negative"
        }
    }
}

@Serializable
data class BudgetUsage(
    val modelTurnsUsed: Int = 0,
    val toolCallsUsed: Int = 0,
    val wallSecondsUsed: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val spendMicros: Long? = null,
    val usageAccuracy: UsageAccuracy = UsageAccuracy.UNKNOWN
)

@Serializable
data class AcceptanceCriterion(
    val id: String,
    val description: String,
    val verifierId: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class AcceptanceCheckResult(
    val criterionId: String,
    val passed: Boolean,
    val evidenceRef: String? = null,
    val message: String
)

@Serializable
data class MissionStateSnapshot(
    val id: MissionId,
    val workspaceId: WorkspaceId,
    val objective: String,
    val requirementsRevision: Long = 1L,
    val stateRevision: Long = 0L,
    val state: MissionState = MissionState.CREATED,
    val budget: BudgetSpec = BudgetSpec(),
    val usage: BudgetUsage = BudgetUsage(),
    val allowedCapabilities: Set<String> = emptySet(),
    val acceptanceCriteria: List<AcceptanceCriterion> = emptyList(),
    val failureSignatures: Map<String, Int> = emptyMap(),
    val blockerReason: String? = null,
    val lastError: FlowError? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
