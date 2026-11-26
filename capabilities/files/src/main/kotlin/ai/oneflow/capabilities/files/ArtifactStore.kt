package ai.oneflow.capabilities.files

import ai.oneflow.core.contracts.ArtifactId
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
data class ArtifactRecord(
    val id: ArtifactId,
    val sha256: String,
    val byteSize: Long,
    val mimeType: String,
    val preview: String,
    val createdAtEpochMs: Long
)

/**
 * Scoped content-addressed artifact store (§6.2, §15.1).
 */
class ArtifactStore(
    private val storeDir: Path,
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    init {
        Files.createDirectories(storeDir)
    }

    fun putText(
        content: String,
        mimeType: String = "text/plain",
        previewChars: Int = 240
    ): ArtifactRecord = putBytes(content.toByteArray(Charsets.UTF_8), mimeType, content.take(previewChars))

    fun putBytes(
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
        preview: String = "[binary ${bytes.size} bytes]"
    ): ArtifactRecord {
        val sha = sha256Hex(bytes)
        val blobPath = blobPathFor(sha)
        if (!Files.exists(blobPath)) {
            val tmp = storeDir.resolve("$sha.blob.tmp")
            Files.write(tmp, bytes)
            Files.move(tmp, blobPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return ArtifactRecord(
            id = ArtifactId("sha256-$sha"),
            sha256 = sha,
            byteSize = bytes.size.toLong(),
            mimeType = mimeType,
            preview = preview,
            createdAtEpochMs = clockEpochMs()
        )
    }

    fun readBytes(id: ArtifactId): ByteArray? {
        val sha = id.value.removePrefix("sha256-")
        if (!sha.matches(Regex("^[0-9a-f]{64}$"))) return null
        val path = blobPathFor(sha)
        return if (Files.exists(path)) Files.readAllBytes(path) else null
    }

    fun readText(id: ArtifactId): String? = readBytes(id)?.toString(Charsets.UTF_8)

    private fun blobPathFor(sha256: String): Path = storeDir.resolve("$sha256.blob")

    companion object {
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
