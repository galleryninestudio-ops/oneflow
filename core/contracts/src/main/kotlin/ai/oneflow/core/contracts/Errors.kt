package ai.oneflow.core.contracts

import kotlinx.serialization.Serializable

@Serializable
enum class ErrorCode {
    UNAVAILABLE,
    INVALID_ARGUMENT,
    DENIED,
    APPROVAL_REQUIRED,
    STALE_STATE,
    TIMEOUT,
    CANCELLED,
    FAILED,
    UNKNOWN_OUTCOME,
    BUDGET_EXCEEDED,
    REVISION_CONFLICT,
    GUARD_TRIGGERED,
    UNSUPPORTED,
    PROVIDER_AUTH,
    PROVIDER_RATE_LIMITED,
    PROVIDER_PROTOCOL_ERROR
}

@Serializable
data class FlowError(
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean = false,
    val details: Map<String, String> = emptyMap()
)

object SecretRedactor {
    private val patterns = listOf(
        Regex("""(?i)(Bearer\s+)[A-Za-z0-9._\-~+/]+=*"""),
        Regex("""(?i)(api[-_]?key["'=:\s]+)[A-Za-z0-9._\-]{8,}"""),
        Regex("""\bsk-[A-Za-z0-9_\-]{12,}\b"""),
        Regex("""\bAIza[0-9A-Za-z\-_]{20,}\b""")
    )

    fun redact(input: String, knownSecrets: Collection<String> = emptyList()): String {
        var out = input
        for (secret in knownSecrets) {
            if (secret.length >= 4 && out.contains(secret)) {
                out = out.replace(secret, "[REDACTED]")
            }
        }
        patterns.forEachIndexed { index, regex ->
            out = if (index <= 1) {
                regex.replace(out) { "${it.groupValues[1]}[REDACTED]" }
            } else {
                regex.replace(out, "[REDACTED]")
            }
        }
        return out
    }
}
