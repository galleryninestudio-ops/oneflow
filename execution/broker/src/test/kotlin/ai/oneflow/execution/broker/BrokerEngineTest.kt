package ai.oneflow.execution.broker

import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.RiskTier
import ai.oneflow.core.contracts.ToolExecutionPolicy
import ai.oneflow.core.contracts.ToolManifest
import ai.oneflow.core.contracts.ToolQuery
import ai.oneflow.core.contracts.ToolRequest
import ai.oneflow.core.contracts.ToolRequestId
import ai.oneflow.core.contracts.ToolResultStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class BrokerEngineTest {

    private fun echoManifest(risk: RiskTier = RiskTier.READ_ONLY, timeoutMs: Long = 5_000L) = ToolManifest(
        id = "fixture.echo",
        version = "1.0.0",
        description = "Deterministic fixture echo tool",
        inputSchema = Json.parseToJsonElement(
            """
            {
              "type": "object",
              "properties": { "message": { "type": "string", "minLength": 1 } },
              "required": ["message"],
              "additionalProperties": false
            }
            """.trimIndent()
        ).jsonObject,
        requiredCapabilities = setOf("fixture.read"),
        risk = risk,
        execution = ToolExecutionPolicy(timeoutMs = timeoutMs),
        tags = setOf("fixture", "echo")
    )

    @Test
    fun `default deny blocks unauthorized calls and approval required gates risky tools`() = runTest {
        val registry = DefaultToolRegistry()
        val broker = SafeToolBroker(registry)
        val executions = AtomicInteger(0)

        broker.registerTool(echoManifest(risk = RiskTier.EXTERNAL_SIDE_EFFECT)) { req ->
            executions.incrementAndGet()
            req.arguments
        }

        val req = ToolRequest(
            requestId = ToolRequestId("req-1"),
            idempotencyKey = "k-1",
            missionId = MissionId("m-1"),
            toolId = "fixture.echo",
            toolVersion = "1.0.0",
            arguments = buildJsonObject { put("message", JsonPrimitive("hello")) }
        )

        val denied = broker.invoke(req, missionGrants = emptySet())
        assertEquals(ToolResultStatus.DENIED, denied.status)
        assertEquals(0, executions.get())

        val needApproval = broker.invoke(req, missionGrants = setOf("fixture.read"))
        assertEquals(ToolResultStatus.APPROVAL_REQUIRED, needApproval.status)
        assertEquals(0, executions.get())

        val ok1 = broker.invoke(
            req,
            missionGrants = setOf("fixture.read"),
            sessionApprovals = setOf("fixture.echo@1.0.0")
        )
        assertEquals(ToolResultStatus.OK, ok1.status)
        assertEquals(1, executions.get())

        // Duplicate idempotencyKey returns cached result without re-running handler
        val ok2 = broker.invoke(
            req.copy(requestId = ToolRequestId("req-2")),
            missionGrants = setOf("fixture.read"),
            sessionApprovals = setOf("fixture.echo@1.0.0")
        )
        assertEquals(ToolResultStatus.OK, ok2.status)
        assertEquals(1, executions.get())
    }

    @Test
    fun `invalid arguments rejected before policy and discover searches manifests`() = runTest {
        val registry = DefaultToolRegistry()
        val broker = SafeToolBroker(registry)
        broker.registerTool(echoManifest()) { it.arguments }

        val hits = registry.discover(ToolQuery(query = "echo fixture", limit = 5))
        assertEquals(1, hits.size)
        assertEquals("fixture.echo", hits[0].id)

        val invalid = broker.invoke(
            ToolRequest(
                requestId = ToolRequestId("req-bad"),
                idempotencyKey = "k-bad",
                missionId = MissionId("m-1"),
                toolId = "fixture.echo",
                toolVersion = "1.0.0",
                arguments = buildJsonObject { put("wrong", JsonPrimitive(123)) }
            ),
            missionGrants = setOf("fixture.read")
        )
        assertEquals(ToolResultStatus.INVALID_ARGUMENT, invalid.status)
        assertTrue(broker.receipts().isNotEmpty())
    }

    @Test
    fun `tool timeout is enforced deterministically`() = runTest {
        val registry = DefaultToolRegistry()
        val broker = SafeToolBroker(registry)
        broker.registerTool(echoManifest(timeoutMs = 50L)) {
            delay(500L)
            buildJsonObject { put("ok", JsonPrimitive(true)) }
        }

        val res = broker.invoke(
            ToolRequest(
                requestId = ToolRequestId("req-to"),
                idempotencyKey = "k-to",
                missionId = MissionId("m-1"),
                toolId = "fixture.echo",
                toolVersion = "1.0.0",
                arguments = buildJsonObject { put("message", JsonPrimitive("slow")) }
            ),
            missionGrants = setOf("fixture.read")
        )
        assertEquals(ToolResultStatus.TIMEOUT, res.status)
    }
}
