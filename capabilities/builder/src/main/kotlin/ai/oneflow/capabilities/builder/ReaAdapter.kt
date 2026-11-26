package ai.oneflow.capabilities.builder

import ai.oneflow.platform.runtime.FlowRuntimeLauncher
import ai.oneflow.platform.runtime.ProcessSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path

@Serializable
data class ReaBackendStatus(
    val reaPackage: String = "rea-agents@6.10.1",
    val cliAvailable: Boolean,
    val missingEngines: List<String> = emptyList(),
    val note: String
)

@Serializable
data class ReaArtifactNode(
    val id: String,
    val kind: String,
    val label: String,
    val confidence: Double,
    val evidenceRef: String
)

@Serializable
data class ReaArtifactEdge(
    val fromId: String,
    val toId: String,
    val relation: String
)

@Serializable
data class ReaArtifactGraph(
    val targetName: String,
    val nodes: List<ReaArtifactNode>,
    val edges: List<ReaArtifactEdge>
)

/**
 * Adapter for morluto/rea (`rea-agents@6.10.1`).
 * Never fabricates binary analysis results when external engines (ghidra, jadx, ida, node) are absent.
 */
class ReaAdapter(
    private val launcher: FlowRuntimeLauncher = FlowRuntimeLauncher(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun probeCapabilities(workingDir: Path, npxPath: String? = null): ReaBackendStatus {
        val resolvedNpx = (npxPath ?: listOf("/usr/bin/npx", "/usr/local/bin/npx")
            .firstOrNull { java.io.File(it).canExecute() })
        if (resolvedNpx == null || !java.io.File(resolvedNpx).canExecute()) {
            return ReaBackendStatus(
                cliAvailable = false,
                missingEngines = listOf("node", "npx", "ghidra", "jadx", "ida"),
                note = "rea-agents@6.10.1 unavailable in current runtime (missing npx/node)"
            )
        }
        val res = launcher.run(
            ProcessSpec(
                executable = resolvedNpx,
                args = listOf("--yes", "rea-agents@6.10.1", "capabilities", "--json"),
                workingDir = workingDir.toString(),
                timeoutMs = 15_000L
            )
        )
        return if (res.exitCode == 0) {
            ReaBackendStatus(
                cliAvailable = true,
                missingEngines = detectMissingEngines(res.stdout),
                note = "rea-agents@6.10.1 capabilities probed"
            )
        } else {
            ReaBackendStatus(
                cliAvailable = false,
                missingEngines = listOf("ghidra", "jadx", "ida"),
                note = "rea-agents@6.10.1 probe exited ${res.exitCode}: ${res.stderr.take(160)}"
            )
        }
    }

    fun buildArtifactGraphJson(graph: ReaArtifactGraph): JsonObject = buildJsonObject {
        put("targetName", JsonPrimitive(graph.targetName))
        put("nodeCount", JsonPrimitive(graph.nodes.size))
        put("edgeCount", JsonPrimitive(graph.edges.size))
    }

    private fun detectMissingEngines(raw: String): List<String> {
        val missing = mutableListOf<String>()
        for (engine in listOf("ghidra", "jadx", "ida", "kuna")) {
            if (raw.contains(engine, ignoreCase = true) && raw.contains("missing", ignoreCase = true)) {
                missing.add(engine)
            }
        }
        return missing
    }
}
