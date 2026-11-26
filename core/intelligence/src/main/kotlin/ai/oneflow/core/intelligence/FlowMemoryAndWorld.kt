package ai.oneflow.core.intelligence

import ai.oneflow.core.contracts.WorkspaceId
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class MemoryScope {
    MISSION,
    WORKSPACE,
    USER_PREFERENCE,
    RESEARCH
}

@Serializable
data class MemoryRecord(
    val id: String,
    val scope: MemoryScope,
    val workspaceId: WorkspaceId?,
    val key: String,
    val value: String,
    val isHypothesis: Boolean = false,
    val sourceEvidence: String,
    val confidence: Double = 1.0,
    val verified: Boolean = false,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
    val supersededBy: String? = null
)

/**
 * Scoped memory store enforcing strict cross-workspace isolation and supersession (§8.4).
 */
class ScopedMemoryStore(
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    private val records = linkedMapOf<String, MemoryRecord>()

    @Synchronized
    fun put(
        scope: MemoryScope,
        workspaceId: WorkspaceId?,
        key: String,
        value: String,
        sourceEvidence: String,
        isHypothesis: Boolean = false,
        verified: Boolean = false,
        ttlMs: Long? = null
    ): MemoryRecord {
        if (scope == MemoryScope.WORKSPACE || scope == MemoryScope.MISSION) {
            require(workspaceId != null) { "$scope memory requires a non-null workspaceId" }
        }
        val now = clockEpochMs()
        val id = "mem-${UUID.randomUUID()}"

        // Supersede prior active record with same (scope, workspaceId, key)
        for ((existingId, existing) in records.entries) {
            if (existing.supersededBy == null &&
                existing.scope == scope &&
                existing.workspaceId == workspaceId &&
                existing.key == key
            ) {
                records[existingId] = existing.copy(supersededBy = id)
            }
        }

        val rec = MemoryRecord(
            id = id,
            scope = scope,
            workspaceId = workspaceId,
            key = key,
            value = value,
            isHypothesis = isHypothesis,
            sourceEvidence = sourceEvidence,
            verified = verified,
            createdAtEpochMs = now,
            expiresAtEpochMs = ttlMs?.let { now + it }
        )
        records[id] = rec
        return rec
    }

    @Synchronized
    fun query(workspaceId: WorkspaceId, queryText: String = "", includeHypotheses: Boolean = true): List<MemoryRecord> {
        val now = clockEpochMs()
        val q = queryText.trim().lowercase()
        return records.values.filter { r ->
            val scopeVisible = when (r.scope) {
                MemoryScope.WORKSPACE, MemoryScope.MISSION -> r.workspaceId == workspaceId
                MemoryScope.USER_PREFERENCE, MemoryScope.RESEARCH -> true
            }
            val notSuperseded = r.supersededBy == null
            val notExpired = r.expiresAtEpochMs == null || r.expiresAtEpochMs > now
            val hypOk = includeHypotheses || !r.isHypothesis
            val textOk = q.isEmpty() || r.key.lowercase().contains(q) || r.value.lowercase().contains(q)
            scopeVisible && notSuperseded && notExpired && hypOk && textOk
        }
    }

    @Synchronized
    fun delete(id: String): Boolean = records.remove(id) != null
}

@Serializable
data class WorldObservation(
    val key: String,
    val source: String,
    val value: String,
    val observedAtEpochMs: Long,
    val ttlMs: Long
) {
    fun isFresh(nowEpochMs: Long): Boolean = (nowEpochMs - observedAtEpochMs) <= ttlMs
}

class FlowWorld(
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    private val observations = linkedMapOf<String, WorldObservation>()

    @Synchronized
    fun observe(key: String, source: String, value: String, ttlMs: Long = 30_000L): WorldObservation {
        val obs = WorldObservation(key, source, value, clockEpochMs(), ttlMs)
        observations[key] = obs
        return obs
    }

    @Synchronized
    fun getFresh(key: String): WorldObservation? {
        val obs = observations[key] ?: return null
        return if (obs.isFresh(clockEpochMs())) obs else null
    }

    @Synchronized
    fun invalidate(key: String) {
        observations.remove(key)
    }

    @Synchronized
    fun invalidatePrefix(prefix: String) {
        val keys = observations.keys.filter { it.startsWith(prefix) }
        keys.forEach { observations.remove(it) }
    }
}
