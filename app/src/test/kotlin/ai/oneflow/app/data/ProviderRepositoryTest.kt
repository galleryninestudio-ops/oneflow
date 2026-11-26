package ai.oneflow.app.data

import ai.oneflow.app.vault.InMemorySecretVault
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ProviderRepositoryTest {

    @Test
    fun `provider repository persists metadata with alias only and never writes raw api key to disk`(@TempDir tempDir: Path) {
        val storeFile = File(tempDir.toFile(), "providers.json")
        val vault = InMemorySecretVault()
        val repo = ProviderRepository(storeFile, vault)

        val secret = "sk-do-not-write-on-disk-xyz987"
        repo.saveProfile(
            presetId = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1/",
            modelId = "gpt-test",
            rawApiKey = secret
        )

        val rawJson = storeFile.readText(Charsets.UTF_8)
        assertFalse(rawJson.contains(secret))
        assertTrue(rawJson.contains("provider_openai"))
        assertEquals(secret, repo.resolveKey("provider_openai"))

        repo.deleteProfileAndKey("openai")
        assertNull(repo.resolveKey("provider_openai"))
        assertTrue(repo.load().profiles.isEmpty())
    }
}
