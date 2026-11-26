package ai.oneflow.capabilities.development

import ai.oneflow.capabilities.files.WorkspacePathGuard
import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.RiskTier
import ai.oneflow.core.contracts.ToolExecutionPolicy
import ai.oneflow.core.contracts.ToolManifest
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.execution.broker.BrokerToolException
import ai.oneflow.execution.broker.SafeToolBroker
import ai.oneflow.platform.runtime.FlowEnvironmentDetector
import ai.oneflow.platform.runtime.FlowRuntimeLauncher
import ai.oneflow.platform.runtime.ProcessSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption

@Serializable
data class DevKitStatus(
    val id: String,
    val name: String,
    val status: String,
    val detail: String
)

object DevKitCatalog {
    fun inspectHostCapabilities(isInAppSandbox: Boolean): List<DevKitStatus> = listOf(
        DevKitStatus(
            id = "foundation",
            name = "Foundation & Git",
            status = if (isInAppSandbox) "PARTIAL_LOCAL" else "RUNNABLE_HOST",
            detail = if (isInAppSandbox) "File/symbol/env tools active; external git/shell requires approved packaged binary or remote runner." else "git, coreutils, clang, python3, node verified on host."
        ),
        DevKitStatus(
            id = "android",
            name = "Android SDK Kit",
            status = if (isInAppSandbox) "DISABLED_IN_APP" else "RUNNABLE_ADEV",
            detail = "Host adev ARM64 builds verified (JDK 21, aapt2, d8, NDK 28). In-app standalone SDK kit deferred until signed APK-packaged delivery is validated."
        ),
        DevKitStatus(
            id = "python_web_native",
            name = "Python / Web / Native Kits",
            status = if (isInAppSandbox) "DISABLED_IN_APP" else "RUNNABLE_HOST",
            detail = "Disabled inside Android APK sandbox unless signed FlowPkg kit is installed & smoke-tested."
        ),
        DevKitStatus(
            id = "agent_shell",
            name = "Unrestricted Agent Shell",
            status = "DISABLED_SECURITY",
            detail = "Prohibited without proven OS-level file/network isolation (§7.2, §9.1)."
        )
    )
}

/**
 * Brokered, workspace-contained development tools (env.detect, code.symbols, git.*, process.run_approved).
 */
