package ai.oneflow.core.models

import ai.oneflow.core.contracts.ChatMessage
import ai.oneflow.core.contracts.ChatRole
import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.ModelEvent
import ai.oneflow.core.contracts.ModelId
import ai.oneflow.core.contracts.ModelRequest
import ai.oneflow.core.contracts.ProviderConfig
import ai.oneflow.core.contracts.ProviderId
import ai.oneflow.core.contracts.UsageAccuracy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiProviderTest {

    @Test
    fun `sse parser handles fragmented utf8 text, multiple tool calls, and reported usage`() = runTest {
        val sse = """
            : keep-alive

            data: {"choices":[{"index":0,"delta":{"content":"Hello "}}]}

            data: {"choices":[{"index":0,"delta":{"content":"🌍!"}}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"disc","arguments":"{\"qu"}}]}}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"over","arguments":"ery\":\"fs\"}"}},{"index":1,"id":"call_2","type":"function","function":{"name":"execute","arguments":"{\"code\":\"return 1;\"}"}}]}}]}

            data: {"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}],"usage":{"prompt_tokens":42,"completion_tokens":18,"total_tokens":60}}

            data: [DONE]

        """.trimIndent() + "\n"

        val parser = OpenAiSseParser()
        val events = parser.parse(Buffer().writeUtf8(sse)).toList()

        val completed = events.filterIsInstance<ModelEvent.Completed>().single()
        assertEquals("Hello 🌍!", completed.fullText)
        assertEquals("tool_calls", completed.finishReason)
        assertEquals(2, completed.toolCalls.size)
        assertEquals("call_1", completed.toolCalls[0].id)
        assertEquals("discover", completed.toolCalls[0].name)
        assertEquals("""{"query":"fs"}""", completed.toolCalls[0].argumentsJson)
        assertEquals("call_2", completed.toolCalls[1].id)
        assertEquals("execute", completed.toolCalls[1].name)
        assertEquals(60L, completed.usage?.totalTokens)
        assertEquals(UsageAccuracy.PROVIDER_REPORTED, completed.usage?.accuracy)
    }

    @Test
    fun `sse parser rejects duplicate tool call id across distinct indices and malformed json`() = runTest {
        val dupIdSse = """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"dup_1","function":{"name":"a","arguments":"{}"}}]}}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"dup_1","function":{"name":"b","arguments":"{}"}}]}}]}

            data: [DONE]

        """.trimIndent() + "\n"

        val eventsDup = OpenAiSseParser().parse(Buffer().writeUtf8(dupIdSse)).toList()
        val failedDup = eventsDup.filterIsInstance<ModelEvent.Failed>().single()
        assertEquals(ErrorCode.PROVIDER_PROTOCOL_ERROR, failedDup.error.code)

        val badJsonSse = """
            data: {"choices":[{"index":0,"delta":
            
        """.trimIndent() + "\n"
        val eventsBad = OpenAiSseParser().parse(Buffer().writeUtf8(badJsonSse)).toList()
        val failedBad = eventsBad.filterIsInstance<ModelEvent.Failed>().single()
        assertEquals(ErrorCode.PROVIDER_PROTOCOL_ERROR, failedBad.error.code)
    }

    @Test
    fun `provider lists models, streams response without usage, and redacts key on auth error`() = runTest {
        val secretKey = "sk-test-super-secret-value-123456"
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "application/json"),
                    body = """{"data":[{"id":"gpt-fixture-1"},{"id":"gpt-fixture-2"}]}"""
                )
            )
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/event-stream"),
                    body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n"
                )
            )
            server.enqueue(
                MockResponse(
                    code = 401,
                    headers = headersOf("Content-Type", "application/json"),
                    body = """{"error":{"message":"Invalid key $secretKey"}}"""
                )
            )

            val provider = OpenAiCompatibleProvider(
                config = ProviderConfig(
                    id = ProviderId("fixture"),
                    name = "Mock Fixture",
                    baseUrl = server.url("/v1").toString(),
                    keyAlias = "alias-fixture"
                ),
                apiKeyProvider = { secretKey }
            )

            val catalog = provider.listModels().getOrThrow()
            assertEquals(listOf("gpt-fixture-1", "gpt-fixture-2"), catalog.models.map { it.value })

            val events = provider.generate(
                ModelRequest(
                    model = ModelId("gpt-fixture-1"),
                    messages = listOf(ChatMessage(ChatRole.USER, "ping"))
                )
            ).toList()
            val completed = events.filterIsInstance<ModelEvent.Completed>().single()
            assertEquals("OK", completed.fullText)
            assertNull(completed.usage)

            val authFailEvents = provider.generate(
                ModelRequest(
                    model = ModelId("gpt-fixture-1"),
                    messages = listOf(ChatMessage(ChatRole.USER, "ping"))
                )
            ).toList()
            val err = authFailEvents.filterIsInstance<ModelEvent.Failed>().single().error
            assertEquals(ErrorCode.PROVIDER_AUTH, err.code)
            assertFalse(err.message.contains(secretKey))
            assertTrue(err.message.contains("[REDACTED]"))
        }
    }
}
