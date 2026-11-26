package ai.oneflow.app.data

import ai.oneflow.core.contracts.AcceptanceCheckResult
import ai.oneflow.core.contracts.MissionState
import ai.oneflow.core.contracts.ModelId
import ai.oneflow.core.contracts.ProviderConfig
import ai.oneflow.core.contracts.ProviderId
import ai.oneflow.core.contracts.VerificationRequest
import ai.oneflow.core.contracts.VerificationResult
import ai.oneflow.core.contracts.VerificationStatus
import ai.oneflow.core.contracts.Verifier
import ai.oneflow.core.contracts.WorkspaceId
import ai.oneflow.core.kernel.InMemoryMissionStore
import ai.oneflow.core.kernel.KernelResult
import ai.oneflow.core.kernel.MissionController
import ai.oneflow.core.kernel.MissionRunner
import ai.oneflow.core.models.OpenAiCompatibleProvider
import ai.oneflow.execution.broker.DefaultToolRegistry
import ai.oneflow.execution.broker.Phase1FixtureTools
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalFixtureServerTest {

    @Test
    fun `loopback fixture server completes full streamed tool loop and approval flow`() = runBlocking {
        val server = LocalFixtureServer()
        val url = server.start()
        try {
            val provider = OpenAiCompatibleProvider(
                config = ProviderConfig(ProviderId("fix"), "Fixture", url, "k"),
                apiKeyProvider = { "fixture-key" }
            )
            val models = provider.listModels().getOrThrow()
            assertEquals(listOf("fixture-sse-v1"), models.models.map { it.value })

            val store = InMemoryMissionStore()
            val controller = MissionController(store)
            val registry = DefaultToolRegistry()
            val broker = SafeToolBroker(registry)
            val tools = Phase1FixtureTools.registerAll(broker)
            val verifier = object : Verifier {
                override suspend fun evaluate(request: VerificationRequest): VerificationResult =
                    VerificationResult(
                        status = VerificationStatus.PASS,
                        checks = listOf(AcceptanceCheckResult("non_empty", !request.candidateResponseText.isNullOrBlank(), null, "ok")),
                        summary = "ok"
                    )
            }
            val runner = MissionRunner(store, controller, provider, registry, broker, verifier)

            val m = controller.createMission(WorkspaceId("w1"), "Test math", allowedCapabilities = setOf("fixture.read"))
            val u = (runner.appendUserMessage(m.id, m.stateRevision, "compute") as KernelResult.Ok).value
            val done = (runner.runMission(u.id, ModelId("fixture-sse-v1"), tools) as KernelResult.Ok).value
            assertEquals(MissionState.COMPLETED, done.state)
            assertTrue(done.conversation.last().message.content?.contains("42.0") == true)
        } finally {
            server.stop()
        }
    }
}
