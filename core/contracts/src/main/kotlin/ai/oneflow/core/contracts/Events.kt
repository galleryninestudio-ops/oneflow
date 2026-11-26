package ai.oneflow.core.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class EffectStatus {
    NONE,
    INTENT,
    COMMITTED,
    UNKNOWN,
    FAILED
}

@Serializable
data class MissionEventEnvelope(
    val eventId: EventId,
    val sequence: Long,
    val timestampEpochMs: Long,
    val missionId: MissionId,
    val taskId: TaskId? = null,
    val agentId: AgentId? = null,
    val type: String,
    val correlationId: CorrelationId? = null,
    val causationId: EventId? = null,
    val actorSource: String,
    val schemaVersion: Int = 1,
    val payload: JsonObject = JsonObject(emptyMap()),
    val artifactRef: ArtifactId? = null,
    val redactionApplied: Boolean = true,
    val effectStatus: EffectStatus = EffectStatus.NONE
)
