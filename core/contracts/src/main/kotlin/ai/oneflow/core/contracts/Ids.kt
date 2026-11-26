package ai.oneflow.core.contracts

import kotlinx.serialization.Serializable

@Serializable @JvmInline value class MissionId(val value: String) {
    init { require(value.isNotBlank()) { "MissionId cannot be blank" } }
}

@Serializable @JvmInline value class WorkspaceId(val value: String) {
    init { require(value.isNotBlank()) { "WorkspaceId cannot be blank" } }
}

@Serializable @JvmInline value class TaskId(val value: String) {
    init { require(value.isNotBlank()) { "TaskId cannot be blank" } }
}

@Serializable @JvmInline value class AgentId(val value: String) {
    init { require(value.isNotBlank()) { "AgentId cannot be blank" } }
}

@Serializable @JvmInline value class EventId(val value: String) {
    init { require(value.isNotBlank()) { "EventId cannot be blank" } }
}

@Serializable @JvmInline value class ToolRequestId(val value: String) {
    init { require(value.isNotBlank()) { "ToolRequestId cannot be blank" } }
}

@Serializable @JvmInline value class ArtifactId(val value: String) {
    init { require(value.isNotBlank()) { "ArtifactId cannot be blank" } }
}

@Serializable @JvmInline value class ProviderId(val value: String) {
    init { require(value.isNotBlank()) { "ProviderId cannot be blank" } }
}

@Serializable @JvmInline value class ModelId(val value: String) {
    init { require(value.isNotBlank()) { "ModelId cannot be blank" } }
}

@Serializable @JvmInline value class CorrelationId(val value: String) {
    init { require(value.isNotBlank()) { "CorrelationId cannot be blank" } }
}
