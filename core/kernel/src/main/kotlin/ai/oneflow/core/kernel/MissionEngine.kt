package ai.oneflow.core.kernel

import ai.oneflow.core.contracts.BudgetSpec
import ai.oneflow.core.contracts.BudgetUsage
import ai.oneflow.core.contracts.CorrelationId
import ai.oneflow.core.contracts.EffectStatus
import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.EventId
import ai.oneflow.core.contracts.FlowError
import ai.oneflow.core.contracts.MissionEventEnvelope
import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.MissionState
import ai.oneflow.core.contracts.MissionStateSnapshot
import ai.oneflow.core.contracts.MissionStore
import ai.oneflow.core.contracts.MissionTransitions
import ai.oneflow.core.contracts.UsageAccuracy
import ai.oneflow.core.contracts.VerificationRequest
import ai.oneflow.core.contracts.VerificationResult
import ai.oneflow.core.contracts.VerificationStatus
import ai.oneflow.core.contracts.Verifier
import ai.oneflow.core.contracts.WorkspaceId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

class InMemoryMissionStore : MissionStore {
    private val mutex = Mutex()
    private val missions = mutableMapOf<MissionId, MissionStateSnapshot>()
    private val eventLog = mutableMapOf<MissionId, MutableList<MissionEventEnvelope>>()

    override suspend fun create(mission: MissionStateSnapshot) {
        mutex.withLock {
            require(!missions.containsKey(mission.id)) { "Mission ${mission.id.value} already exists" }
            missions[mission.id] = mission
            eventLog[mission.id] = mutableListOf()
        }
    }

    override suspend fun load(id: MissionId): MissionStateSnapshot? = mutex.withLock {
        missions[id]
    }

    override suspend fun append(event: MissionEventEnvelope, updatedState: MissionStateSnapshot?) {
        mutex.withLock {
            val current = missions[event.missionId]
                ?: error("Mission ${event.missionId.value} does not exist")
            val list = eventLog.getOrPut(event.missionId) { mutableListOf() }
            val expectedSeq = (list.lastOrNull()?.sequence ?: 0L) + 1L
            require(event.sequence == expectedSeq) {
                "Expected event sequence $expectedSeq but got ${event.sequence}"
            }
            if (updatedState != null) {
                require(updatedState.stateRevision == current.stateRevision + 1L) {
                    "CAS stateRevision mismatch: current=${current.stateRevision}, next=${updatedState.stateRevision}"
                }
                missions[event.missionId] = updatedState
            }
            list += event
        }
    }

    override suspend fun events(id: MissionId): List<MissionEventEnvelope> = mutex.withLock {
        eventLog[id]?.toList().orEmpty()
    }
}

