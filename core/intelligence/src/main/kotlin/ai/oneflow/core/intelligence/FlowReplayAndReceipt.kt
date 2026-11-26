package ai.oneflow.core.intelligence

import ai.oneflow.core.contracts.AcceptanceCheckResult
import ai.oneflow.core.contracts.BudgetUsage
import ai.oneflow.core.contracts.MissionEventEnvelope
import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.MissionState
import ai.oneflow.core.contracts.MissionStateSnapshot
import ai.oneflow.core.contracts.MissionStore
import ai.oneflow.core.contracts.WorkspaceId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Serializable
data class ReplayFrame(
    val sequence: Long,
    val timestampEpochMs: Long,
    val eventType: String,
    val actorSource: String,
    val stateAfter: MissionState?,
    val summary: String
)

@Serializable
data class ReplayReport(
    val missionId: MissionId,
    val frames: List<ReplayFrame>,
    val externalSideEffectsExecuted: Int = 0
)

/**
 * Read-only FlowReplay & fork engine (§8.2).
 * Reconstructs mission timeline strictly from recorded events with ZERO real tool/model calls.
 */
class FlowReplayEngine(private val store: MissionStore) {

    suspend fun replayReadOnly(missionId: MissionId): ReplayReport {
        val events = store.events(missionId)
        var inferredState: MissionState? = null
        val frames = events.map { ev ->
            when (ev.type) {
                "mission.created" -> inferredState = MissionState.CREATED
                "mission.state_changed" -> {
                    val to = ev.payload["to"]?.jsonPrimitive?.contentOrNull
                    if (to != null) {
                        inferredState = runCatching { MissionState.valueOf(to) }.getOrDefault(inferredState)
                    }
                }
                "permission.approved" -> inferredState = MissionState.RUNNING
                "budget.exhausted" -> inferredState = MissionState.PAUSED_BUDGET
            }
            ReplayFrame(
                sequence = ev.sequence,
                timestampEpochMs = ev.timestampEpochMs,
                eventType = ev.type,
                actorSource = ev.actorSource,
                stateAfter = inferredState,
                summary = ev.payload.toString()
            )
        }
        return ReplayReport(
            missionId = missionId,
            frames = frames,
            externalSideEffectsExecuted = 0
        )
    }

    suspend fun forkMission(
        sourceId: MissionId,
        targetWorkspaceId: WorkspaceId? = null
    ): MissionStateSnapshot? {
        val source = store.load(sourceId) ?: return null
        val now = System.currentTimeMillis()
        val forked = source.copy(
            id = MissionId("m-fork-${UUID.randomUUID().toString().take(8)}"),
            workspaceId = targetWorkspaceId ?: source.workspaceId,
            state = MissionState.READY,
            stateRevision = 0L,
            allowedCapabilities = emptySet(), // Independent grants required for fork (§8.2)
            approvedTools = emptySet(),
            pendingApproval = null,
            activeFencingToken = 0L,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        store.create(forked)
        return forked
    }
}

@Serializable
data class MissionReceipt(
    val missionId: MissionId,
    val objective: String,
    val requirementsRevision: Long,
    val finalState: MissionState,
    val acceptanceResults: List<AcceptanceCheckResult>,
    val toolCallsObserved: Int,
    val usage: BudgetUsage,
    val undoableTransactions: List<String>,
    val eventCount: Int
)

object MissionReceiptGenerator {
    suspend fun generate(
        store: MissionStore,
        missionId: MissionId,
        acceptanceResults: List<AcceptanceCheckResult> = emptyList(),
        undoableTransactions: List<String> = emptyList()
    ): MissionReceipt? {
        val snap = store.load(missionId) ?: return null
        val events = store.events(missionId)
        val toolCount = events.count { it.type == "effect.observed" }
        return MissionReceipt(
            missionId = snap.id,
            objective = snap.objective,
            requirementsRevision = snap.requirementsRevision,
            finalState = snap.state,
            acceptanceResults = acceptanceResults,
            toolCallsObserved = toolCount,
            usage = snap.usage,
            undoableTransactions = undoableTransactions,
            eventCount = events.size
        )
    }
}
