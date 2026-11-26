package ai.oneflow.platform.runtime

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class WorkspaceEnvironmentManifest(
    val projectTypes: List<String>,
    val gitBranch: String?,
    val hasAgentsMd: Boolean,
    val suggestedCommands: List<String>,
    val detectedFiles: List<String>
)

/**
 * Inspects a workspace without executing untrusted build scripts (§9.5).
 */
object FlowEnvironmentDetector {
    fun detect(workspaceRoot: Path): WorkspaceEnvironmentManifest {
        val types = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val keyFiles = mutableListOf<String>()

        fun exists(name: String): Boolean {
            val ok = Files.exists(workspaceRoot.resolve(name))
            if (ok) keyFiles += name
            return ok
        }

        if (exists("settings.gradle.kts") || exists("settings.gradle") || exists("build.gradle.kts")) {
            types += "gradle-kotlin"
            commands += "./gradlew test --console=plain"
        }
        if (exists("package.json")) {
            types += "node"
            commands += if (exists("pnpm-lock.yaml")) "pnpm test" else "npm test"
        }
        if (exists("pyproject.toml") || exists("requirements.txt")) {
            types += "python"
            commands += "python3 -m unittest"
        }
        if (exists("Cargo.toml")) {
            types += "rust"
            commands += "cargo test"
        }
        if (exists("go.mod")) {
            types += "go"
            commands += "go test ./..."
        }
        if (exists("CMakeLists.txt")) {
            types += "cmake-c-cpp"
            commands += "cmake --build build"
        }

        val gitHead = workspaceRoot.resolve(".git/HEAD")
        val branch = if (Files.exists(gitHead)) {
            val raw = Files.readString(gitHead).trim()
            if (raw.startsWith("ref: refs/heads/")) raw.removePrefix("ref: refs/heads/") else raw.take(12)
        } else null

        val hasAgents = exists("AGENTS.md")

        return WorkspaceEnvironmentManifest(
            projectTypes = types.ifEmpty { listOf("generic") },
            gitBranch = branch,
            hasAgentsMd = hasAgents,
            suggestedCommands = commands,
            detectedFiles = keyFiles
        )
    }
}
