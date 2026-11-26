package ai.oneflow.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local loopback HTTP + SSE fixture server for Phase 1 on-device verification
 * when no external LLM API key is configured.
 * Strictly labeled as a deterministic test fixture, never a real model.
 */
class LocalFixtureServer(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    val baseUrl: String
        get() = "http://127.0.0.1:${serverSocket?.localPort ?: error("Fixture server not started")}/v1"

    @Synchronized
    fun start(): String {
        if (running.get() && serverSocket != null) return baseUrl
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        running.set(true)
        thread(isDaemon = true, name = "oneflow-fixture-http") {
            while (running.get()) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { handleClient(client) }
            }
        }
        return baseUrl
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()

            val headerBytes = readHeaders(input) ?: return
            val headerText = headerBytes.toString(Charsets.UTF_8)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()

            val contentLength = lines.drop(1)
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0

            val bodyBytes = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)
            val bodyStr = bodyBytes.toString(Charsets.UTF_8)

            when {
                method == "GET" && path.endsWith("/models") -> {
                    writeJson(output, 200, """{"data":[{"id":"fixture-sse-v1"}]}""")
                }
                method == "POST" && path.endsWith("/chat/completions") -> {
                    handleChatCompletions(output, bodyStr)
                }
                else -> {
                    writeJson(output, 404, """{"error":{"message":"Not found: $path"}}""")
                }
            }
        }
    }

    private fun handleChatCompletions(output: OutputStream, rawBody: String) {
        val root = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
        val messages = root?.get("messages") as? JsonArray ?: JsonArray(emptyList())
        val lastMsg = messages.lastOrNull() as? JsonObject
        val lastRole = lastMsg?.get("role")?.jsonPrimitive?.contentOrNull.orEmpty()
        val allUserText = messages
            .filterIsInstance<JsonObject>()
            .filter { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
            .joinToString(" ") { it["content"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .lowercase()

        val sseChunks = if (lastRole == "tool") {
            val toolContent = lastMsg?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val toolMsgCount = messages.count { (it as? JsonObject)?.get("role")?.jsonPrimitive?.contentOrNull == "tool" }
            if ((allUserText.contains("website") || allUserText.contains("html") || allUserText.contains("flowcode")) && toolMsgCount == 1) {
                val jsCode = """
                    const html = '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{background:#000;color:#fff;font-family:sans-serif;padding:20px}.card{background:#141414;border:1px solid #303030;border-radius:12px;padding:16px}@media(max-width:600px){body{padding:12px}}</style></head><body><div class="card"><h1>Oneflow Preview</h1><p>Created via FlowCode QuickJS-NG isolated worker.</p></div></body></html>';
                    const w = await flow.fs.write({ path: 'index.html', content: html });
                    const v = await flow.verify.file({ path: 'index.html', must_contain: ['viewport', 'Oneflow Preview'] });
                    return { w, v };
                """.trimIndent()
                val argsEscaped = Json.encodeToString(JsonPrimitive(buildJsonObject { put("code", JsonPrimitive(jsCode)) }.toString()))
                listOf(
                    """{"choices":[{"index":0,"delta":{"content":"[TEST FIXTURE] Executing FlowCode website script...\n"}}]}""",
                    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_fix_exec_1","type":"function","function":{"name":"execute","arguments":$argsEscaped}}]}}]}""",
                    """{"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}],"usage":{"prompt_tokens":20,"completion_tokens":24,"total_tokens":44}}""",
                    "[DONE]"
                )
            } else {
                val escaped = Json.encodeToString(JsonPrimitive("[TEST FIXTURE] Broker returned: $toolContent"))
                listOf(
                    """{"choices":[{"index":0,"delta":{"content":$escaped}}]}""",
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{}}],"usage":{"prompt_tokens":24,"completion_tokens":14,"total_tokens":38}}""",
                    "[DONE]"
                )
            }
        } else if (allUserText.contains("website") || allUserText.contains("html") || allUserText.contains("flowcode")) {
            listOf(
                """{"choices":[{"index":0,"delta":{"content":"[TEST FIXTURE] Discovering FlowCode workspace tools...\n"}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_fix_disc_1","type":"function","function":{"name":"discover","arguments":"{\"query\":\"fs verify\",\"limit\":10}"}}]}}]}""",
                """{"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}],"usage":{"prompt_tokens":16,"completion_tokens":12,"total_tokens":28}}""",
                "[DONE]"
            )
        } else if (allUserText.contains("approval") || allUserText.contains("publish")) {
            listOf(
                """{"choices":[{"index":0,"delta":{"content":"[TEST FIXTURE] Requesting approval-gated tool...\n"}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_fix_pub_1","type":"function","function":{"name":"fixture_publish_note","arguments":"{\"note\":\"Phase 1 fixture approval\"}"}}]}}]}""",
                """{"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}],"usage":{"prompt_tokens":18,"completion_tokens":16,"total_tokens":34}}""",
                "[DONE]"
            )
        } else {
            listOf(
                """{"choices":[{"index":0,"delta":{"content":"[TEST FIXTURE] Calling fixture_math_add(19, 23)...\n"}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_fix_add_1","type":"function","function":{"name":"fixture_math_add","arguments":"{\"a\":19,\"b\":23}"}}]}}]}""",
                """{"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}],"usage":{"prompt_tokens":16,"completion_tokens":15,"total_tokens":31}}""",
                "[DONE]"
            )
        }

        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        for (chunk in sseChunks) {
            output.write("data: $chunk\n\n".toByteArray(Charsets.UTF_8))
            output.flush()
            Thread.sleep(60)
        }
    }

    private fun writeJson(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readExact(input: BufferedInputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(out, offset, len - offset)
            if (n <= 0) break
            offset += n
        }
        return if (offset == len) out else out.copyOf(offset)
    }

    private fun readHeaders(input: BufferedInputStream): ByteArray? {
        val buf = ArrayList<Byte>(512)
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toByteArray()
            buf.add(b.toByte())
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) return buf.toByteArray() else 0
                else -> 0
            }
            if (buf.size > 32 * 1024) return null
        }
    }
}
