package ai.oneflow.capabilities.files

import ai.oneflow.core.contracts.ArtifactId
import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.RiskTier
import ai.oneflow.core.contracts.ToolExecutionPolicy
import ai.oneflow.core.contracts.ToolManifest
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.execution.broker.BrokerToolException
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Bounded, path-contained workspace file & artifact tools (§6.4, Phase 2).
 */
class WorkspaceFileTools(
    val pathGuard: WorkspacePathGuard,
    val artifactStore: ArtifactStore,
    val journal: TransactionJournal,
    private val maxReadBytes: Int = 128 * 1024,
    private val maxWriteBytes: Int = 512 * 1024
) {
    private fun schema(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private fun resolveSafe(rel: String, allowRoot: Boolean = false): Path {
        return try {
            pathGuard.resolveContained(rel, allowRoot = allowRoot)
        } catch (se: SecurityException) {
            throw BrokerToolException(ToolResultStatus.DENIED, ErrorCode.DENIED, se.message ?: "Path denied")
        }
    }

    fun registerAll(broker: SafeToolBroker) {
        registerList(broker)
        registerGlob(broker)
        registerRead(broker)
        registerGrep(broker)
        registerWrite(broker)
        registerPatch(broker)
        registerArtifacts(broker)
        registerVerifyFile(broker)
    }

    private fun registerList(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "fs.list",
                version = "1.0.0",
                description = "List entries in an authorized workspace directory",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "path": { "type": "string" },
                        "limit": { "type": "integer", "minimum": 1, "maximum": 500 }
                      },
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("workspace.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("fs", "list", "workspace"),
                exampleUsage = """await flow.fs.list({ path: ".", limit: 100 })"""
            )
        ) { req ->
            val rel = req.arguments["path"]?.jsonPrimitive?.contentOrNull ?: "."
            val limit = req.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 200
            val dir = resolveSafe(rel, allowRoot = true)
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw BrokerToolException(ToolResultStatus.INVALID_ARGUMENT, ErrorCode.INVALID_ARGUMENT, "Not a directory: $rel")
            }
            val items = Files.list(dir).use { stream ->
                stream.sorted().limit(limit.toLong()).map { p ->
                    val isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                    val size = if (isDir) 0L else runCatching { Files.size(p) }.getOrDefault(0L)
                    buildJsonObject {
                        put("name", JsonPrimitive(p.fileName.toString()))
                        put("path", JsonPrimitive(pathGuard.relativize(p)))
                        put("type", JsonPrimitive(if (isDir) "dir" else "file"))
                        put("bytes", JsonPrimitive(size))
                    }
                }.toList()
            }
            buildJsonObject {
                put("path", JsonPrimitive(rel))
                put("entries", JsonArray(items))
            }
        }
    }

    private fun registerGlob(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "fs.glob",
                version = "1.0.0",
                description = "Find workspace files matching a glob pattern",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "pattern": { "type": "string", "minLength": 1 },
                        "limit": { "type": "integer", "minimum": 1, "maximum": 500 }
                      },
                      "required": ["pattern"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("workspace.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("fs", "glob", "search"),
                exampleUsage = """await flow.fs.glob({ pattern: "**/*.html", limit: 100 })"""
            )
        ) { req ->
            val pattern = req.arguments["pattern"]!!.jsonPrimitive.content
            val limit = req.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 200
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            val matches = mutableListOf<String>()
            Files.walk(pathGuard.rootRealPath).use { stream ->
                val it = stream.iterator()
                while (it.hasNext() && matches.size < limit) {
                    val p = it.next()
                    if (Files.isSymbolicLink(p)) continue
                    if (p == pathGuard.rootRealPath) continue
                    val rel = Path.of(pathGuard.relativize(p))
                    if (rel.toString().startsWith(".oneflow")) continue
                    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && matcher.matches(rel)) {
                        matches += rel.toString().replace('\\', '/')
                    }
                }
            }
            buildJsonObject {
                put("pattern", JsonPrimitive(pattern))
                put("files", buildJsonArray { matches.forEach { add(JsonPrimitive(it)) } })
            }
        }
    }

    private fun registerRead(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "fs.read",
                version = "1.0.0",
                description = "Read a UTF-8 workspace file within byte limits and return its sha256",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "path": { "type": "string", "minLength": 1 },
                        "max_bytes": { "type": "integer", "minimum": 1, "maximum": 131072 }
                      },
                      "required": ["path"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("workspace.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("fs", "read"),
                exampleUsage = """await flow.fs.read({ path: "index.html" })"""
            )
        ) { req ->
            val rel = req.arguments["path"]!!.jsonPrimitive.content
            val cap = (req.arguments["max_bytes"]?.jsonPrimitive?.intOrNull ?: maxReadBytes).coerceAtMost(maxReadBytes)
            val file = resolveSafe(rel)
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw BrokerToolException(ToolResultStatus.UNAVAILABLE, ErrorCode.UNAVAILABLE, "File not found: $rel")
            }
            val bytes = Files.readAllBytes(file)
            val sha = ArtifactStore.sha256Hex(bytes)
            val truncated = bytes.size > cap
            val slice = if (truncated) bytes.copyOf(cap) else bytes
            buildJsonObject {
                put("path", JsonPrimitive(pathGuard.relativize(file)))
                put("sha256", JsonPrimitive(sha))
                put("bytes", JsonPrimitive(bytes.size))
                put("truncated", JsonPrimitive(truncated))
                put("content", JsonPrimitive(slice.toString(Charsets.UTF_8)))
            }
        }
    }

    private fun registerGrep(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "fs.grep",
                version = "1.0.0",
                description = "Search workspace text files for a literal substring or bounded pattern",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "query": { "type": "string", "minLength": 1, "maxLength": 200 },
                        "limit": { "type": "integer", "minimum": 1, "maximum": 100 }
                      },
                      "required": ["query"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("workspace.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("fs", "grep", "search"),
                exampleUsage = """await flow.fs.grep({ query: "viewport", limit: 20 })"""
            )
        ) { req ->
            val query = req.arguments["query"]!!.jsonPrimitive.content
            val limit = req.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 50
            val hits = mutableListOf<kotlinx.serialization.json.JsonObject>()
            Files.walk(pathGuard.rootRealPath).use { stream ->
                val it = stream.iterator()
                while (it.hasNext() && hits.size < limit) {
                    val p = it.next()
                    if (Files.isSymbolicLink(p) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue
                    val rel = pathGuard.relativize(p)
                    if (rel.startsWith(".oneflow")) continue
                    if (Files.size(p) > maxReadBytes) continue
                    val lines = runCatching { Files.readAllLines(p, Charsets.UTF_8) }.getOrNull() ?: continue
                    lines.forEachIndexed { idx, line ->
                        if (hits.size < limit && line.contains(query)) {
                            hits += buildJsonObject {
                                put("path", JsonPrimitive(rel))
                                put("line", JsonPrimitive(idx + 1))
                                put("text", JsonPrimitive(line.take(240)))
                            }
                        }
                    }
                }
            }
            buildJsonObject {
                put("query", JsonPrimitive(query))
                put("matches", JsonArray(hits))
            }
        }
    }

    private fun registerWrite(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "fs.write",
                version = "1.0.0",
                description = "Atomically write a UTF-8 file in the workspace with undo journal support",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "path": { "type": "string", "minLength": 1 },
                        "content": { "type": "string" },
                        "expected_sha256": { "type": "string" }
                      },
                      "required": ["path", "content"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("workspace.write"),
                risk = RiskTier.REVERSIBLE_WRITE,
                execution = ToolExecutionPolicy(concurrency = "exclusive_workspace_write"),
                tags = setOf("fs", "write"),
                exampleUsage = """await flow.fs.write({ path: "index.html", content: "<!doctype html>..." })"""
            )
        ) { req ->
            val rel = req.arguments["path"]!!.jsonPrimitive.content
            val content = req.arguments["content"]!!.jsonPrimitive.content
            val expectedSha = req.arguments["expected_sha256"]?.jsonPrimitive?.contentOrNull
            val newBytes = content.toByteArray(Charsets.UTF_8)
            if (newBytes.size > maxWriteBytes) {
                throw BrokerToolException(ToolResultStatus.INVALID_ARGUMENT, ErrorCode.INVALID_ARGUMENT, "Content exceeds maxWriteBytes ($maxWriteBytes)")
            }

            val target = resolveSafe(rel)
            val prevBytes = if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.readAllBytes(target) else null
            val prevSha = prevBytes?.let { ArtifactStore.sha256Hex(it) }
            if (expectedSha != null && expectedSha != prevSha) {
                throw BrokerToolException(
                    ToolResultStatus.STALE_STATE,
                    ErrorCode.STALE_STATE,
                    "expected_sha256 mismatch for '$rel': expected=$expectedSha actual=${prevSha ?: "absent"}"
                )
            }

            Files.createDirectories(target.parent)
            val normalizedRel = pathGuard.relativize(target)
            val tx = journal.recordWrite(normalizedRel, prevBytes, newBytes)

            val tmp = target.parent.resolve("${target.fileName}.oneflow.tmp")
            Files.write(tmp, newBytes)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

            val artifact = artifactStore.putBytes(newBytes, mimeType = guessMime(normalizedRel), preview = content.take(200))
            buildJsonObject {
                put("path", JsonPrimitive(normalizedRel))
                put("sha256", JsonPrimitive(tx.newSha256))
                put("bytes", JsonPrimitive(newBytes.size))
                put("transactionId", JsonPrimitive(tx.transactionId))
                put("artifactId", JsonPrimitive(artifact.id.value))
            }
        }
    }

    private fun registerPatch(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "fs.patch",
                version = "1.0.0",
                description = "Replace an exact substring in an existing workspace file guarded by expected_sha256",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "path": { "type": "string", "minLength": 1 },
                        "find": { "type": "string", "minLength": 1 },
                        "replace": { "type": "string" },
                        "expected_sha256": { "type": "string", "minLength": 1 }
                      },
                      "required": ["path", "find", "replace", "expected_sha256"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("workspace.write"),
                risk = RiskTier.REVERSIBLE_WRITE,
                execution = ToolExecutionPolicy(concurrency = "exclusive_workspace_write"),
                tags = setOf("fs", "patch"),
                exampleUsage = """await flow.fs.patch({ path: "index.html", find: "Old", replace: "New", expected_sha256: "..." })"""
            )
        ) { req ->
            val rel = req.arguments["path"]!!.jsonPrimitive.content
            val find = req.arguments["find"]!!.jsonPrimitive.content
            val replace = req.arguments["replace"]!!.jsonPrimitive.content
            val expectedSha = req.arguments["expected_sha256"]!!.jsonPrimitive.content

            val target = resolveSafe(rel)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw BrokerToolException(ToolResultStatus.UNAVAILABLE, ErrorCode.UNAVAILABLE, "File not found: $rel")
            }
            val prevBytes = Files.readAllBytes(target)
            val prevSha = ArtifactStore.sha256Hex(prevBytes)
            if (prevSha != expectedSha) {
                throw BrokerToolException(
                    ToolResultStatus.STALE_STATE,
                    ErrorCode.STALE_STATE,
                    "expected_sha256 mismatch for '$rel': expected=$expectedSha actual=$prevSha"
                )
            }
            val text = prevBytes.toString(Charsets.UTF_8)
            if (!text.contains(find)) {
                throw BrokerToolException(ToolResultStatus.INVALID_ARGUMENT, ErrorCode.INVALID_ARGUMENT, "Target text not found in '$rel'")
            }
            val updated = text.replaceFirst(find, replace)
            val newBytes = updated.toByteArray(Charsets.UTF_8)
            val normalizedRel = pathGuard.relativize(target)
            val tx = journal.recordWrite(normalizedRel, prevBytes, newBytes)

            val tmp = target.parent.resolve("${target.fileName}.oneflow.tmp")
            Files.write(tmp, newBytes)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

            buildJsonObject {
                put("path", JsonPrimitive(normalizedRel))
                put("previousSha256", JsonPrimitive(prevSha))
                put("newSha256", JsonPrimitive(tx.newSha256))
                put("transactionId", JsonPrimitive(tx.transactionId))
            }
        }
    }

    private fun registerArtifacts(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "artifact.create",
                version = "1.0.0",
                description = "Store text in the content-addressed artifact store",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "content": { "type": "string" },
                        "mime_type": { "type": "string" }
                      },
                      "required": ["content"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("workspace.write"),
                risk = RiskTier.REVERSIBLE_WRITE,
                tags = setOf("artifact", "store")
            )
        ) { req ->
            val content = req.arguments["content"]!!.jsonPrimitive.content
            val mime = req.arguments["mime_type"]?.jsonPrimitive?.contentOrNull ?: "text/plain"
            val rec = artifactStore.putText(content, mime)
            buildJsonObject {
                put("artifactId", JsonPrimitive(rec.id.value))
                put("sha256", JsonPrimitive(rec.sha256))
                put("bytes", JsonPrimitive(rec.byteSize))
                put("mimeType", JsonPrimitive(rec.mimeType))
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "artifact.read",
                version = "1.0.0",
                description = "Read a stored artifact by artifactId",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": { "artifact_id": { "type": "string", "minLength": 1 } },
                      "required": ["artifact_id"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("workspace.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("artifact", "read")
            )
        ) { req ->
            val id = req.arguments["artifact_id"]!!.jsonPrimitive.content
            val text = artifactStore.readText(ArtifactId(id))
                ?: throw BrokerToolException(ToolResultStatus.UNAVAILABLE, ErrorCode.UNAVAILABLE, "Artifact not found: $id")
            buildJsonObject {
                put("artifactId", JsonPrimitive(id))
                put("content", JsonPrimitive(text.take(maxReadBytes)))
            }
        }
    }

    private fun registerVerifyFile(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "verify.file",
                version = "1.0.0",
                description = "Deterministically verify a workspace file exists and contains required substrings",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "path": { "type": "string", "minLength": 1 },
                        "must_contain": { "type": "array", "items": { "type": "string" } },
                        "non_empty": { "type": "boolean" }
                      },
                      "required": ["path"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("workspace.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("verify", "test"),
                exampleUsage = """await flow.verify.file({ path: "index.html", must_contain: ["viewport", "<style"], non_empty: true })"""
            )
        ) { req ->
            val rel = req.arguments["path"]!!.jsonPrimitive.content
            val nonEmpty = req.arguments["non_empty"]?.jsonPrimitive?.booleanOrNull ?: true
            val mustContain = req.arguments["must_contain"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

            val file = resolveSafe(rel)
            val exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            if (!exists) {
                return@registerTool buildJsonObject {
                    put("passed", JsonPrimitive(false))
                    put("reason", JsonPrimitive("File does not exist: $rel"))
                }
            }
            val bytes = Files.readAllBytes(file)
            if (nonEmpty && bytes.isEmpty()) {
                return@registerTool buildJsonObject {
                    put("passed", JsonPrimitive(false))
                    put("reason", JsonPrimitive("File is empty: $rel"))
                }
            }
            val text = bytes.toString(Charsets.UTF_8)
            val missing = mustContain.filterNot { text.contains(it) }
            buildJsonObject {
                put("passed", JsonPrimitive(missing.isEmpty()))
                put("sha256", JsonPrimitive(ArtifactStore.sha256Hex(bytes)))
                put("bytes", JsonPrimitive(bytes.size))
                put("missing", buildJsonArray { missing.forEach { add(JsonPrimitive(it)) } })
            }
        }
    }

    private fun guessMime(path: String): String = when {
        path.endsWith(".html", true) || path.endsWith(".htm", true) -> "text/html"
        path.endsWith(".css", true) -> "text/css"
        path.endsWith(".js", true) -> "application/javascript"
        path.endsWith(".json", true) -> "application/json"
        else -> "text/plain"
    }
}
