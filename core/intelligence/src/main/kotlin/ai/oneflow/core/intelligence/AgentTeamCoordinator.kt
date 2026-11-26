package ai.oneflow.core.intelligence

import ai.oneflow.core.contracts.MissionId
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SubagentBudget(
    val maxSteps: Int,
    val maxTokens: Int
)

@Serializable
data class SubagentGrantResult(
    val subagentId: String,
    val parentMissionId: MissionId,
    val effectiveCapabilities: Set<String>,
    val rejectedEscalations: Set<String>,
    val effectiveBudget: SubagentBudget
)

@Serializable
data class ResourceLock(
    val resourceKey: String,
    val ownerId: String,
    val acquiredAtMs: Long
)

/**
 * Coordinates Agent Teams:
 * - Subagents can only inherit a strict subset of parent capabilities and budget.
 * - Exclusive resource locks prevent concurrent subagents from writing the same resource.
 */
class AgentTeamCoordinator {
    private val locks = ConcurrentHashMap<String, ResourceLock>()

    fun spawnSubagentGrant(
        subagentId: String,
        parentMissionId: MissionId,
        parentCapabilities: Set<String>,
        requestedCapabilities: Set<String>,
        parentBudget: SubagentBudget,
        requestedBudget: SubagentBudget
    ): SubagentGrantResult {
        val effectiveCaps = requestedCapabilities.intersect(parentCapabilities)
        val rejected = requestedCapabilities - parentCapabilities
        val clampedBudget = SubagentBudget(
            maxSteps = requestedBudget.maxSteps.coerceIn(1, parentBudget.maxSteps.coerceAtLeast(1)),
            maxTokens = requestedBudget.maxTokens.coerceIn(1, parentBudget.maxTokens.coerceAtLeast(1))
        )
        return SubagentGrantResult(
            subagentId = subagentId,
            parentMissionId = parentMissionId,
            effectiveCapabilities = effectiveCaps,
            rejectedEscalations = rejected,
            effectiveBudget = clampedBudget
        )
    }

    fun tryAcquireLock(resourceKey: String, ownerId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val candidate = ResourceLock(resourceKey, ownerId, nowMs)
        val existing = locks.putIfAbsent(resourceKey, candidate)
        return existing == null || existing.ownerId == ownerId
    }

    fun releaseLock(resourceKey: String, ownerId: String): Boolean {
        val current = locks[resourceKey] ?: return false
        if (current.ownerId != ownerId) return false
        return locks.remove(resourceKey, current)
    }

    fun activeLocks(): List<ResourceLock> = locks.values.toList()
}