class DevWorkspaceTools(
    private val pathGuard: WorkspacePathGuard,
    private val launcher: FlowRuntimeLauncher = FlowRuntimeLauncher(),
    private val approvedExecutables: Set<String> = emptySet(),
    private val envAllowlist: Map<String, String> = mapOf("PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"))
) {
    private fun schema(raw: String) = Json.parseToJsonElement(raw).jsonObject

    fun registerAll(broker: SafeToolBroker) {
        registerEnvDetect(broker)
        registerCodeSymbols(broker)
        registerGitTools(broker)
        registerRunApproved(broker)
    }

    private fun registerEnvDetect(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "env.detect",
                version = "1.0.0",
                description = "Detect project manifests, Git branch, and suggested commands without executing build scripts",
                inputSchema = schema("""{"type":"object","properties":{},"additionalProperties":false}"""),
                requiredCapabilities = setOf("workspace.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("env", "detect", "dev")
            )
        ) {
            val manifest = FlowEnvironmentDetector.detect(pathGuard.rootRealPath)
            buildJsonObject {
                put("projectTypes", buildJsonArray { manifest.projectTypes.forEach { add(JsonPrimitive(it)) } })
                manifest.gitBranch?.let { put("gitBranch", JsonPrimitive(it)) }
                put("hasAgentsMd", JsonPrimitive(manifest.hasAgentsMd))
                put("suggestedCommands", buildJsonArray { manifest.suggestedCommands.forEach { add(JsonPrimitive(it)) } })
                put("detectedFiles", buildJsonArray { manifest.detectedFiles.forEach { add(JsonPrimitive(it)) } })
            }
        }
    }

    private fun registerCodeSymbols(broker: SafeToolBroker) {
        val symbolRegex = Regex("""^\s*(?:public|private|internal|protected|suspend|data|sealed|open|abstract|override|\s)*\b(class|interface|object|fun|enum class|def|function)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        broker.registerTool(
            ToolManifest(
                id = "code.symbols",
                version = "1.0.0",
                description = "Extract class/function/interface symbol locations from a workspace source file",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": { "path": { "type": "string", "minLength": 1 } },
                      "required": ["path"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("workspace.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("code", "symbols", "lsp")
            )
        ) { req ->
            val rel = req.arguments["path"]!!.jsonPrimitive.content
            val file = try {
                pathGuard.resolveContained(rel)
            } catch (se: SecurityException) {
                throw BrokerToolException(ToolResultStatus.DENIED, ErrorCode.DENIED, se.message ?: "Denied")
            }
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw BrokerToolException(ToolResultStatus.UNAVAILABLE, ErrorCode.UNAVAILABLE, "File not found: $rel")
            }
            val lines = Files.readAllLines(file, Charsets.UTF_8)
            val symbols = buildJsonArray {
                lines.forEachIndexed { idx, line ->
                    val m = symbolRegex.find(line)
                    if (m != null) {
                        add(buildJsonObject {
                            put("kind", JsonPrimitive(m.groupValues[1]))
                            put("name", JsonPrimitive(m.groupValues[2]))
                            put("line", JsonPrimitive(idx + 1))
                        })
                    }
                }
            }
            buildJsonObject {
                put("path", JsonPrimitive(pathGuard.relativize(file)))
                put("symbols", symbols)
            }
        }
    }

    private fun registerGitTools(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "git.status",
                version = "1.0.0",
                description = "Run structured `git status --short` inside workspace",
                inputSchema = schema("""{"type":"object","properties":{},"additionalProperties":false}"""),
                requiredCapabilities = setOf("workspace.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("git", "status")
            )
        ) {
            val out = launcher.run(
                ProcessSpec(
                    executable = "git",
                    args = listOf("status", "--short", "--branch"),
                    workingDir = pathGuard.rootRealPath.toString(),
                    envAllowlist = envAllowlist
                )
            )
            buildJsonObject {
                put("exitCode", JsonPrimitive(out.exitCode))
                put("stdout", JsonPrimitive(out.stdout))
                put("stderr", JsonPrimitive(out.stderr))
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "git.checkpoint",
                version = "1.0.0",
                description = "Stage and commit workspace changes as a local Git checkpoint",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": { "message": { "type": "string", "minLength": 1, "maxLength": 200 } },
                      "required": ["message"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("workspace.write"),
                risk = RiskTier.REVERSIBLE_WRITE,
                execution = ToolExecutionPolicy(concurrency = "exclusive_workspace_write"),
                tags = setOf("git", "checkpoint", "commit")
            )
        ) { req ->
            val msg = req.arguments["message"]!!.jsonPrimitive.content
            val dir = pathGuard.rootRealPath.toString()
            val addOut = launcher.run(ProcessSpec("git", listOf("add", "-A"), dir, envAllowlist))
            if (addOut.exitCode != 0) {
                throw BrokerToolException(ToolResultStatus.FAILED, ErrorCode.FAILED, "git add failed: ${addOut.stderr}")
            }
            val commitOut = launcher.run(ProcessSpec("git", listOf("commit", "-m", msg), dir, envAllowlist))
            buildJsonObject {
                put("exitCode", JsonPrimitive(commitOut.exitCode))
                put("stdout", JsonPrimitive(commitOut.stdout))
                put("stderr", JsonPrimitive(commitOut.stderr))
            }
        }
    }

    private fun registerRunApproved(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "process.run_approved",
                version = "1.0.0",
                description = "Run an explicitly allowlisted executable with structured arguments (no shell)",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "executable": { "type": "string", "minLength": 1 },
                        "args": { "type": "array", "items": { "type": "string" } },
                        "timeout_ms": { "type": "integer", "minimum": 100, "maximum": 60000 }
                      },
                      "required": ["executable"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("runtime.exec"),
                risk = RiskTier.DISRUPTIVE_LOCAL,
                execution = ToolExecutionPolicy(timeoutMs = 60_000L, concurrency = "exclusive_workspace_write"),
                tags = setOf("process", "runtime", "test")
            )
        ) { req ->
            val exe = req.arguments["executable"]!!.jsonPrimitive.content
            if (exe !in approvedExecutables) {
                throw BrokerToolException(
                    ToolResultStatus.DENIED,
                    ErrorCode.DENIED,
                    "Executable '$exe' is not in approved allowlist (${approvedExecutables.joinToString()})"
                )
            }
            val args = req.arguments["args"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val timeout = (req.arguments["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 20_000).toLong()
            val out = launcher.run(
                ProcessSpec(
                    executable = exe,
                    args = args,
                    workingDir = pathGuard.rootRealPath.toString(),
                    envAllowlist = envAllowlist,
                    timeoutMs = timeout
                )
            )
            buildJsonObject {
                put("exitCode", JsonPrimitive(out.exitCode))
                put("stdout", JsonPrimitive(out.stdout))
                put("stderr", JsonPrimitive(out.stderr))
                put("timedOut", JsonPrimitive(out.timedOut))
            }
        }
    }
}
