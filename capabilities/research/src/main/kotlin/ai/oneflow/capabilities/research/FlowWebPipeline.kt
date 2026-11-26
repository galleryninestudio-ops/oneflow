package ai.oneflow.capabilities.research

import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.RiskTier
import ai.oneflow.core.contracts.ToolManifest
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.core.intelligence.DeterministicContextIndex
import ai.oneflow.execution.broker.BrokerToolException
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
data class SearchHit(
    val canonicalUrl: String,
    val title: String,
    val snippet: String,
    val sourceId: String,
    val publishedDate: String? = null,
    val rank: Int = 0
)

@Serializable
data class SearchResponse(
    val query: String,
    val hits: List<SearchHit>,
    val sourceErrors: Map<String, String> = emptyMap()
)

interface SearchAdapter {
    val sourceId: String
    suspend fun search(query: String, limit: Int): Result<List<SearchHit>>
}

/**
 * Keyless Wikipedia OpenSearch API adapter.
 */
class WikipediaOpenSearchAdapter(
    private val client: OkHttpClient = WebFetcher.defaultClient(),
    private val validator: SsrfPolicyValidator = SsrfPolicyValidator(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SearchAdapter {
    override val sourceId: String = "wikipedia"

    override suspend fun search(query: String, limit: Int): Result<List<SearchHit>> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=$limit&namespace=0&format=json"
        if (validator.validate(url) is UrlPolicyCheck.Blocked) {
            return@withContext Result.failure(SecurityException("Blocked URL"))
        }
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "OneflowResearch/0.4").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val arr = json.parseToJsonElement(resp.body.string()) as JsonArray
                val titles = (arr.getOrNull(1) as? JsonArray).orEmpty()
                val descs = (arr.getOrNull(2) as? JsonArray).orEmpty()
                val urls = (arr.getOrNull(3) as? JsonArray).orEmpty()
                titles.indices.mapNotNull { i ->
                    val u = urls.getOrNull(i)?.jsonPrimitive?.content ?: return@mapNotNull null
                    val t = titles.getOrNull(i)?.jsonPrimitive?.content ?: u
                    val s = descs.getOrNull(i)?.jsonPrimitive?.content.orEmpty()
                    SearchHit(canonicalUrl = u, title = t, snippet = s, sourceId = sourceId, rank = i + 1)
                }
            }
        }
    }
}

class SearchRouter(
    private val adapters: List<SearchAdapter>,
    private val circuitBreaker: SourceCircuitBreaker = SourceCircuitBreaker()
) {
    suspend fun search(query: String, limit: Int = 10): SearchResponse {
        val seen = linkedMapOf<String, SearchHit>()
        val errors = linkedMapOf<String, String>()

        for (adapter in adapters) {
            if (!circuitBreaker.allowRequest(adapter.sourceId)) {
                errors[adapter.sourceId] = "Circuit open (${circuitBreaker.health(adapter.sourceId)})"
                continue
            }
            val res = adapter.search(query, limit)
            res.fold(
                onSuccess = { items ->
                    circuitBreaker.recordSuccess(adapter.sourceId)
                    for (item in items) {
                        val canonical = normalizeUrl(item.canonicalUrl)
                        if (!seen.containsKey(canonical)) {
                            seen[canonical] = item.copy(canonicalUrl = canonical)
                        }
                    }
                },
                onFailure = { err ->
                    circuitBreaker.recordFailure(adapter.sourceId)
                    errors[adapter.sourceId] = err.message ?: "Search failed"
                }
            )
        }
        return SearchResponse(
            query = query,
            hits = seen.values.take(limit),
            sourceErrors = errors
        )
    }

    private fun normalizeUrl(url: String): String =
        url.trim().substringBefore('#').trimEnd('/')
}

@Serializable
data class ExtractedDocument(
    val sourceId: String,
    val canonicalUrl: String,
    val title: String,
    val textContent: String,
    val headings: List<String>,
    val links: List<String>,
    val contentSha256: String,
    val fetchedAtEpochMs: Long,
    val fromCache: Boolean = false,
    val challengeDetected: String? = null
)

/**
 * SSRF-safe HTTP fetcher + jsoup Readability-style extractor with ETag cache and challenge detection (§10.2).
 */
