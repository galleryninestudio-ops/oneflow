package ai.oneflow.core.intelligence

import ai.oneflow.core.contracts.MissionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase8TeamsWorkflowsTest {

    @Test
    fun `agent team subagent cannot escalate parent capabilities or budget and respects resource locks`() {
        val coord = AgentTeamCoordinator()
        val grant = coord.spawnSubagentGrant(
            subagentId = "sub-1",
            parentMissionId = MissionId("m-parent"),
            parentCapabilities = setOf("fs.read", "web.read"),
            requestedCapabilities = setOf("fs.read", "device.actuate", "process.exec"),
            parentBudget = SubagentBudget(maxSteps = 8, maxTokens = 4000),
            requestedBudget = SubagentBudget(maxSteps = 50, maxTokens = 99999)
        )
        assertEquals(setOf("fs.read"), grant.effectiveCapabilities)
        assertEquals(setOf("device.actuate", "process.exec"), grant.rejectedEscalations)
        assertEquals(8, grant.effectiveBudget.maxSteps)
        assertEquals(4000, grant.effectiveBudget.maxTokens)

        assertTrue(coord.tryAcquireLock("workspace/src/Main.kt", "sub-1"))
        assertFalse(coord.tryAcquireLock("workspace/src/Main.kt", "sub-2"))
        assertFalse(coord.releaseLock("workspace/src/Main.kt", "sub-2"))
        assertTrue(coord.releaseLock("workspace/src/Main.kt", "sub-1"))
        assertTrue(coord.tryAcquireLock("workspace/src/Main.kt", "sub-2"))
    }

    @Test
    fun `flow trigger registry enforces dedupe window and pause state`() {
        val reg = FlowTriggerRegistry()
        reg.register(TriggerSpec("t1", "Daily Check", intervalMinutes = 60, dedupeWindowMs = 10_000L))

        assertTrue(reg.shouldFire("t1", "key-a", nowMs = 1000L))
        assertFalse(reg.shouldFire("t1", "key-a", nowMs = 5000L))
        assertTrue(reg.shouldFire("t1", "key-a", nowMs = 12_000L))

        assertTrue(reg.setPaused("t1", true))
        assertFalse(reg.shouldFire("t1", "key-b", nowMs = 20_000L))
        assertEquals(2, reg.getHistory().size)
    }

    @Test
    fun `flowlearn requires passing eval gate and explicit user approval`() {
        val engine = FlowLearnEngine()
        val weak = engine.submitProposal("p-weak", "Weak", "steps", baselineScore = 0.80, candidateScore = 0.65)
        assertFalse(weak.evalPassed)
        assertTrue(engine.approveAndPromote("p-weak", userApproved = true) is FlowLearnPromoteResult.Rejected)

        val strong = engine.submitProposal("p-good", "Good", "steps", baselineScore = 0.75, candidateScore = 0.90)
        assertTrue(strong.evalPassed)
        assertTrue(engine.approveAndPromote("p-good", userApproved = false) is FlowLearnPromoteResult.Rejected)
        val activated = engine.approveAndPromote("p-good", userApproved = true)
        assertTrue(activated is FlowLearnPromoteResult.Activated)
        assertTrue((activated as FlowLearnPromoteResult.Activated).proposal.active)
    }

    @Test
    fun `remote interop endpoints are disabled by default and require pairing plus token`() {
        val defaults = RemoteInteropSettings()
        assertFalse(defaults.flowConnect.enabled)
        assertFalse(defaults.mcpServer.enabled)
        assertFalse(defaults.flowConnect.isReadyForConnection("https://peer", "abc"))

        val paired = RemoteEndpointPolicy(
            serviceName = "FlowConnect",
            enabled = true,
            pairedPeerId = "peer-1",
            authTokenSha256 = "hash-123",
            allowedOrigins = setOf("https://trusted.local")
        )
        assertFalse(paired.isReadyForConnection("https://evil.invalid", "hash-123"))
        assertFalse(paired.isReadyForConnection("https://trusted.local", "wrong"))
        assertTrue(paired.isReadyForConnection("https://trusted.local", "hash-123"))
    }
}
