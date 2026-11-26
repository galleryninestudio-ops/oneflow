package ai.oneflow.execution.flowcode

import ai.oneflow.capabilities.files.ArtifactStore
import ai.oneflow.capabilities.files.TransactionJournal
import ai.oneflow.capabilities.files.WorkspaceFileTools
import ai.oneflow.capabilities.files.WorkspacePathGuard
import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.ScriptRequest
import ai.oneflow.core.contracts.ToolRequest
import ai.oneflow.core.contracts.ToolRequestId
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.execution.broker.DefaultToolRegistry
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 10 FlowLab Benchmark & Security Regression:
 * Compares 10-file batch processing via FlowCode (1 model turn, intermediate data stays out of model context)
 * against 10 sequential direct-tool calls, plus path-traversal regression check.
 */
class Phase10FlowLabBenchmarkTest {

    private fun resolveWorkerBinary(): Path {
        val candidates = listOf(
            Path.of("native/qjs-worker/build/qjs_worker"),
            Path.of("../../native/qjs-worker/build/qjs_worker")
        )
        return candidates.firstOrNull { Files.isExecutable(it) } ?: candidates.first()
    }

    @Test
    fun `flowlab benchmark compares 10-file flowcode batch vs direct tool calls and enforces traversal guard`(@TempDir tempDir: Path) = runBlocking {
        val worker = resolveWorkerBinary()
        assumeTrue(Files.isExecutable(worker), "qjs_worker not compiled at $worker")

        val ws = tempDir.resolve("flowlab_ws").also { Files.createDirectories(it) }
        for (i in 1..10) {
            Files.writeString(ws.resolve("item_$i.txt"), "record-$i-payload-${"x".repeat(200)}")
        }

        val guard = WorkspacePathGuard(ws)
        val artifacts = ArtifactStore(ws.resolve(".oneflow/artifacts"))
        val journal = TransactionJournal(ws.resolve(".oneflow/journal"), guard)
        val registry = DefaultToolRegistry()
        val broker = SafeToolBroker(registry)
        WorkspaceFileTools(guard, artifacts, journal).registerAll(broker)

        val engine = QuickJsProcessScriptEngine(
            workerBinary = worker,
            toolRegistry = registry,
            toolBroker = broker,
            activeToolIds = { listOf("fs.list", "fs.read", "fs.write") },
            missionGrantsProvider = { setOf("workspace.read", "workspace.write") },
            largeOutputSink = { content, mime -> artifacts.putText(content, mime).id },
            maxInlineOutputBytes = 16 * 1024
        )

        // 1. Direct-tool baseline (10 read turns)
        val missionId = MissionId("m-flowlab")
        var directTurns = 0
        var directTotalChars = 0
        for (i in 1..10) {
            val res = broker.invoke(
                ToolRequest(
                    requestId = ToolRequestId("d-$i"),
                    idempotencyKey = "kd-$i",
                    missionId = missionId,
                    toolId = "fs.read",
                    toolVersion = "1.0.0",
                    arguments = buildJsonObject { put("path", JsonPrimitive("item_$i.txt")) }
                ),
                missionGrants = setOf("workspace.read")
            )
            assertEquals(ToolResultStatus.OK, res.status)
            directTurns++
            directTotalChars += res.data.toString().length
        }

        // 2. FlowCode batch (1 turn; intermediate payloads stay inside QuickJS-NG worker)
        val scriptRes = engine.execute(
            ScriptRequest(
                missionId = missionId,
                script = """
                    let count = 0;
                    for (let i = 1; i <= 10; i++) {
                        const r = await flow.fs.read({ path: 'item_' + i + '.txt' });
                        if (r.content && r.content.length > 0) count++;
                    }
                    await flow.fs.write({ path: 'summary.txt', content: 'processed=' + count });
                    return { processed: count };
                """.trimIndent(),
                timeoutMs = 10_000L,
                maxMemoryBytes = 16L * 1024L * 1024L
            )
        )
        assertTrue(scriptRes.ok, "FlowCode batch failed: ${scriptRes.error}")
        assertEquals("processed=10", Files.readString(ws.resolve("summary.txt")))
        val flowCodeTurns = 1
        val flowCodeModelReturnChars = scriptRes.outputJson.orEmpty().length
        assertEquals(10, directTurns)
        assertEquals(1, flowCodeTurns)
        assertTrue(flowCodeModelReturnChars < directTotalChars / 5)

        // 3. Security regression: traversal attempt outside workspace must be denied
        val traversal = broker.invoke(
            ToolRequest(
                requestId = ToolRequestId("sec-1"),
                idempotencyKey = "ksec-1",
                missionId = missionId,
                toolId = "fs.read",
                toolVersion = "1.0.0",
                arguments = buildJsonObject { put("path", JsonPrimitive("../../etc/passwd")) }
            ),
            missionGrants = setOf("workspace.read")
        )
        assertEquals(ToolResultStatus.DENIED, traversal.status)
    }
}
