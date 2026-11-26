package ai.oneflow.execution.flowcode

import ai.oneflow.capabilities.files.ArtifactStore
import ai.oneflow.capabilities.files.TransactionJournal
import ai.oneflow.capabilities.files.WorkspaceFileTools
import ai.oneflow.capabilities.files.WorkspacePathGuard
import ai.oneflow.core.contracts.AcceptanceCheckResult
import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.MissionState
import ai.oneflow.core.contracts.ModelId
import ai.oneflow.core.contracts.ProviderConfig
import ai.oneflow.core.contracts.ProviderId
import ai.oneflow.core.contracts.ScriptRequest
import ai.oneflow.core.contracts.VerificationRequest
import ai.oneflow.core.contracts.VerificationResult
import ai.oneflow.core.contracts.VerificationStatus
import ai.oneflow.core.contracts.Verifier
import ai.oneflow.core.contracts.WorkspaceId
import ai.oneflow.core.kernel.FileMissionStore
import ai.oneflow.core.kernel.KernelResult
import ai.oneflow.core.kernel.MissionController
import ai.oneflow.core.kernel.MissionRunner
import ai.oneflow.core.models.OpenAiCompatibleProvider
import ai.oneflow.execution.broker.DefaultToolRegistry
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FlowCodeEndToEndTest {

    private fun resolveWorkerBinary(): Path {
        val candidates = listOf(
            Path.of("native/qjs-worker/build/qjs_worker"),
            Path.of("../../native/qjs-worker/build/qjs_worker")
        )
        return candidates.firstOrNull { Files.isExecutable(it) } ?: candidates.first()
    }

    private fun setupHarness(
        workspace: Path,
        grantsProvider: (MissionId) -> Set<String>,
        maxInlineBytes: Int = 16 * 1024
    ): Triple<DefaultToolRegistry, SafeToolBroker, QuickJsProcessScriptEngine> {
        val guard = WorkspacePathGuard(workspace)
        val artifacts = ArtifactStore(workspace.resolve(".oneflow/artifacts"))
        val journal = TransactionJournal(workspace.resolve(".oneflow/journal"), guard)
        val registry = DefaultToolRegistry()
        val broker = SafeToolBroker(registry)
        WorkspaceFileTools(guard, artifacts, journal).registerAll(broker)

        val engine = QuickJsProcessScriptEngine(
            workerBinary = resolveWorkerBinary(),
            toolRegistry = registry,
            toolBroker = broker,
            activeToolIds = { listOf("fs.list", "fs.glob", "fs.read", "fs.write", "fs.patch", "fs.grep", "verify.file", "artifact.create", "artifact.read") },
            missionGrantsProvider = grantsProvider,
            largeOutputSink = { content, mime -> artifacts.putText(content, mime).id },
            maxInlineOutputBytes = maxInlineBytes
        )
        return Triple(registry, broker, engine)
    }

    @Test
    fun `responsive website gate succeeds via discover and batched execute, and fails safely when permission denied`(@TempDir tempDir: Path) = runTest {
        val worker = resolveWorkerBinary()
        assumeTrue(Files.isExecutable(worker), "qjs_worker not compiled at $worker")

        val wsAllowed = tempDir.resolve("ws_allowed").also { Files.createDirectories(it) }
        val storeDir = tempDir.resolve("missions").also { Files.createDirectories(it) }

        val (_, broker, engine) = setupHarness(wsAllowed, { setOf("workspace.read", "workspace.write") })
        val registry = DefaultToolRegistry()
        val guard = WorkspacePathGuard(wsAllowed)
        val artifacts = ArtifactStore(wsAllowed.resolve(".oneflow/artifacts"))
        val journal = TransactionJournal(wsAllowed.resolve(".oneflow/journal"), guard)
        val realBroker = SafeToolBroker(registry)
        WorkspaceFileTools(guard, artifacts, journal).registerAll(realBroker)
        val realEngine = QuickJsProcessScriptEngine(
            workerBinary = worker,
            toolRegistry = registry,
            toolBroker = realBroker,
            activeToolIds = { listOf("fs.list", "fs.glob", "fs.read", "fs.write", "fs.patch", "fs.grep", "verify.file") },
            missionGrantsProvider = { setOf("workspace.read", "workspace.write") },
            largeOutputSink = { content, mime -> artifacts.putText(content, mime).id }
        )
        val metaTools = FlowCodeTools.registerMetaTools(realBroker, registry, realEngine)

        val jsBatchCode = """
            const html = '<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1.0"><link rel="stylesheet" href="styles.css"></head><body><main class="grid"><h1>Oneflow</h1></main></body></html>';
            const css = '.grid { display: grid; padding: 16px; } @media (max-width: 600px) { .grid { padding: 8px; } }';
            const w1 = await flow.fs.write({ path: 'index.html', content: html });
            const w2 = await flow.fs.write({ path: 'styles.css', content: css });
            const [v1, v2] = await Promise.all([
              flow.verify.file({ path: 'index.html', must_contain: ['viewport', 'styles.css'] }),
              flow.verify.file({ path: 'styles.css', must_contain: ['@media'] })
            ]);
            return { w1, w2, v1, v2 };
        """.trimIndent()

        val execArgsInner = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject { put("code", kotlinx.serialization.json.JsonPrimitive(jsBatchCode)) }
        )
        val execArgsJson = Json.encodeToString(
            kotlinx.serialization.json.JsonPrimitive.serializer(),
            kotlinx.serialization.json.JsonPrimitive(execArgsInner)
        )

        MockWebServer().use { server ->
            server.start()
            // Turn 1: discover
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = """
                        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c_disc","type":"function","function":{"name":"discover","arguments":"{\"query\":\"fs verify\",\"limit\":10}"}}]}}]}

                        data: {"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}]}

                        data: [DONE]

                    """.trimIndent() + "\n"
                )
            )
            // Turn 2: batched FlowCode execute
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = """
                        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c_exec","type":"function","function":{"name":"execute","arguments":$execArgsJson}}]}}]}

                        data: {"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}]}

                        data: [DONE]

                    """.trimIndent() + "\n"
                )
            )
            // Turn 3: final summary
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Created and verified index.html and styles.css.\"}}]}\n\ndata: [DONE]\n\n"
                )
            )

            val store = FileMissionStore(storeDir)
            val controller = MissionController(store)
            val provider = OpenAiCompatibleProvider(
                config = ProviderConfig(ProviderId("mock"), "Mock", server.url("/v1").toString(), "k"),
                apiKeyProvider = { "sk-test" }
            )
            val verifier = object : Verifier {
                override suspend fun evaluate(request: VerificationRequest): VerificationResult {
                    val idx = wsAllowed.resolve("index.html")
                    val css = wsAllowed.resolve("styles.css")
                    val ok = Files.exists(idx) && Files.exists(css) &&
                        Files.readString(idx).contains("viewport") &&
                        Files.readString(css).contains("@media")
                    return VerificationResult(
                        status = if (ok) VerificationStatus.PASS else VerificationStatus.NEEDS_WORK,
                        checks = listOf(AcceptanceCheckResult("website_files", ok, null, "index.html & styles.css verified")),
                        summary = if (ok) "Responsive website verified" else "Missing responsive files"
                    )
                }
            }

            val runner = MissionRunner(store, controller, provider, registry, realBroker, verifier)
            val m = controller.createMission(
                workspaceId = WorkspaceId("ws-web"),
                objective = "Create a responsive website in this workspace and show me a verified preview.",
                allowedCapabilities = setOf("workspace.read", "workspace.write")
            )
            val u = (runner.appendUserMessage(m.id, m.stateRevision, m.objective) as KernelResult.Ok).value
            val completed = (runner.runMission(u.id, ModelId("mock"), metaTools) as KernelResult.Ok).value

            assertEquals(MissionState.COMPLETED, completed.state)
            // Batched FlowCode completed in 3 model turns (discover + execute + final), vs 5+ direct turns
            assertEquals(3, completed.usage.modelTurnsUsed)
            assertTrue(Files.exists(wsAllowed.resolve("index.html")))
            assertTrue(Files.exists(wsAllowed.resolve("styles.css")))
            assertTrue(realBroker.receipts().isNotEmpty())
        }

        // Now re-run execute with DENIED workspace.write permission -> zero mutations
        val wsDenied = tempDir.resolve("ws_denied").also { Files.createDirectories(it) }
        val (_, _, deniedEngine) = setupHarness(wsDenied, { emptySet() })
        val deniedRes = deniedEngine.execute(
            ScriptRequest(
                missionId = MissionId("m-denied"),
                script = """await flow.fs.write({ path: "index.html", content: "hacked" });"""
            )
        )
        assertFalse(deniedRes.ok)
        assertFalse(Files.exists(wsDenied.resolve("index.html")))
    }

    @Test
    fun `sandbox blocks ambient globals, path traversal, infinite loops, memory abuse, and caps huge output`(@TempDir tempDir: Path) = runTest {
        val worker = resolveWorkerBinary()
        assumeTrue(Files.isExecutable(worker), "qjs_worker not compiled at $worker")

        val ws = tempDir.resolve("ws_sec").also { Files.createDirectories(it) }
        val (_, _, engine) = setupHarness(ws, { setOf("workspace.read", "workspace.write") }, maxInlineBytes = 512)

        // 1. No ambient Node/QuickJS std/os/network
        val ambient = engine.execute(
            ScriptRequest(
                MissionId("m-sec"),
                """return { req: typeof require, std: typeof std, os: typeof os, fetch: typeof fetch };"""
            )
        )
        assertTrue(ambient.ok)
        assertTrue(ambient.outputJson!!.contains("\"req\":\"undefined\""))
        assertTrue(ambient.outputJson!!.contains("\"fetch\":\"undefined\""))

        // 2. Path traversal blocked by host broker
        val traversal = engine.execute(
            ScriptRequest(
                MissionId("m-sec"),
                """await flow.fs.read({ path: "../../etc/passwd" });"""
            )
        )
        assertFalse(traversal.ok)
        assertTrue(traversal.error!!.message.contains("DENIED"))

        // 3. Infinite loop interrupted cleanly
        val loop = engine.execute(
            ScriptRequest(
                MissionId("m-sec"),
                """try { while (true) {} } catch (e) { return "escaped"; }""",
                timeoutMs = 80L
            )
        )
        assertFalse(loop.ok)
        assertEquals(ErrorCode.TIMEOUT, loop.error!!.code)

        // 4. Memory limit enforced
        val oom = engine.execute(
            ScriptRequest(
                MissionId("m-sec"),
                """const arr = new Array(2000000).fill(7); return arr.length;""",
                maxMemoryBytes = 2L * 1024L * 1024L
            )
        )
        assertFalse(oom.ok)

        // 5. Large output saved to ArtifactStore and truncated inline
        val big = engine.execute(
            ScriptRequest(
                MissionId("m-sec"),
                """return "A".repeat(4000);"""
            )
        )
        assertTrue(big.ok)
        val obj = Json.parseToJsonElement(big.outputJson!!).jsonObject
        assertTrue(obj["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(obj["byteSize"]!!.jsonPrimitive.int >= 4000)
        assertTrue(obj["artifactId"]!!.jsonPrimitive.content.startsWith("sha256-"))

        // 6. Worker death / missing worker binary containment does not crash host
        val deadEngine = QuickJsProcessScriptEngine(
            workerBinary = tempDir.resolve("nonexistent_worker"),
            toolRegistry = DefaultToolRegistry(),
            toolBroker = SafeToolBroker(DefaultToolRegistry()),
            activeToolIds = { emptyList() },
            missionGrantsProvider = { emptySet() }
        )
        val deadRes = deadEngine.execute(ScriptRequest(MissionId("m-dead"), "return 1;"))
        assertFalse(deadRes.ok)
        assertEquals(ErrorCode.UNAVAILABLE, deadRes.error?.code)
    }
}