class FileMissionStore(
    private val rootDir: Path,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : MissionStore {
    private val mutex = Mutex()

    init {
        Files.createDirectories(rootDir)
    }

    private fun missionDir(id: MissionId): Path = rootDir.resolve(id.value.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    private fun snapshotFile(id: MissionId): Path = missionDir(id).resolve("snapshot.json")
    private fun eventsFile(id: MissionId): Path = missionDir(id).resolve("events.jsonl")

    override suspend fun create(mission: MissionStateSnapshot) {
        mutex.withLock {
            val dir = missionDir(mission.id)
            Files.createDirectories(dir)
            val snap = snapshotFile(mission.id)
            require(!Files.exists(snap)) { "Mission ${mission.id.value} already exists" }
            writeAtomic(snap, json.encodeToString(mission))
            if (!Files.exists(eventsFile(mission.id))) {
                Files.createFile(eventsFile(mission.id))
            }
        }
    }

    override suspend fun load(id: MissionId): MissionStateSnapshot? = mutex.withLock {
        val snap = snapshotFile(id)
        if (!Files.exists(snap)) return@withLock null
        json.decodeFromString<MissionStateSnapshot>(Files.readString(snap))
    }

    override suspend fun append(event: MissionEventEnvelope, updatedState: MissionStateSnapshot?) {
        mutex.withLock {
            val snapPath = snapshotFile(event.missionId)
            require(Files.exists(snapPath)) { "Mission ${event.missionId.value} does not exist" }
            val current = json.decodeFromString<MissionStateSnapshot>(Files.readString(snapPath))
            val existingEvents = readEventsLocked(event.missionId)
            val expectedSeq = (existingEvents.lastOrNull()?.sequence ?: 0L) + 1L
            require(event.sequence == expectedSeq) {
                "Expected event sequence $expectedSeq but got ${event.sequence}"
            }
            val line = json.encodeToString(event) + "\n"
            Files.writeString(
                eventsFile(event.missionId),
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
            if (updatedState != null) {
                require(updatedState.stateRevision == current.stateRevision + 1L) {
                    "CAS stateRevision mismatch: current=${current.stateRevision}, next=${updatedState.stateRevision}"
                }
                writeAtomic(snapPath, json.encodeToString(updatedState))
            }
        }
    }

    override suspend fun events(id: MissionId): List<MissionEventEnvelope> = mutex.withLock {
        readEventsLocked(id)
    }

    private fun readEventsLocked(id: MissionId): List<MissionEventEnvelope> {
        val file = eventsFile(id)
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file)
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<MissionEventEnvelope>(it) }
    }

    private fun writeAtomic(target: Path, content: String) {
        val tmp = target.parent.resolve("${target.fileName}.tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

sealed interface KernelResult<out T> {
    data class Ok<T>(val value: T) : KernelResult<T>
    data class Err(val error: FlowError) : KernelResult<Nothing>
}

class FlowGuard(
    private val maxRepeatedFailures: Int = 3
) {
    init {
        require(maxRepeatedFailures >= 2) { "maxRepeatedFailures must be >= 2" }
    }

    fun normalizeSignature(category: String, detail: String): String {
        val normalized = detail.lowercase()
            .replace(Regex("[0-9]+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
        return "$category:$normalized"
    }

    fun shouldTrigger(failureCounts: Map<String, Int>, signature: String): Boolean =
        (failureCounts[signature] ?: 0) >= maxRepeatedFailures
}

class MissionController(
    private val store: MissionStore,
    private val guard: FlowGuard = FlowGuard(),
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun createMission(
        workspaceId: WorkspaceId,
        objective: String,
        budget: BudgetSpec = BudgetSpec(),
        allowedCapabilities: Set<String> = emptySet(),
        missionId: MissionId = MissionId("m-${UUID.randomUUID()}")
    ): MissionStateSnapshot {
        val now = clockEpochMs()
        val initial = MissionStateSnapshot(
            id = missionId,
            workspaceId = workspaceId,
            objective = objective,
            budget = budget,
            allowedCapabilities = allowedCapabilities,
            state = MissionState.CREATED,
            stateRevision = 0L,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        store.create(initial)
        store.append(
            MissionEventEnvelope(
                eventId = EventId("e-${UUID.randomUUID()}"),
                sequence = 1L,
                timestampEpochMs = now,
                missionId = missionId,
                type = "mission.created",
                actorSource = "kernel",
                payload = buildJsonObject {
                    put("objective", JsonPrimitive(objective))
                    put("state", JsonPrimitive(MissionState.CREATED.name))
                }
            )
        )
        return initial
    }

    suspend fun transition(
        missionId: MissionId,
        expectedRevision: Long,
        targetState: MissionState,
        reason: String,
        actorSource: String = "kernel",
        correlationId: CorrelationId? = null,
        error: FlowError? = null
    ): KernelResult<MissionStateSnapshot> {
        val current = store.load(missionId)
            ?: return KernelResult.Err(FlowError(ErrorCode.UNAVAILABLE, "Mission ${missionId.value} not found"))

        if (current.stateRevision != expectedRevision) {
            return KernelResult.Err(
                FlowError(
                    ErrorCode.REVISION_CONFLICT,
                    "Expected revision $expectedRevision but actual is ${current.stateRevision}"
                )
            )
        }
        if (!MissionTransitions.canTransition(current.state, targetState)) {
            return KernelResult.Err(
                FlowError(
                    ErrorCode.INVALID_ARGUMENT,
                    "Invalid mission state transition ${current.state} -> $targetState"
                )
            )
        }

        val now = clockEpochMs()
        val nextSeq = (store.events(missionId).lastOrNull()?.sequence ?: 0L) + 1L
        val nextSnapshot = current.copy(
            state = targetState,
            stateRevision = current.stateRevision + 1L,
            blockerReason = if (targetState == MissionState.BLOCKED) reason else current.blockerReason,
            lastError = error ?: current.lastError,
            updatedAtEpochMs = now
        )
        val event = MissionEventEnvelope(
            eventId = EventId("e-${UUID.randomUUID()}"),
            sequence = nextSeq,
            timestampEpochMs = now,
            missionId = missionId,
            type = "mission.state_changed",
            correlationId = correlationId,
            actorSource = actorSource,
            payload = buildJsonObject {
                put("from", JsonPrimitive(current.state.name))
                put("to", JsonPrimitive(targetState.name))
                put("reason", JsonPrimitive(reason))
            }
        )
        store.append(event, nextSnapshot)
        return KernelResult.Ok(nextSnapshot)
    }

    suspend fun recordTurnUsage(
        missionId: MissionId,
        expectedRevision: Long,
        addModelTurns: Int = 1,
        addToolCalls: Int = 0,
        addPromptTokens: Long = 0L,
        addCompletionTokens: Long = 0L,
        accuracy: UsageAccuracy = UsageAccuracy.UNKNOWN
    ): KernelResult<MissionStateSnapshot> {
        val current = store.load(missionId)
            ?: return KernelResult.Err(FlowError(ErrorCode.UNAVAILABLE, "Mission ${missionId.value} not found"))
        if (current.stateRevision != expectedRevision) {
            return KernelResult.Err(FlowError(ErrorCode.REVISION_CONFLICT, "Revision mismatch"))
        }

        val nextUsage = current.usage.copy(
            modelTurnsUsed = current.usage.modelTurnsUsed + addModelTurns,
            toolCallsUsed = current.usage.toolCallsUsed + addToolCalls,
            promptTokens = current.usage.promptTokens + addPromptTokens,
            completionTokens = current.usage.completionTokens + addCompletionTokens,
            usageAccuracy = if (accuracy == UsageAccuracy.PROVIDER_REPORTED) {
                UsageAccuracy.PROVIDER_REPORTED
            } else {
                current.usage.usageAccuracy
            }
        )
        val exhausted = nextUsage.modelTurnsUsed > current.budget.maxModelTurns ||
            nextUsage.toolCallsUsed > current.budget.maxToolCalls

        val targetState = if (exhausted && MissionTransitions.canTransition(current.state, MissionState.PAUSED_BUDGET)) {
            MissionState.PAUSED_BUDGET
        } else {
            current.state
        }

        val now = clockEpochMs()
        val nextSeq = (store.events(missionId).lastOrNull()?.sequence ?: 0L) + 1L
        val nextSnapshot = current.copy(
            state = targetState,
            stateRevision = current.stateRevision + 1L,
            usage = nextUsage,
            updatedAtEpochMs = now
        )
        store.append(
            MissionEventEnvelope(
                eventId = EventId("e-${UUID.randomUUID()}"),
                sequence = nextSeq,
                timestampEpochMs = now,
                missionId = missionId,
                type = if (exhausted) "budget.exhausted" else "budget.debited",
                actorSource = "kernel",
                payload = buildJsonObject {
                    put("turns", JsonPrimitive(nextUsage.modelTurnsUsed))
                    put("toolCalls", JsonPrimitive(nextUsage.toolCallsUsed))
                    put("accuracy", JsonPrimitive(nextUsage.usageAccuracy.name))
                }
            ),
            nextSnapshot
        )
        return KernelResult.Ok(nextSnapshot)
    }

    suspend fun recordFailure(
        missionId: MissionId,
        expectedRevision: Long,
        category: String,
        detail: String
    ): KernelResult<MissionStateSnapshot> {
        val current = store.load(missionId)
            ?: return KernelResult.Err(FlowError(ErrorCode.UNAVAILABLE, "Mission ${missionId.value} not found"))
        if (current.stateRevision != expectedRevision) {
            return KernelResult.Err(FlowError(ErrorCode.REVISION_CONFLICT, "Revision mismatch"))
        }

        val sig = guard.normalizeSignature(category, detail)
        val nextCount = (current.failureSignatures[sig] ?: 0) + 1
        val nextSignatures = current.failureSignatures + (sig to nextCount)
        val guardTripped = guard.shouldTrigger(nextSignatures, sig)
        val nextState = if (guardTripped && MissionTransitions.canTransition(current.state, MissionState.PAUSED)) {
            MissionState.PAUSED
        } else {
            current.state
        }

        val now = clockEpochMs()
        val nextSeq = (store.events(missionId).lastOrNull()?.sequence ?: 0L) + 1L
        val nextError = FlowError(
            code = if (guardTripped) ErrorCode.GUARD_TRIGGERED else ErrorCode.FAILED,
            message = if (guardTripped) "FlowGuard tripped after $nextCount repeated failures ($sig)" else detail
        )
        val nextSnapshot = current.copy(
            state = nextState,
            stateRevision = current.stateRevision + 1L,
            failureSignatures = nextSignatures,
            lastError = nextError,
            updatedAtEpochMs = now
        )
        store.append(
            MissionEventEnvelope(
                eventId = EventId("e-${UUID.randomUUID()}"),
                sequence = nextSeq,
                timestampEpochMs = now,
                missionId = missionId,
                type = if (guardTripped) "guard.triggered" else "mission.failure_recorded",
                actorSource = "guard",
                effectStatus = EffectStatus.FAILED,
                payload = buildJsonObject {
                    put("signature", JsonPrimitive(sig))
                    put("count", JsonPrimitive(nextCount))
                }
            ),
            nextSnapshot
        )
        return KernelResult.Ok(nextSnapshot)
    }

    suspend fun evaluateCompletion(
        missionId: MissionId,
        expectedRevision: Long,
        verifier: Verifier,
        candidateResponseText: String? = null
    ): KernelResult<Pair<MissionStateSnapshot, VerificationResult>> {
        val verifying = when (val res = transition(missionId, expectedRevision, MissionState.VERIFYING, "Enter verification gate")) {
            is KernelResult.Err -> return res
            is KernelResult.Ok -> res.value
        }

        val result = verifier.evaluate(VerificationRequest(verifying, candidateResponseText))
        val target = when (result.status) {
            VerificationStatus.PASS -> MissionState.COMPLETED
            VerificationStatus.NEEDS_WORK -> MissionState.RUNNING
            VerificationStatus.BLOCKED -> MissionState.BLOCKED
        }
        val finalRes = transition(
            missionId = missionId,
            expectedRevision = verifying.stateRevision,
            targetState = target,
            reason = result.summary,
            actorSource = "verifier"
        )
        return when (finalRes) {
            is KernelResult.Err -> finalRes
            is KernelResult.Ok -> KernelResult.Ok(finalRes.value to result)
        }
    }
}
