package ai.oneflow.core.intelligence

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class FlowLearnProposal(
    val proposalId: String,
    val title: String,
    val workflowSummary: String,
    val baselineScore: Double,
    val candidateScore: Double,
    val evalPassed: Boolean,
    val approvedByUser: Boolean = false,
    val active: Boolean = false
)

sealed interface FlowLearnPromoteResult {
    data class Activated(val proposal: FlowLearnProposal) : FlowLearnPromoteResult
    data class Rejected(val reason: String) : FlowLearnPromoteResult
}

/**
 * Evaluation-gated workflow learning engine:
 * Proposals can only be promoted after passing evaluation (candidateScore >= baselineScore)
 * and receiving explicit user approval.
 */
class FlowLearnEngine {
    private val proposals = ConcurrentHashMap<String, FlowLearnProposal>()

    fun submitProposal(
        proposalId: String,
        title: String,
        workflowSummary: String,
        baselineScore: Double,
        candidateScore: Double
    ): FlowLearnProposal {
        val evalPassed = candidateScore >= baselineScore && candidateScore >= 0.70
        val p = FlowLearnProposal(
            proposalId = proposalId,
            title = title,
            workflowSummary = workflowSummary,
            baselineScore = baselineScore,
            candidateScore = candidateScore,
            evalPassed = evalPassed
        )
        proposals[proposalId] = p
        return p
    }

    fun approveAndPromote(proposalId: String, userApproved: Boolean): FlowLearnPromoteResult {
        val p = proposals[proposalId] ?: return FlowLearnPromoteResult.Rejected("Unknown proposal '$proposalId'")
        if (!p.evalPassed) {
            return FlowLearnPromoteResult.Rejected(
                "Evaluation gate failed (candidate=${p.candidateScore}, baseline=${p.baselineScore})"
            )
        }
        if (!userApproved) {
            return FlowLearnPromoteResult.Rejected("Explicit user approval required before activating learned workflow")
        }
        val active = p.copy(approvedByUser = true, active = true)
        proposals[proposalId] = active
        return FlowLearnPromoteResult.Activated(active)
    }

    fun listProposals(): List<FlowLearnProposal> = proposals.values.sortedBy { it.proposalId }
}

/**
 * Remote & interoperability contracts (FlowConnect / FlowSync / MCP Server / ACP / A2A).
 * All disabled by default unless explicitly configured & paired.
 */
@Serializable
data class RemoteEndpointPolicy(
    val serviceName: String,
    val enabled: Boolean = false,
    val pairedPeerId: String? = null,
    val authTokenSha256: String? = null,
    val allowedOrigins: Set<String> = emptySet()
) {
    fun isReadyForConnection(origin: String?, tokenSha256: String?): Boolean {
        if (!enabled) return false
        if (pairedPeerId.isNullOrBlank() || authTokenSha256.isNullOrBlank()) return false
        if (tokenSha256 != authTokenSha256) return false
        if (allowedOrigins.isNotEmpty() && (origin == null || origin !in allowedOrigins)) return false
        return true
    }
}

@Serializable
data class RemoteInteropSettings(
    val flowConnect: RemoteEndpointPolicy = RemoteEndpointPolicy("FlowConnect"),
    val flowSync: RemoteEndpointPolicy = RemoteEndpointPolicy("FlowSync"),
    val mcpServer: RemoteEndpointPolicy = RemoteEndpointPolicy("MCPServer"),
    val acpBridge: RemoteEndpointPolicy = RemoteEndpointPolicy("ACP"),
    val a2aBridge: RemoteEndpointPolicy = RemoteEndpointPolicy("A2A")
)
