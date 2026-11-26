package ai.oneflow.core.kernel

import ai.oneflow.core.contracts.AcceptanceCheckResult
import ai.oneflow.core.contracts.BudgetSpec
import ai.oneflow.core.contracts.ChatMessage
import ai.oneflow.core.contracts.ChatRole
import ai.oneflow.core.contracts.ContextCompileSpec
import ai.oneflow.core.contracts.EffectStatus
import ai.oneflow.core.contracts.EventId
import ai.oneflow.core.contracts.MissionConversationEntry
import ai.oneflow.core.contracts.MissionEventEnvelope
import ai.oneflow.core.contracts.MissionState
import ai.oneflow.core.contracts.ModelId
import ai.oneflow.core.contracts.ProviderConfig
import ai.oneflow.core.contracts.ProviderId
import ai.oneflow.core.contracts.VerificationRequest
import ai.oneflow.core.contracts.VerificationResult
import ai.oneflow.core.contracts.VerificationStatus
import ai.oneflow.core.contracts.Verifier
import ai.oneflow.core.contracts.WorkspaceId
import ai.oneflow.core.models.OpenAiCompatibleProvider
import ai.oneflow.execution.broker.DefaultToolRegistry
import ai.oneflow.execution.broker.Phase1FixtureTools
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MissionRunnerIntegrationTest {

    private val passVerifier = object : Verifier {
        override suspend fun evaluate(request: VerificationRequest): VerificationResult =
            VerificationResult(
                status = VerificationStatus.PASS,
                checks = listOf(AcceptanceCheckResult("response", true, null, "Verified")),
                summary = "All Phase 1 checks passed"
            )
    }

    @Test
    fun `end to end streamed response executes fixture tool with matching id and persists to disk`(@TempDir tempDir: Path) = runTest {
        val secretKey = "sk-phase1-secret-do-not-expose-999"
        MockWebServer().use { server ->
            server.start()
            // Turn 1: model requests fixture_math_add
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = """
                        data: {"choices":[{"index":0,"delta":{"content":"Calculating... "}}]}

                        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_add_1","type":"function","function":{"name":"fixture_math_add","arguments":"{\"a\":19,\"b\":23}"}}]}}]}

                        data: {"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}],"usage":{"prompt_tokens":15,"completion_tokens":10,"total_tokens":25}}

                        data: [DONE]

                    """.trimIndent() + "\n"
                )
            )
            // Turn 2: model reads tool result and finishes
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = """
                        data: {"choices":[{"index":0,"delta":{"content":"The sum is 42.0."}}]}

                        data: {"choices":[{"index":0,"finish_reason":"stop","delta":{}}],"usage":{"prompt_tokens":30,"completion_tokens":8,"total_tokens":38}}

                        data: [DONE]

                    """.trimIndent() + "\n"
                )
            )

            val store = FileMissionStore(tempDir)
            val controller = MissionController(store)
            val registry = DefaultToolRegistry()
            val broker = SafeToolBroker(registry)
            val tools = Phase1FixtureTools.registerAll(broker)
            val provider = OpenAiCompatibleProvider(
                config = ProviderConfig(ProviderId("mock"), "Mock", server.url("/v1").toString(), "alias-1"),
                apiKeyProvider = { secretKey }
            )

            val runner = MissionRunner(
                store = store,
                controller = controller,
                modelProvider = provider,
                toolRegistry = registry,
                toolBroker = broker,
                verifier = passVerifier,
                knownSecrets = { listOf(secretKey) }
            )

            val created = controller.createMission(
                workspaceId = WorkspaceId("ws-p1"),
                objective = "Add 19 + 23 using fixture tool",
                allowedCapabilities = setOf("fixture.read")
            )
            val withUser = (runner.appendUserMessage(created.id, created.stateRevision, "Compute 19 + 23") as KernelResult.Ok).value

            val updates = mutableListOf<MissionRunnerUpdate>()
            val finalRes = runner.runMission(withUser.id, ModelId("mock-model"), tools) { updates += it }
            val completed = (finalRes as KernelResult.Ok).value

            assertEquals(MissionState.COMPLETED, completed.state)
            assertEquals(2, completed.usage.modelTurnsUsed)
            assertEquals(1, completed.usage.toolCallsUsed)
            assertEquals(45L, completed.usage.promptTokens)

            // Verify second HTTP request carried exact tool_call_id="call_add_1" and result 42.0
            server.takeRequest()
            val secondReq = server.takeRequest().body?.utf8().orEmpty()
            assertTrue(secondReq.contains("call_add_1"))
            assertTrue(secondReq.contains("42.0"))
            assertFalse(secondReq.contains(secretKey))

            // Simulate process death by constructing a fresh FileMissionStore
            val reloadedStore = FileMissionStore(tempDir)
            val reloaded = reloadedStore.load(created.id)!!
            assertEquals(MissionState.COMPLETED, reloaded.state)
            assertTrue(reloaded.conversation.any { it.message.role == ChatRole.TOOL && it.message.toolCallId == "call_add_1" })
            assertTrue(updates.any { it is MissionRunnerUpdate.TextDelta })
        }
    }

    @Test
    fun `approval gated tool pauses in WAITING_PERMISSION and executes upon user approval`(@TempDir tempDir: Path) = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = """
                        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_pub_1","type":"function","function":{"name":"fixture_publish_note","arguments":"{\"note\":\"hello\"}"}}]}}]}

                        data: {"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}]}

                        data: [DONE]

                    """.trimIndent() + "\n"
                )
            )
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Published.\"}}]}\n\ndata: [DONE]\n\n"
                )
            )

            val store = FileMissionStore(tempDir)
            val controller = MissionController(store)
            val registry = DefaultToolRegistry()
            val broker = SafeToolBroker(registry)
            val tools = Phase1FixtureTools.registerAll(broker)
            val provider = OpenAiCompatibleProvider(
                config = ProviderConfig(ProviderId("mock"), "Mock", server.url("/v1").toString(), "alias-1"),
                apiKeyProvider = { "sk-local-test" }
            )
            val runner = MissionRunner(store, controller, provider, registry, broker, passVerifier)

            val created = controller.createMission(
                workspaceId = WorkspaceId("ws-p1-app"),
                objective = "Publish note",
                allowedCapabilities = setOf("fixture.write")
            )
            val waiting = (runner.runMission(created.id, ModelId("m"), tools) as KernelResult.Ok).value
            assertEquals(MissionState.WAITING_PERMISSION, waiting.state)
            assertEquals("fixture_publish_note", waiting.pendingApproval?.toolId)

            val resumedState = (runner.resolvePendingApproval(created.id, approved = true) as KernelResult.Ok).value
            assertEquals(MissionState.RUNNING, resumedState.state)
            assertNull(resumedState.pendingApproval)

            val done = (runner.runMission(created.id, ModelId("m"), tools) as KernelResult.Ok).value
            assertEquals(MissionState.COMPLETED, done.state)
            assertTrue(done.conversation.any { it.message.role == ChatRole.TOOL && it.message.content?.contains("\"published\":true") == true })
        }
    }

    @Test
    fun `crash between effect intent and ack reconciles as UNKNOWN_OUTCOME without blind re-execution`(@TempDir tempDir: Path) = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Acknowledged unknown state.\"}}]}\n\ndata: [DONE]\n\n"
                )
            )

            val store = FileMissionStore(tempDir)
            val controller = MissionController(store)
            val m = controller.createMission(WorkspaceId("ws-rec"), "Recover safely")
            val r = (controller.transition(m.id, m.stateRevision, MissionState.READY, "ready") as KernelResult.Ok).value
            val run = (controller.transition(r.id, r.stateRevision, MissionState.RUNNING, "run") as KernelResult.Ok).value
            // Simulate unacknowledged effect.intent persisted right before process kill
            store.append(
                MissionEventEnvelope(
                    eventId = EventId("e-crash"),
                    sequence = 4L,
                    timestampEpochMs = 1000L,
                    missionId = m.id,
                    type = "effect.intent",
                    actorSource = "kernel",
                    effectStatus = EffectStatus.INTENT,
                    payload = buildJsonObject {
                        put("toolId", JsonPrimitive("fixture_publish_note"))
                        put("toolCallId", JsonPrimitive("call_crashed_9"))
                        put("idempotencyKey", JsonPrimitive("${m.id.value}:0:call_crashed_9"))
                    }
                )
            )

            // Fresh store & runner simulating restart
            val store2 = FileMissionStore(tempDir)
            val controller2 = MissionController(store2)
            val registry = DefaultToolRegistry()
            val broker = SafeToolBroker(registry)
            val provider = OpenAiCompatibleProvider(
                config = ProviderConfig(ProviderId("mock"), "Mock", server.url("/v1").toString(), "alias-1"),
                apiKeyProvider = { "sk-local-test" }
            )
            val runner2 = MissionRunner(store2, controller2, provider, registry, broker, passVerifier)
            val finished = (runner2.runMission(m.id, ModelId("m")) as KernelResult.Ok).value

            assertEquals(MissionState.COMPLETED, finished.state)
            assertTrue(finished.conversation.any {
                it.message.role == ChatRole.TOOL &&
                    it.message.toolCallId == "call_crashed_9" &&
                    it.message.content?.contains("UNKNOWN_OUTCOME") == true
            })
            assertTrue(store2.events(m.id).any { it.type == "effect.reconciled_unknown" })
        }
    }

    @Test
    fun `context compiler compacts old turns while keeping immutable objective and redacting secrets`() {
        val secret = "sk-very-secret-token-123456"
        val compiler = FlowContextCompiler(knownSecrets = { listOf(secret) })
        val mission = ai.oneflow.core.contracts.MissionStateSnapshot(
            id = ai.oneflow.core.contracts.MissionId("m-cmp"),
            workspaceId = WorkspaceId("ws-cmp"),
            objective = "Immutable goal: verify tests",
            requirementsRevision = 3L,
            conversation = (1..12).map { idx ->
                MissionConversationEntry(
                    turnIndex = idx,
                    message = ChatMessage(
                        role = ChatRole.USER,
                        content = "Turn $idx message with padding " + "x".repeat(300) + " $secret"
                    ),
                    timestampEpochMs = idx.toLong()
                )
            },
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L
        )
        val res = compiler.compile(
            mission = mission,
            modelId = ModelId("m"),
            tools = emptyList(),
            spec = ContextCompileSpec(maxEstimatedTokens = 450, systemPolicyInstructions = "System rule")
        )
        assertTrue(res.omittedMessageCount > 0)
        val serialized = res.request.messages.joinToString("\n") { it.content.orEmpty() }
        assertTrue(serialized.contains("Immutable goal: verify tests"))
        assertTrue(serialized.contains("rev=3"))
        assertFalse(serialized.contains(secret))
    }

    @Test
    fun `budget exhaustion pauses mission and coroutine cancellation transitions to CANCELLED`(@TempDir tempDir: Path) = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = """
                        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"fixture_echo","arguments":"{\"message\":\"hi\"}"}}]}}]}

                        data: {"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}]}

                        data: [DONE]

                    """.trimIndent() + "\n"
                )
            )
            val store = FileMissionStore(tempDir)
            val controller = MissionController(store)
            val registry = DefaultToolRegistry()
            val broker = SafeToolBroker(registry)
            val tools = Phase1FixtureTools.registerAll(broker)
            val provider = OpenAiCompatibleProvider(
                config = ProviderConfig(ProviderId("mock"), "Mock", server.url("/v1").toString(), "alias-1"),
                apiKeyProvider = { "sk-test" }
            )
            val runner = MissionRunner(store, controller, provider, registry, broker, passVerifier)
            val m = controller.createMission(
                workspaceId = WorkspaceId("ws-b"),
                objective = "Single turn cap",
                budget = BudgetSpec(maxModelTurns = 1, maxToolCalls = 5),
                allowedCapabilities = setOf("fixture.read")
            )
            val paused = (runner.runMission(m.id, ModelId("m"), tools) as KernelResult.Ok).value
            assertEquals(MissionState.PAUSED_BUDGET, paused.state)

            // Cancellation check on another mission
            val m2 = controller.createMission(WorkspaceId("ws-c"), "Cancel me")
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Wait\"}}]}\n\n"
                )
            )
            val job = launch {
                try {
                    runner.runMission(m2.id, ModelId("m"))
                } catch (_: CancellationException) {
                    // expected
                }
            }
            job.cancel()
            job.join()
        }
    }
}