class WebFetcher(
    private val validator: SsrfPolicyValidator = SsrfPolicyValidator(),
    private val client: OkHttpClient = defaultClient(),
    private val index: DeterministicContextIndex = DeterministicContextIndex(),
    private val maxBodyBytes: Int = 512 * 1024
) {
    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private data class CachedEntry(
        val doc: ExtractedDocument,
        val etag: String?,
        val lastModified: String?
    )

    private val cache = ConcurrentHashMap<String, CachedEntry>()

    suspend fun fetch(rawUrl: String): Result<ExtractedDocument> = withContext(Dispatchers.IO) {
        var currentUrl = rawUrl.trim()
        for (hop in 0..5) {
            when (val check = validator.validate(currentUrl)) {
                is UrlPolicyCheck.Blocked -> return@withContext Result.failure(SecurityException(check.reason))
                is UrlPolicyCheck.Allowed -> Unit
            }

            val cached = cache[currentUrl]
            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", "OneflowResearch/0.4")
                .get()
            cached?.etag?.let { reqBuilder.header("If-None-Match", it) }
            cached?.lastModified?.let { reqBuilder.header("If-Modified-Since", it) }

            val resp = try {
                client.newCall(reqBuilder.build()).execute()
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }

            resp.use { r ->
                if (r.code == 304 && cached != null) {
                    return@withContext Result.success(cached.doc.copy(fromCache = true))
                }
                if (r.code in 300..399) {
                    val loc = r.header("Location")
                        ?: return@withContext Result.failure(IllegalStateException("Redirect ${r.code} missing Location"))
                    val resolved = java.net.URI(currentUrl).resolve(loc).toString()
                    currentUrl = resolved
                    continue
                }
                if (!r.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("HTTP ${r.code} for $currentUrl"))
                }

                val rawBytes = r.body.bytes()
                val bounded = if (rawBytes.size > maxBodyBytes) rawBytes.copyOf(maxBodyBytes) else rawBytes
                val html = bounded.toString(Charsets.UTF_8)
                val extracted = extractHtml(currentUrl, html)
                if (extracted.challengeDetected == null) {
                    cache[currentUrl] = CachedEntry(
                        doc = extracted,
                        etag = r.header("ETag"),
                        lastModified = r.header("Last-Modified")
                    )
                    index.indexDocument(currentUrl, "${extracted.title}\n${extracted.textContent}")
                }
                return@withContext Result.success(extracted)
            }
        }
        Result.failure(IllegalStateException("Too many redirects (>5)"))
    }

    private fun extractHtml(url: String, html: String): ExtractedDocument {
        val doc = Jsoup.parse(html, url)
        val rawLower = html.lowercase()
        val challenge = when {
            rawLower.contains("cf-turnstile") || rawLower.contains("g-recaptcha") || rawLower.contains("verify you are human") ->
                "CAPTCHA / anti-bot challenge detected; manual user handoff required"
            rawLower.contains("type=\"password\"") && doc.select("main, article").text().length < 120 ->
                "Login form detected; manual user authentication required"
            else -> null
        }

        doc.select("script, style, noscript, iframe, svg").remove()
        val mainEl = doc.selectFirst("article, main") ?: doc.body()
        val headings = mainEl.select("h1, h2, h3").map { it.text().trim() }.filter { it.isNotEmpty() }.take(30)
        val links = mainEl.select("a[href]").mapNotNull { it.absUrl("href").takeIf { u -> u.isNotBlank() } }.distinct().take(50)
        val text = mainEl.text().trim()
        val sha = MessageDigest.getInstance("SHA-256").digest(html.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return ExtractedDocument(
            sourceId = "src-${sha.take(12)}",
            canonicalUrl = url,
            title = doc.title().ifBlank { url },
            textContent = text,
            headings = headings,
            links = links,
            contentSha256 = sha,
            fetchedAtEpochMs = System.currentTimeMillis(),
            challengeDetected = challenge
        )
    }

    fun searchLocalCache(query: String, limit: Int = 5) = index.search(query, limit)
}

@Serializable
data class CitationRecord(
    val sourceId: String,
    val url: String,
    val title: String,
    val excerpt: String,
    val sha256: String,
    val fetchedAtEpochMs: Long
)

class ResearchLedger {
    private val citations = mutableListOf<CitationRecord>()

