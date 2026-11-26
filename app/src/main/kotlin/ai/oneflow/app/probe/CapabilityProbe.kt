package ai.oneflow.app.probe

import ai.oneflow.core.contracts.FeatureFlags
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

data class CapabilityItem(
    val name: String,
    val status: String,
    val detail: String
)

data class Phase0Diagnostics(
    val appId: String,
    val sdkInt: Int,
    val abis: String,
    val webViewInfo: String,
    val flags: FeatureFlags,
    val capabilities: List<CapabilityItem>
)

object CapabilityProbe {
    fun inspect(context: Context): Phase0Diagnostics {
        val flags = FeatureFlags(flowcodeQuickJsEnabled = true)
        val webViewPkg = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        val multiProfile = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        }.getOrDefault(false)

        val shizukuInstalled = isPackageInstalled(context, "moe.shizuku.privileged.api")

        val capabilities = listOf(
            CapabilityItem(
                name = "External Model API (BYO)",
                status = "NOT_CONFIGURED",
                detail = "Requires user-supplied OpenAI-compatible URL & API key. No local AI models."
            ),
            CapabilityItem(
                name = "FlowCode QuickJS Worker",
                status = if (flags.flowcodeQuickJsEnabled) "ENABLED" else "DISABLED_PHASE_0",
                detail = "Gated off in production until Phase 2 sandbox & isolatedProcess IPC gates pass."
            ),
            CapabilityItem(
                name = "Native Agent Shell",
                status = if (flags.agentShellEnabled) "ENABLED" else "DISABLED",
                detail = "No unrestricted shell until OS isolation proven (Phase 4)."
            ),
            CapabilityItem(
                name = "System WebView & Multi-Profile",
                status = if (webViewPkg != null) "AVAILABLE" else "UNAVAILABLE",
                detail = "Pkg=${webViewPkg?.packageName ?: "none"} v${webViewPkg?.versionName ?: "?"}; MULTI_PROFILE=$multiProfile"
            ),
            CapabilityItem(
                name = "Shizuku Manager App",
                status = if (shizukuInstalled) "INSTALLED_GATED" else "NOT_DETECTED",
                detail = "Optional system bridge (Phase 6); disabled at Phase 0 boot."
            ),
            CapabilityItem(
                name = "Accessibility & MediaProjection",
                status = "DISABLED_PHASE_0",
                detail = "Optional consent-gated capabilities (Phase 6); never requested at launch."
            )
        )

        return Phase0Diagnostics(
            appId = context.packageName,
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.joinToString(", "),
            webViewInfo = "${webViewPkg?.packageName ?: "unknown"} (${webViewPkg?.versionName ?: "?"})",
            flags = flags,
            capabilities = capabilities
        )
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
