package ai.oneflow.capabilities.files

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

@Serializable
data class FileWriteUndoRecord(
    val transactionId: String,
    val relativePath: String,
    val existedBefore: Boolean,
    val previousContentBase64: String?,
    val previousSha256: String?,
    val newSha256: String,
    val timestampEpochMs: Long,
    val undone: Boolean = false
)

/**
 * Local write transaction journal supporting atomic undo (§8.2).
 */
class TransactionJournal(
    private val journalDir: Path,
    private val pathGuard: WorkspacePathGuard,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    init {
        Files.createDirectories(journalDir)
    }

    @Synchronized
    fun recordWrite(
        relativePath: String,
        previousBytes: ByteArray?,
        newBytes: ByteArray
    ): FileWriteUndoRecord {
        val txId = "tx-${UUID.randomUUID()}"
        val rec = FileWriteUndoRecord(
            transactionId = txId,
            relativePath = relativePath,
            existedBefore = previousBytes != null,
            previousContentBase64 = previousBytes?.let { Base64.getEncoder().encodeToString(it) },
            previousSha256 = previousBytes?.let { ArtifactStore.sha256Hex(it) },
            newSha256 = ArtifactStore.sha256Hex(newBytes),
            timestampEpochMs = clockEpochMs()
        )
        writeAtomic(journalDir.resolve("$txId.json"), json.encodeToString(rec))
        return rec
    }

    @Synchronized
    fun undo(transactionId: String): Boolean {
        val file = journalDir.resolve("$transactionId.json")
        if (!Files.exists(file)) return false
        val rec = json.decodeFromString<FileWriteUndoRecord>(Files.readString(file))
        if (rec.undone) return false

        val target = pathGuard.resolveContained(rec.relativePath)
        if (!rec.existedBefore) {
            Files.deleteIfExists(target)
        } else {
            val prevBytes = Base64.getDecoder().decode(rec.previousContentBase64.orEmpty())
            Files.createDirectories(target.parent)
            val tmp = target.parent.resolve("${target.fileName}.undo.tmp")
            Files.write(tmp, prevBytes)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        writeAtomic(file, json.encodeToString(rec.copy(undone = true)))
        return true
    }

    fun listRecords(): List<FileWriteUndoRecord> {
        if (!Files.exists(journalDir)) return emptyList()
        return Files.list(journalDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }
                .map { json.decodeFromString<FileWriteUndoRecord>(Files.readString(it)) }
                .toList()
                .sortedByDescending { it.timestampEpochMs }
        }
    }

    private fun writeAtomic(target: Path, content: String) {
        val tmp = target.parent.resolve("${target.fileName}.tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
