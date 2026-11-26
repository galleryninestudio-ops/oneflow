package ai.oneflow.capabilities.research

import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.FlowError
import kotlinx.serialization.Serializable

@Serializable
data class BrowserElementRef(
    val refId: String,
    val role: String,
    val label: String,
    val selector: String,
    val isSensitiveInput: Boolean = false
)

@Serializable
data class DomSnapshot(
    val spaceId: String,
    val pageId: String,
    val generation: Long,
    val url: String,
    val title: String,
    val textSummary: String,
    val elements: List<BrowserElementRef>
)

sealed interface RefResolution {
    data class Valid(val element: BrowserElementRef) : RefResolution
    data class Stale(val error: FlowError) : RefResolution
}

/**
 * Ego-inspired Browser Space & Snapshot Ref Registry (§10.4).
 * Enforces snapshot generation checks so acting on stale DOM refs returns STALE_STATE.
 */
class BrowserRefRegistry {
    private val latestSnapshots = mutableMapOf<String, DomSnapshot>()

    @Synchronized
    fun recordSnapshot(snapshot: DomSnapshot) {
        latestSnapshots["${snapshot.spaceId}:${snapshot.pageId}"] = snapshot
    }

    @Synchronized
    fun resolveForAction(
        spaceId: String,
        pageId: String,
        expectedGeneration: Long,
        refId: String
    ): RefResolution {
        val current = latestSnapshots["$spaceId:$pageId"]
            ?: return RefResolution.Stale(FlowError(ErrorCode.STALE_STATE, "No snapshot recorded for $spaceId:$pageId"))
        if (current.generation != expectedGeneration) {
            return RefResolution.Stale(
                FlowError(
                    ErrorCode.STALE_STATE,
                    "DOM snapshot generation mismatch: expected=$expectedGeneration actual=${current.generation}"
                )
            )
        }
        val el = current.elements.firstOrNull { it.refId == refId }
            ?: return RefResolution.Stale(FlowError(ErrorCode.STALE_STATE, "Element ref '$refId' not in generation $expectedGeneration"))
        return RefResolution.Valid(el)
    }
}
