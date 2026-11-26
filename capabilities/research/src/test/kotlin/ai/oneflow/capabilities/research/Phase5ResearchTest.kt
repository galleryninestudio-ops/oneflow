package ai.oneflow.capabilities.research

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
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase5ResearchTest {

    @Test
    fun `ssrf validator blocks private, loopback, metadata, file scheme, and 302 redirect to metadata`() {
        val strict = SsrfPolicyValidator()
        assertTrue(strict.validate("http://127.0.0.1:8080/secret") is UrlPolicyCheck.Blocked)
        assertTrue(strict.validate("http://169.254.169.254/latest/meta-data/") is UrlPolicyCheck.Blocked)
        assertTrue(strict.validate("http://10.0.0.1/") is UrlPolicyCheck.Blocked)
        assertTrue(strict.validate("file:///etc/passwd") is UrlPolicyCheck.Blocked)

        // Test 302 redirect from authorized local fixture port -> 169.254.169.254 is blocked!
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse(
                        code = 302,
                        headers = headersOf("Location", "http://169.254.169.254/latest/meta-data/")
                    )
                )
                val fetcher = WebFetcher(SsrfPolicyValidator(allowedLocalPorts = setOf(server.port)))
                val res = fetcher.fetch(server.url("/redirect").toString())
                assertTrue(res.isFailure)
                assertTrue(res.exceptionOrNull() is SecurityException)
            }
        }
    }

    @Test
    fun `search router deduplicates, reports degraded source, fetcher caches ETag and detects captcha handoff`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/html", "ETag", "\"v1\""),
                    body = """
                        <html><head><title>Oneflow Docs</title></head>
                        <body><main><h1>Architecture</h1><p>Independent Kotlin kernel with Code Mode.</p></main></body></html>
                    """.trimIndent()
                )
            )
            server.enqueue(MockResponse(code = 304))
            server.enqueue(
                MockResponse(
                    code = 200,
                    headers = headersOf("Content-Type", "text/html"),
                    body = "<html><body><div>Verify you are human (cf-turnstile)</div></body></html>"
                )
            )

            val docUrl = server.url("/docs").toString()
            val captchaUrl = server.url("/challenge").toString()

            val okAdapter = object : SearchAdapter {
                override val sourceId = "fixture-ok"
                override suspend fun search(query: String, limit: Int) = Result.success(
                    listOf(
                        SearchHit(docUrl, "Oneflow Docs", "Architecture overview", "fixture-ok", rank = 1),
                        SearchHit("$docUrl/", "Duplicate", "Should dedupe", "fixture-ok", rank = 2)
                    )
                )
            }
            val failAdapter = object : SearchAdapter {
                override val sourceId = "fixture-down"
                override suspend fun search(query: String, limit: Int) = Result.failure<List<SearchHit>>(IllegalStateException("429 throttled"))
            }

            val router = SearchRouter(listOf(okAdapter, failAdapter))
            val fetcher = WebFetcher(SsrfPolicyValidator(allowedLocalPorts = setOf(server.port)))
            val ledger = ResearchLedger()
            val broker = SafeToolBroker(DefaultToolRegistry())
            FlowWebTools(router, fetcher, ledger).registerAll(broker)
            val grants = setOf("web.read")

            // Search deduplicates URLs & reports source failure honestly
            val sRes = broker.invoke(
                ToolRequest(ToolRequestId("s1"), "ks1", MissionId("m1"), toolId = "web.search", toolVersion = "1.0.0", arguments = buildJsonObject { put("query", JsonPrimitive("Oneflow")) }),
                missionGrants = grants
            )
            assertEquals(ToolResultStatus.OK, sRes.status)
            assertTrue(sRes.data!!["sourceErrors"].toString().contains("fixture-down"))

            // Fetch 1 -> extracts HTML & records citation; Fetch 2 -> ETag 304 cache hit
            val f1 = broker.invoke(
                ToolRequest(ToolRequestId("f1"), "kf1", MissionId("m1"), toolId = "web.fetch", toolVersion = "1.0.0", arguments = buildJsonObject { put("url", JsonPrimitive(docUrl)) }),
                missionGrants = grants
            )
            assertEquals(ToolResultStatus.OK, f1.status)
            assertTrue(f1.data!!["text"]!!.jsonPrimitive.content.contains("Independent Kotlin kernel"))

            val f2 = broker.invoke(
                ToolRequest(ToolRequestId("f2"), "kf2", MissionId("m1"), toolId = "web.fetch", toolVersion = "1.0.0", arguments = buildJsonObject { put("url", JsonPrimitive(docUrl)) }),
                missionGrants = grants
            )
            assertEquals(ToolResultStatus.OK, f2.status)
            assertEquals("true", f2.data!!["fromCache"]!!.jsonPrimitive.content)

            // Fetch 3 -> CAPTCHA triggers APPROVAL_REQUIRED handoff (no bypass)
            val f3 = broker.invoke(
                ToolRequest(ToolRequestId("f3"), "kf3", MissionId("m1"), toolId = "web.fetch", toolVersion = "1.0.0", arguments = buildJsonObject { put("url", JsonPrimitive(captchaUrl)) }),
                missionGrants = grants
            )
            assertEquals(ToolResultStatus.APPROVAL_REQUIRED, f3.status)

            // Cited Markdown report
            val rep = broker.invoke(
                ToolRequest(
                    ToolRequestId("r1"), "kr1", MissionId("m1"), toolId = "research.report", toolVersion = "1.0.0",
                    arguments = buildJsonObject {
                        put("title", JsonPrimitive("Research Report"))
                        put("summary", JsonPrimitive("Verified architecture findings."))
                    }
                ),
                missionGrants = grants
            )
            assertEquals(ToolResultStatus.OK, rep.status)
            val md = rep.data!!["markdown"]!!.jsonPrimitive.content
            assertTrue(md.contains("Oneflow Docs"))
            assertTrue(md.contains(docUrl.trimEnd('/')))
        }
    }

    @Test
    fun `browser ref registry rejects stale snapshot generation`() {
        val reg = BrowserRefRegistry()
        reg.recordSnapshot(
            DomSnapshot(
                spaceId = "sp-1",
                pageId = "pg-1",
                generation = 2L,
                url = "https://example.org",
                title = "Example",
                textSummary = "Hello",
                elements = listOf(BrowserElementRef("el-1", "button", "Submit", "#submit"))
            )
        )
        assertTrue(reg.resolveForAction("sp-1", "pg-1", 2L, "el-1") is RefResolution.Valid)
        val stale = reg.resolveForAction("sp-1", "pg-1", 1L, "el-1")
        assertTrue(stale is RefResolution.Stale)
        assertEquals(ErrorCode.STALE_STATE, (stale as RefResolution.Stale).error.code)
    }
}
