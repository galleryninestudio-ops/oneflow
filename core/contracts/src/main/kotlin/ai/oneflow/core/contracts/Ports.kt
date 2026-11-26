package ai.oneflow.core.contracts

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

interface ModelProvider {
    suspend fun capabilities(model: ModelId): ModelCapabilities
    suspend fun listModels(): Result<ModelCatalogResult>
    fun generate(request: ModelRequest): Flow<ModelEvent>
}

interface ToolRegistry {
    fun register(manifest: ToolManifest)
    suspend fun discover(query: ToolQuery): List<ToolDefinition>
    fun get(toolId: String, version: String? = null): ToolManifest?
}

@Serializable
data class PolicyDecision(
    val allowed: Boolean,
    val requiresApproval: Boolean = false,
    val reason: String,
    val missingCapabilities: Set<String> = emptySet()
)

interface PolicyEngine {
    fun evaluate(
        manifest: ToolManifest,
        request: ToolRequest,
        missionGrants: Set<String>,
        sessionApprovals: Set<String> = emptySet()
    ): PolicyDecision
}

interface ToolBroker {
    suspend fun invoke(
        request: ToolRequest,
        missionGrants: Set<String>,
        sessionApprovals: Set<String> = emptySet()
    ): ToolResult
}

@Serializable
data class ScriptRequest(
    val missionId: MissionId,
    val script: String,
    val timeoutMs: Long = 10_000L,
    val maxMemoryBytes: Long = 16L * 1024L * 1024L
)

@Serializable
data class ScriptResult(
    val ok: Boolean,
    val outputJson: String? = null,
    val error: FlowError? = null,
    val durationMs: Long = 0L
)

interface ScriptEngine {
    suspend fun execute(request: ScriptRequest): ScriptResult
}

interface MissionStore {
    suspend fun create(mission: MissionStateSnapshot)
    suspend fun load(id: MissionId): MissionStateSnapshot?
    suspend fun listMissions(): List<MissionStateSnapshot>
    suspend fun append(event: MissionEventEnvelope, updatedState: MissionStateSnapshot? = null)
    suspend fun events(id: MissionId): List<MissionEventEnvelope>
}

@Serializable
data class VerificationRequest(
    val mission: MissionStateSnapshot,
    val candidateResponseText: String? = null
)

@Serializable
enum class VerificationStatus {
    PASS,
    NEEDS_WORK,
    BLOCKED
}

@Serializable
data class VerificationResult(
    val status: VerificationStatus,
    val checks: List<AcceptanceCheckResult> = emptyList(),
    val summary: String
)

interface Verifier {
    suspend fun evaluate(request: VerificationRequest): VerificationResult
}

@Serializable
data class FeatureFlags(
    val flowcodeQuickJsEnabled: Boolean = false,
    val agentShellEnabled: Boolean = false,
    val shizukuEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val captureEnabled: Boolean = false,
    val webMcpEnabled: Boolean = false,
    val reaEnabled: Boolean = false
)
