package ai.oneflow.core.kernel

import ai.oneflow.core.contracts.LeaseManager
import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.MissionLease
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class InMemoryLeaseManager(
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() }
) : LeaseManager {
    private val mutex = Mutex()
    private val leases = mutableMapOf<MissionId, MissionLease>()
    private val fencingSeq = AtomicLong(0L)

    override suspend fun acquire(missionId: MissionId, holderId: String, ttlMs: Long): MissionLease? = mutex.withLock {
        val now = clockEpochMs()
        val existing = leases[missionId]
        if (existing != null && existing.expiresAtEpochMs > now && existing.holderId != holderId) {
            return@withLock null
        }
        val lease = MissionLease(
            missionId = missionId,
            leaseId = "lease-${UUID.randomUUID()}",
            holderId = holderId,
            fencingToken = fencingSeq.incrementAndGet(),
            acquiredAtEpochMs = now,
            expiresAtEpochMs = now + ttlMs
        )
        leases[missionId] = lease
        lease
    }

    override suspend fun renew(lease: MissionLease, ttlMs: Long): MissionLease? = mutex.withLock {
        val current = leases[lease.missionId] ?: return@withLock null
        if (current.leaseId != lease.leaseId || current.fencingToken != lease.fencingToken) {
            return@withLock null
        }
        val now = clockEpochMs()
        val renewed = current.copy(expiresAtEpochMs = now + ttlMs)
        leases[lease.missionId] = renewed
        renewed
    }

    override suspend fun release(lease: MissionLease) {
        mutex.withLock {
            val current = leases[lease.missionId]
            if (current != null && current.leaseId == lease.leaseId) {
                leases.remove(lease.missionId)
            }
        }
    }

    override suspend fun current(missionId: MissionId): MissionLease? = mutex.withLock {
        val now = clockEpochMs()
        val existing = leases[missionId] ?: return@withLock null
        if (existing.expiresAtEpochMs <= now) {
            leases.remove(missionId)
            null
        } else {
            existing
        }
    }
}
