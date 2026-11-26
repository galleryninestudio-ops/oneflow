package ai.oneflow.capabilities.builder

import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.ToolRequest
import ai.oneflow.core.contracts.ToolRequestId
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.execution.broker.DefaultToolRegistry
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class Phase7BuilderTest {

    @Test
    fun `rea adapter reports unavailable cleanly without fabricating binary findings`() = runBlocking {
        val tempDir = Files.createTempDirectory("oneflow-p7-rea")
        val adapter = ReaAdapter()
        val status = adapter.probeCapabilities(tempDir, npxPath = "/nonexistent/npx")
        assertFalse(status.cliAvailable)
        assertTrue(status.missingEngines.containsAll(listOf("ghidra", "jadx", "ida")))
        assertTrue(status.note.contains("unavailable"))
    }

    @Test
    fun `flowforge static scanner blocks unsafe constructs and supports versioned rollback`() = runBlocking {
        val registry = FlowForgeRegistry()
        val unsafe = registry.register(
            adapterId = "bad.adapter",
            sourceCode = """
                val p = ProcessBuilder("sh").start()
                val url = "http://insecure.example.com/api"
            """.trimIndent()
        )
        assertTrue(unsafe is ForgeRegisterResult.Rejected)
        val findings = (unsafe as ForgeRegisterResult.Rejected).findings.map { it.ruleId }
        assertTrue(findings.contains("FORGE_NO_EXEC"))
        assertTrue(findings.contains("FORGE_NO_CLEARTEXT_HTTP"))

        val v1 = registry.register("notes.adapter", "async function run() { return { v: 1 }; }")
        val v2 = registry.register("notes.adapter", "async function run() { return { v: 2 }; }")
        assertTrue(v1 is ForgeRegisterResult.Registered)
        assertTrue(v2 is ForgeRegisterResult.Registered)
        assertEquals(2, registry.activeAdapter("notes.adapter")?.version)

        val rolled = registry.rollback("notes.adapter")
        assertEquals(1, rolled?.version)
        assertEquals(1, registry.activeAdapter("notes.adapter")?.version)
    }

    @Test
    fun `flowteach excludes sensitive password steps and compiles parameterized flowcode skill`() {
        val compiled = FlowTeachCompiler.compileToFlowCode(
            skillId = "skill.login_demo",
            name = "Safe Demo Flow",
            steps = listOf(
                RecordedUiStep("input", "n1", "Username", inputParamName = "username", isSensitive = false),
                RecordedUiStep("input", "n2", "Password", inputParamName = "password", isSensitive = true),
                RecordedUiStep("tap", "n3", "Continue", isSensitive = false)
            )
        )
        assertEquals(1, compiled.excludedSensitiveSteps)
        assertEquals(listOf("username"), compiled.parameters)
        assertFalse(compiled.flowCodeJs.contains("n2"))
        assertTrue(compiled.flowCodeJs.contains("device.tap_ref"))
        assertTrue(compiled.flowCodeJs.contains("params['username']"))
    }

    @Test
    fun `builder tools enforce static scan gate via SafeToolBroker`() = runBlocking {
        val tempDir = Files.createTempDirectory("oneflow-p7-tools")
        val broker = SafeToolBroker(DefaultToolRegistry())
        BuilderTools(ReaAdapter(), FlowForgeRegistry(), tempDir).registerAll(broker)

        val rejected = broker.invoke(
            ToolRequest(
                requestId = ToolRequestId("b1"),
                idempotencyKey = "kb1",
                missionId = MissionId("m-p7"),
                toolId = "forge.scan_and_register",
                toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("adapterId", JsonPrimitive("eval.bad"))
                    put("sourceCode", JsonPrimitive("eval('1+1')"))
                }
            ),
            missionGrants = setOf("builder.write"),
            sessionApprovals = setOf("forge.scan_and_register")
        )
        assertEquals(ToolResultStatus.DENIED, rejected.status)
        assertEquals(ErrorCode.DENIED, rejected.error?.code)

        val accepted = broker.invoke(
            ToolRequest(
                requestId = ToolRequestId("b2"),
                idempotencyKey = "kb2",
                missionId = MissionId("m-p7"),
                toolId = "forge.scan_and_register",
                toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("adapterId", JsonPrimitive("good.adapter"))
                    put("sourceCode", JsonPrimitive("async function run() { return 42; }"))
                }
            ),
            missionGrants = setOf("builder.write"),
            sessionApprovals = setOf("forge.scan_and_register")
        )
        assertEquals(ToolResultStatus.OK, accepted.status)
    }
}
