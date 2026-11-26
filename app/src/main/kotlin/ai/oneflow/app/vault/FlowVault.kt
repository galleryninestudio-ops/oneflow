package ai.oneflow.app.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretVault {
    fun putSecret(alias: String, secret: String)
    fun getSecret(alias: String): String?
    fun hasSecret(alias: String): Boolean
    fun deleteSecret(alias: String)
    fun knownSecrets(): Collection<String>
}

class InMemorySecretVault : SecretVault {
    private val map = linkedMapOf<String, String>()
    override fun putSecret(alias: String, secret: String) {
        map[alias] = secret
    }
    override fun getSecret(alias: String): String? = map[alias]
    override fun hasSecret(alias: String): Boolean = map.containsKey(alias)
    override fun deleteSecret(alias: String) {
        map.remove(alias)
    }
    override fun knownSecrets(): Collection<String> = map.values.toList()
}

/**
 * Android Keystore-backed AES-256-GCM credential vault (§7.5).
 * Stores encrypted blobs under Context.noBackupFilesDir/oneflow_vault/.
 */
class AndroidKeystoreVault(context: Context) : SecretVault {
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "oneflow_vault_master_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private val SAFE_ALIAS = Regex("^[A-Za-z0-9._-]{1,64}$")
    }

    private val vaultDir = File(context.noBackupFilesDir, "oneflow_vault").apply { mkdirs() }

    private fun validateAlias(alias: String): File {
        require(SAFE_ALIAS.matches(alias)) { "Invalid vault alias" }
        return File(vaultDir, "$alias.bin")
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    @Synchronized
    override fun putSecret(alias: String, secret: String) {
        val file = validateAlias(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(1 + iv.size + ciphertext.size)
        payload[0] = iv.size.toByte()
        System.arraycopy(iv, 0, payload, 1, iv.size)
        System.arraycopy(ciphertext, 0, payload, 1 + iv.size, ciphertext.size)

        val tmp = File(vaultDir, "$alias.bin.tmp")
        tmp.writeBytes(payload)
        if (!tmp.renameTo(file)) {
            file.writeBytes(payload)
            tmp.delete()
        }
    }

    @Synchronized
    override fun getSecret(alias: String): String? {
        val file = validateAlias(alias)
        if (!file.exists()) return null
        val payload = file.readBytes()
        if (payload.size < 2) return null
        val ivLen = payload[0].toInt() and 0xFF
        if (payload.size <= 1 + ivLen) return null
        val iv = payload.copyOfRange(1, 1 + ivLen)
        val ciphertext = payload.copyOfRange(1 + ivLen, payload.size)

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    override fun hasSecret(alias: String): Boolean = validateAlias(alias).exists()

    @Synchronized
    override fun deleteSecret(alias: String) {
        validateAlias(alias).delete()
    }

    @Synchronized
    override fun knownSecrets(): Collection<String> {
        return vaultDir.listFiles()
            ?.mapNotNull { file ->
                if (!file.name.endsWith(".bin")) null
                else getSecret(file.name.removeSuffix(".bin"))
            }
            .orEmpty()
    }
}
