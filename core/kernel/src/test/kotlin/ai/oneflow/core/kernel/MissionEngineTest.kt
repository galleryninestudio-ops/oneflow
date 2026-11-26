package ai.oneflow.core.kernel

import ai.oneflow.core.contracts.AcceptanceCheckResult
import ai.oneflow.core.contracts.BudgetSpec
import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.MissionState
import ai.oneflow.core.contracts.VerificationRequest
import ai.oneflow.core.contracts.VerificationResult
import ai.oneflow.core.contracts.VerificationStatus
import ai.oneflow.core.contracts.Verifier
import ai.oneflow.core.contracts.WorkspaceId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MissionEngineTest {

    @Test
    fun `happy path transitions created ready running verifying completed with evidence`(@TempDir tempDir: Path) = runTest {
        val store = FileMissionStore(tempDir)
        val controller = MissionController(store)

        val created = controller.createMission(WorkspaceId("ws-1"), "Build website")
        assertEquals(MissionState.CREATED, created.state)

        val ready = (controller.transition(created.id, created.stateRevision, MissionState.READY, "validated") as KernelResult.Ok).value
        val running = (controller.transition(ready.id, ready.stateRevision, MissionState.RUNNING, "started") as KernelResult.Ok).value

        // Direct RUNNING -> COMPLETED must be rejected
        val invalid = controller.transition(running.id, running.stateRevision, MissionState.COMPLETED, "skip verify")
        assertTrue(invalid is KernelResult.Err)
        assertEquals(ErrorCode.INVALID_ARGUMENT, (invalid as KernelResult.Err).error.code)

        // Verification gate decides completion
        val verifier = object : Verifier {
            override suspend fun evaluate(request: VerificationRequest): VerificationResult = VerificationResult(
                status = VerificationStatus.PASS,
                checks = listOf(AcceptanceCheckResult("index", true, "art-1", "index.html present")),
                summary = "All acceptance checks passed"
            )
        }
        val (completed, ver) = (controller.evaluateCompletion(running.id, running.stateRevision, verifier) as KernelResult.Ok).value
        assertEquals(MissionState.COMPLETED, completed.state)
        assertEquals(VerificationStatus.PASS, ver.status)

        // Reload from disk store to prove persistence
        val reloaded = store.load(created.id)!!
        assertEquals(MissionState.COMPLETED, reloaded.state)
        val events = store.events(created.id)
        assertEquals(5, events.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), events.map { it.sequence })
    }

    @Test
    fun `stale revision fails CAS and budget exhaustion pauses mission`() = runTest {
        val store = InMemoryMissionStore()
        val controller = MissionController(store)

        val created = controller.createMission(
            workspaceId = WorkspaceId("ws-2"),
            objective = "Bounded work",
            budget = BudgetSpec(maxModelTurns = 1, maxToolCalls = 5)
        )
        val ready = (controller.transition(created.id, created.stateRevision, MissionState.READY, "go") as KernelResult.Ok).value
        val running = (controller.transition(ready.id, ready.stateRevision, MissionState.RUNNING, "run") as KernelResult.Ok).value

        val stale = controller.transition(running.id, 0L, MissionState.PAUSED, "stale")
        assertTrue(stale is KernelResult.Err)
        assertEquals(ErrorCode.REVISION_CONFLICT, (stale as KernelResult.Err).error.code)

        val turn1 = (controller.recordTurnUsage(running.id, running.stateRevision, addModelTurns = 1) as KernelResult.Ok).value
        assertEquals(MissionState.RUNNING, turn1.state)

        val turn2 = (controller.recordTurnUsage(turn1.id, turn1.stateRevision, addModelTurns = 1) as KernelResult.Ok).value
        assertEquals(MissionState.PAUSED_BUDGET, turn2.state)
    }

    @Test
    fun `flow guard trips after 3 repeated failure signatures`() = runTest {
        val store = InMemoryMissionStore()
        val controller = MissionController(store, FlowGuard(maxRepeatedFailures = 3))

        val created = controller.createMission(WorkspaceId("ws-3"), "Guard test")
        val ready = (controller.transition(created.id, created.stateRevision, MissionState.READY, "go") as KernelResult.Ok).value
        var state = (controller.transition(ready.id, ready.stateRevision, MissionState.RUNNING, "run") as KernelResult.Ok).value

        state = (controller.recordFailure(state.id, state.stateRevision, "tool", "Timeout after 5000 ms") as KernelResult.Ok).value
        assertEquals(MissionState.RUNNING, state.state)

        state = (controller.recordFailure(state.id, state.stateRevision, "tool", "Timeout after 5012 ms") as KernelResult.Ok).value
        assertEquals(MissionState.RUNNING, state.state)

        state = (controller.recordFailure(state.id, state.stateRevision, "tool", "Timeout after 4998 ms") as KernelResult.Ok).value
        assertEquals(MissionState.PAUSED, state.state)
        assertEquals(ErrorCode.GUARD_TRIGGERED, state.lastError?.code)
    }
}
