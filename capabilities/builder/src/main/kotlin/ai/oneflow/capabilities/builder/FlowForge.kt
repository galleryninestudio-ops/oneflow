package ai.oneflow.capabilities.builder

import ai.oneflow.core.contracts.RiskTier
import kotlinx.serialization.Serializable

@Serializable
data class StaticScanFinding(
    val ruleId: String,
    val severity: String,
    val message: String
)

@Serializable
data class StaticScanResult(
    val passed: Boolean,
    val findings: List<StaticScanFinding>
)

@Serializable
data class FlowForgeAdapterRecord(
    val adapterId: String,
    val version: Int,
    val riskTier: RiskTier,
    val requiredCapabilities: Set<String>,
    val sourceCode: String,
    val active: Boolean = true
)

/**
 * Static security & sandbox gate for FlowForge-generated adapters before registration.
 */
object FlowForgeStaticScanner {
    private val forbiddenPatterns = listOf(
        "FORGE_NO_EXEC" to Regex("""\b(ProcessBuilder|Runtime\.getRuntime\(\)\.exec|child_process)\b"""),
        "FORGE_NO_DYNAMIC_EVAL" to Regex("""\b(eval\s*\(|new\s+Function\s*\()"""),
        "FORGE_NO_RAW_SOCKET" to Regex("""\b(java\.net\.Socket|ServerSocket|net\.createServer)\b"""),
        "FORGE_NO_ANDROID_IN_PURE" to Regex("""import\s+android\."""),
        "FORGE_NO_CLEARTEXT_HTTP" to Regex("""http://(?!127\.0\.0\.1|localhost)""")
    )

    fun scan(sourceCode: String): StaticScanResult {
        if (sourceCode.isBlank()) {
            return StaticScanResult(
                passed = false,
                findings = listOf(StaticScanFinding("FORGE_EMPTY", "ERROR", "Adapter source cannot be blank"))
            )
        }
        val findings = mutableListOf<StaticScanFinding>()
        for ((ruleId, regex) in forbiddenPatterns) {
            if (regex.containsMatchIn(sourceCode)) {
                findings.add(
                    StaticScanFinding(
                        ruleId = ruleId,
                        severity = "ERROR",
                        message = "Forbidden construct matched rule $ruleId"
                    )
                )
            }
        }
        return StaticScanResult(passed = findings.isEmpty(), findings = findings)
    }
}

sealed interface ForgeRegisterResult {
    data class Registered(val record: FlowForgeAdapterRecord) : ForgeRegisterResult
    data class Rejected(val findings: List<StaticScanFinding>) : ForgeRegisterResult
}

/**
 * Versioned adapter registry with quarantine-first static scanning and rollback.
 */
class FlowForgeRegistry {
    private val history = linkedMapOf<String, MutableList<FlowForgeAdapterRecord>>()

    fun register(
        adapterId: String,
        sourceCode: String,
        riskTier: RiskTier = RiskTier.READ_ONLY,
        requiredCapabilities: Set<String> = emptySet()
    ): ForgeRegisterResult {
        val scan = FlowForgeStaticScanner.scan(sourceCode)
        if (!scan.passed) {
            return ForgeRegisterResult.Rejected(scan.findings)
        }
        val versions = history.getOrPut(adapterId) { mutableListOf() }
        versions.replaceAll { it.copy(active = false) }
        val nextVersion = (versions.maxOfOrNull { it.version } ?: 0) + 1
        val rec = FlowForgeAdapterRecord(
            adapterId = adapterId,
            version = nextVersion,
            riskTier = riskTier,
            requiredCapabilities = requiredCapabilities,
            sourceCode = sourceCode,
            active = true
        )
        versions.add(rec)
        return ForgeRegisterResult.Registered(rec)
    }

    fun activeAdapter(adapterId: String): FlowForgeAdapterRecord? =
        history[adapterId]?.firstOrNull { it.active }

    fun rollback(adapterId: String): FlowForgeAdapterRecord? {
        val versions = history[adapterId] ?: return null
        if (versions.size < 2) return null
        val currentIdx = versions.indexOfFirst { it.active }
        if (currentIdx <= 0) return null
        versions[currentIdx] = versions[currentIdx].copy(active = false)
        val prev = versions[currentIdx - 1].copy(active = true)
        versions[currentIdx - 1] = prev
        return prev
    }

    fun listAll(): List<FlowForgeAdapterRecord> = history.values.flatten()
}
