package ai.oneflow.platform.runtime

import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.FlowError
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Serializable
enum class PackageRuntimeKind {
    BIONIC,
    MUSL,
    STATIC,
    JVM,
    REMOTE
}

@Serializable
enum class PackageInstallState {
    DOWNLOADED,
    VERIFIED,
    INSTALLED,
    RUNNABLE,
    UNSUPPORTED
}

@Serializable
data class FlowPkgManifest(
    val id: String,
    val version: String,
    val abi: String,
    val runtime: PackageRuntimeKind,
    val minAndroidSdk: Int = 26,
    val dependencies: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val sha256Hex: String,
    val signatureBase64: String,
    val license: String,
    val installedSizeBytes: Long
) {
    fun signingPayload(): ByteArray =
        "$id|$version|$abi|${runtime.name}|$minAndroidSdk|$sha256Hex|$license|$installedSizeBytes".toByteArray(Charsets.UTF_8)
}

@Serializable
data class InstalledPackageRecord(
    val manifest: FlowPkgManifest,
    val state: PackageInstallState,
    val previousVersion: String? = null,
    val installedAtEpochMs: Long
)

sealed interface PkgResult<out T> {
    data class Ok<T>(val value: T) : PkgResult<T>
    data class Err(val error: FlowError) : PkgResult<Nothing>
}

/**
 * Signed package staging, SHA-256 + ECDSA verification, activation, and rollback (§9.2).
 */
class FlowPkgManager(
    private val packagesRoot: Path,
    private val trustedPublicKey: PublicKey,
    private val deviceAbi: String = "arm64-v8a",
    private val deviceSdk: Int = 35,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
) {
    init {
        Files.createDirectories(packagesRoot)
    }

    companion object {
        fun signManifest(manifestWithoutSig: FlowPkgManifest, privateKey: PrivateKey): FlowPkgManifest {
            val sig = Signature.getInstance("SHA256withECDSA").apply {
                initSign(privateKey)
                update(manifestWithoutSig.signingPayload())
            }.sign()
            return manifestWithoutSig.copy(signatureBase64 = Base64.getEncoder().encodeToString(sig))
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun decodePublicKey(base64X509: String): PublicKey {
            val bytes = Base64.getDecoder().decode(base64X509)
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }
    }

    @Synchronized
    fun verifyAndInstall(
        manifest: FlowPkgManifest,
        payloadBytes: ByteArray,
        smokeTest: (Path) -> Boolean = { true }
    ): PkgResult<InstalledPackageRecord> {
        if (manifest.minAndroidSdk > deviceSdk) {
            return PkgResult.Err(FlowError(ErrorCode.UNSUPPORTED, "Package ${manifest.id} requires SDK ${manifest.minAndroidSdk}, device is $deviceSdk"))
        }
        if (manifest.abi != "any" && manifest.abi != deviceAbi) {
            return PkgResult.Err(FlowError(ErrorCode.UNSUPPORTED, "ABI mismatch: package=${manifest.abi}, device=$deviceAbi"))
        }

        // 1. Verify ECDSA signature
        val sigOk = runCatching {
            val sigBytes = Base64.getDecoder().decode(manifest.signatureBase64)
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(trustedPublicKey)
                update(manifest.signingPayload())
                verify(sigBytes)
            }
        }.getOrDefault(false)
        if (!sigOk) {
            return PkgResult.Err(FlowError(ErrorCode.DENIED, "Invalid package signature for ${manifest.id}@${manifest.version}"))
        }

        // 2. Verify SHA-256 checksum
        val actualSha = sha256Hex(payloadBytes)
        if (!actualSha.equals(manifest.sha256Hex, ignoreCase = true)) {
            return PkgResult.Err(FlowError(ErrorCode.DENIED, "SHA-256 mismatch for ${manifest.id}@${manifest.version}"))
        }

        // 3. Check dependencies
        val missingDeps = manifest.dependencies.filterNot { depId ->
            Files.exists(activeFile(depId))
        }
        if (missingDeps.isNotEmpty()) {
            return PkgResult.Err(FlowError(ErrorCode.INVALID_ARGUMENT, "Missing dependencies: ${missingDeps.joinToString()}"))
        }

        // 4. Stage & run smoke test
        val versionDir = pkgVersionDir(manifest.id, manifest.version)
        Files.createDirectories(versionDir)
        val payloadFile = versionDir.resolve("payload.bin")
        Files.write(payloadFile, payloadBytes)

        val smokePassed = runCatching { smokeTest(payloadFile) }.getOrDefault(false)
        if (!smokePassed) {
            Files.deleteIfExists(payloadFile)
            return PkgResult.Err(FlowError(ErrorCode.FAILED, "Smoke test failed for ${manifest.id}@${manifest.version}"))
        }

        val prev = currentActive(manifest.id)?.manifest?.version
        val record = InstalledPackageRecord(
            manifest = manifest,
            state = PackageInstallState.RUNNABLE,
            previousVersion = prev,
            installedAtEpochMs = System.currentTimeMillis()
        )
        Files.writeString(versionDir.resolve("record.json"), json.encodeToString(record))
        Files.writeString(activeFile(manifest.id), manifest.version)
        return PkgResult.Ok(record)
    }

    @Synchronized
    fun rollback(packageId: String): PkgResult<InstalledPackageRecord> {
        val current = currentActive(packageId)
            ?: return PkgResult.Err(FlowError(ErrorCode.UNAVAILABLE, "Package $packageId is not installed"))
        val prevVer = current.previousVersion
            ?: return PkgResult.Err(FlowError(ErrorCode.UNAVAILABLE, "No previous version available to rollback $packageId"))
        val prevRecordFile = pkgVersionDir(packageId, prevVer).resolve("record.json")
        if (!Files.exists(prevRecordFile)) {
            return PkgResult.Err(FlowError(ErrorCode.UNAVAILABLE, "Previous version $prevVer files missing"))
        }
        Files.writeString(activeFile(packageId), prevVer)
        val restored = json.decodeFromString<InstalledPackageRecord>(Files.readString(prevRecordFile))
        return PkgResult.Ok(restored)
    }

    fun currentActive(packageId: String): InstalledPackageRecord? {
        val active = activeFile(packageId)
        if (!Files.exists(active)) return null
        val ver = Files.readString(active).trim()
        val recFile = pkgVersionDir(packageId, ver).resolve("record.json")
        if (!Files.exists(recFile)) return null
        return json.decodeFromString<InstalledPackageRecord>(Files.readString(recFile))
    }

    private fun pkgDir(id: String): Path = packagesRoot.resolve(id.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    private fun pkgVersionDir(id: String, ver: String): Path = pkgDir(id).resolve(ver.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    private fun activeFile(id: String): Path = pkgDir(id).resolve("ACTIVE_VERSION")
}
