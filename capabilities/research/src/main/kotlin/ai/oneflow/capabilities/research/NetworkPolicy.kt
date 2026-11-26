package ai.oneflow.capabilities.research

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

sealed interface UrlPolicyCheck {
    data class Allowed(val uri: URI) : UrlPolicyCheck
    data class Blocked(val reason: String) : UrlPolicyCheck
}

/**
 * Validates outbound HTTP/HTTPS URLs against SSRF (§10.2):
 * - Allows only http/https
 * - Resolves DNS and blocks loopback, RFC1918 site-local, link-local (169.254.x.x),
 *   multicast, any-local, and IPv6 ULA (fc00::/7)
 * - Optional `allowedLocalPorts` permits explicitly authorized local dev/test targets only.
 */
class SsrfPolicyValidator(
    private val allowedLocalPorts: Set<Int> = emptySet(),
    private val dnsResolver: (String) -> Array<InetAddress> = { host -> InetAddress.getAllByName(host) }
) {
    fun validate(rawUrl: String): UrlPolicyCheck {
        val uri = try {
            URI(rawUrl.trim())
        } catch (e: Exception) {
            return UrlPolicyCheck.Blocked("Malformed URL: ${e.message}")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlPolicyCheck.Blocked("Unsupported URL scheme '$scheme' (only http/https permitted)")
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return UrlPolicyCheck.Blocked("Missing host in URL")

        val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
        val addresses = try {
            dnsResolver(host)
        } catch (e: Exception) {
            return UrlPolicyCheck.Blocked("DNS resolution failed for '$host': ${e.message}")
        }
        if (addresses.isEmpty()) {
            return UrlPolicyCheck.Blocked("No DNS addresses for '$host'")
        }

        for (addr in addresses) {
            if (isPrivateOrReserved(addr)) {
                val isAuthorizedLocal = addr.isLoopbackAddress && port in allowedLocalPorts
                if (!isAuthorizedLocal) {
                    return UrlPolicyCheck.Blocked("SSRF policy blocked non-public address ${addr.hostAddress} for host '$host'")
                }
            }
        }
        return UrlPolicyCheck.Allowed(uri)
    }

    private fun isPrivateOrReserved(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isAnyLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        val bytes = addr.address
        if (addr is Inet6Address && bytes.size == 16) {
            // fc00::/7 Unique Local Addresses
            val firstByte = bytes[0].toInt() and 0xFE
            if (firstByte == 0xFC) return true
        }
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // 100.64.0.0/10 CGNAT
            if (b0 == 100 && b1 in 64..127) return true
            // 0.0.0.0/8
            if (b0 == 0) return true
        }
        return false
    }
}

enum class SourceHealthState {
    HEALTHY,
    DEGRADED,
    OPEN
}

class SourceCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 30_000L,
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class State(
        var failures: Int = 0,
        var openedAtMs: Long = 0L
    )

    private val states = ConcurrentHashMap<String, State>()

    fun allowRequest(sourceId: String): Boolean {
        val st = states[sourceId] ?: return true
        if (st.failures < failureThreshold) return true
        val elapsed = clockEpochMs() - st.openedAtMs
        return elapsed >= openDurationMs
    }

    fun recordSuccess(sourceId: String) {
        states.remove(sourceId)
    }

    fun recordFailure(sourceId: String) {
        val st = states.getOrPut(sourceId) { State() }
        st.failures += 1
        if (st.failures >= failureThreshold) {
            st.openedAtMs = clockEpochMs()
        }
    }

    fun health(sourceId: String): SourceHealthState {
        val st = states[sourceId] ?: return SourceHealthState.HEALTHY
        return when {
            st.failures >= failureThreshold && !allowRequest(sourceId) -> SourceHealthState.OPEN
            st.failures > 0 -> SourceHealthState.DEGRADED
            else -> SourceHealthState.HEALTHY
        }
    }
}