    @Synchronized
    fun record(doc: ExtractedDocument, excerpt: String) {
        citations += CitationRecord(
            sourceId = doc.sourceId,
            url = doc.canonicalUrl,
            title = doc.title,
            excerpt = excerpt.take(400),
            sha256 = doc.contentSha256,
            fetchedAtEpochMs = doc.fetchedAtEpochMs
        )
    }

    @Synchronized
    fun exportMarkdownReport(title: String, summary: String): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine(summary)
        appendLine()
        appendLine("## Sources & Evidence")
        if (citations.isEmpty()) {
            appendLine("_No sources cited yet._")
        } else {
            citations.forEachIndexed { idx, c ->
                appendLine("${idx + 1}. [${c.title}](${c.url}) (`${c.sourceId}`, sha256 `${c.sha256.take(12)}`)")
                if (c.excerpt.isNotBlank()) {
                    appendLine("   > ${c.excerpt}")
                }
            }
        }
    }
}

/**
 * Registers FlowWeb brokered tools (`web.search`, `web.fetch`, `research.report`).
 */
class FlowWebTools(
    private val searchRouter: SearchRouter,
    private val fetcher: WebFetcher,
    private val ledger: ResearchLedger = ResearchLedger()
) {
    private fun schema(raw: String) = Json.parseToJsonElement(raw).jsonObject

    fun registerAll(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "web.search",
                version = "1.0.0",
                description = "Search keyless public sources with URL deduplication and health reporting",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "query": { "type": "string", "minLength": 1 },
                        "limit": { "type": "integer", "minimum": 1, "maximum": 20 }
                      },
                      "required": ["query"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("web.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("web", "search", "research")
            )
        ) { req ->
            val q = req.arguments["query"]!!.jsonPrimitive.content
            val limit = req.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 8
            val resp = searchRouter.search(q, limit)
            buildJsonObject {
                put("query", JsonPrimitive(resp.query))
                put("items", buildJsonArray {
                    for (h in resp.hits) {
                        add(buildJsonObject {
                            put("url", JsonPrimitive(h.canonicalUrl))
                            put("title", JsonPrimitive(h.title))
                            put("snippet", JsonPrimitive(h.snippet))
                            put("source", JsonPrimitive(h.sourceId))
                        })
                    }
                })
                put("sourceErrors", buildJsonObject {
                    resp.sourceErrors.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                })
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "web.fetch",
                version = "1.0.0",
                description = "Fetch and extract public web content with SSRF protection and challenge detection",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": { "url": { "type": "string", "minLength": 1 } },
                      "required": ["url"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("web.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("web", "fetch", "extract")
            )
        ) { req ->
            val url = req.arguments["url"]!!.jsonPrimitive.content
            val res = fetcher.fetch(url)
            val doc = res.getOrElse { err ->
                val status = if (err is SecurityException) ToolResultStatus.DENIED else ToolResultStatus.FAILED
                val code = if (err is SecurityException) ErrorCode.DENIED else ErrorCode.FAILED
                throw BrokerToolException(status, code, err.message ?: "Fetch failed")
            }
            if (doc.challengeDetected != null) {
                throw BrokerToolException(
                    ToolResultStatus.APPROVAL_REQUIRED,
                    ErrorCode.APPROVAL_REQUIRED,
                    doc.challengeDetected
                )
            }
            ledger.record(doc, doc.textContent.take(300))
            buildJsonObject {
                put("sourceId", JsonPrimitive(doc.sourceId))
                put("url", JsonPrimitive(doc.canonicalUrl))
                put("title", JsonPrimitive(doc.title))
                put("sha256", JsonPrimitive(doc.contentSha256))
                put("fromCache", JsonPrimitive(doc.fromCache))
                put("text", JsonPrimitive(doc.textContent.take(16_000)))
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "research.report",
                version = "1.0.0",
                description = "Generate a cited Markdown research report from recorded evidence",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "title": { "type": "string", "minLength": 1 },
                        "summary": { "type": "string", "minLength": 1 }
                      },
                      "required": ["title", "summary"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("web.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("research", "report", "cite")
            )
        ) { req ->
            val title = req.arguments["title"]!!.jsonPrimitive.content
            val summary = req.arguments["summary"]!!.jsonPrimitive.content
            val md = ledger.exportMarkdownReport(title, summary)
            buildJsonObject {
                put("markdown", JsonPrimitive(md))
            }
        }
    }
}
