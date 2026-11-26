package ai.oneflow.app.data

import ai.oneflow.app.vault.SecretVault
import ai.oneflow.core.contracts.ModelId
import ai.oneflow.core.contracts.ProviderConfig
import ai.oneflow.core.contracts.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedProviderProfile(
    val config: ProviderConfig,
    val selectedModelId: String = "",
    val isLocalTestFixture: Boolean = false
)

@Serializable
data class ProviderStoreState(
    val activeProviderId: String? = null,
    val profiles: List<SavedProviderProfile> = emptyList(),
    val onboardingCompleted: Boolean = false
)

/**
 * Persists provider metadata (never raw API keys!) to disk and delegates secrets to SecretVault.
 */
class ProviderRepository(
    private val storageFile: File,
    private val vault: SecretVault,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
) {
    @Synchronized
    fun load(): ProviderStoreState {
        if (!storageFile.exists()) return ProviderStoreState()
        return runCatching {
            json.decodeFromString<ProviderStoreState>(storageFile.readStringSafe())
        }.getOrDefault(ProviderStoreState())
    }

    @Synchronized
    fun saveProfile(
        presetId: String,
        displayName: String,
        baseUrl: String,
        modelId: String,
        rawApiKey: String?,
        isLocalTestFixture: Boolean = false
    ): ProviderStoreState {
        val current = load()
        val alias = "provider_$presetId"
        if (!rawApiKey.isNullOrBlank()) {
            vault.putSecret(alias, rawApiKey.trim())
        }
        val config = ProviderConfig(
            id = ProviderId(presetId),
            name = displayName,
            baseUrl = baseUrl.trim().trimEnd('/'),
            keyAlias = alias,
            defaultModel = modelId.trim().takeIf { it.isNotBlank() }?.let(::ModelId)
        )
        val profile = SavedProviderProfile(
            config = config,
            selectedModelId = modelId.trim(),
            isLocalTestFixture = isLocalTestFixture
        )
        val updatedList = current.profiles.filterNot { it.config.id.value == presetId } + profile
        val next = current.copy(
            activeProviderId = presetId,
            profiles = updatedList,
            onboardingCompleted = true
        )
        persist(next)
        return next
    }

    @Synchronized
    fun deleteProfileAndKey(presetId: String): ProviderStoreState {
        val current = load()
        val alias = "provider_$presetId"
        vault.deleteSecret(alias)
        val remaining = current.profiles.filterNot { it.config.id.value == presetId }
        val next = current.copy(
            activeProviderId = remaining.firstOrNull()?.config?.id?.value,
            profiles = remaining
        )
        persist(next)
        return next
    }

    fun activeProfile(): SavedProviderProfile? {
        val state = load()
        return state.profiles.firstOrNull { it.config.id.value == state.activeProviderId }
            ?: state.profiles.firstOrNull()
    }

    fun resolveKey(alias: String): String? = vault.getSecret(alias)

    fun hasStoredKey(alias: String): Boolean = vault.hasSecret(alias)

    private fun persist(state: ProviderStoreState) {
        storageFile.parentFile?.mkdirs()
        val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
        tmp.writeText(json.encodeToString(state), Charsets.UTF_8)
        if (!tmp.renameTo(storageFile)) {
            storageFile.writeText(json.encodeToString(state), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun File.readStringSafe(): String = readText(Charsets.UTF_8)
}
