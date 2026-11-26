package ai.oneflow.app.ui

import ai.oneflow.app.theme.OneflowColors
import ai.oneflow.capabilities.files.WorkspacePathGuard
import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.nio.file.Files

/**
 * Restricted WebView preview for workspace-generated HTML/CSS artifacts (§10.4, Phase 2).
 * - Isolated synthetic origin (`https://oneflow-preview.invalid/`)
 * - Serves ONLY files contained within WorkspacePathGuard
 * - JavaScript disabled, file/content access disabled, zero addJavascriptInterface
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RestrictedWorkspacePreviewCard(
    pathGuard: WorkspacePathGuard,
    entryRelativePath: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
        border = BorderStroke(1.dp, OneflowColors.Success),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text("Restricted Artifact Preview", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                    Text("Origin: https://oneflow-preview.invalid/$entryRelativePath (JS off)", style = MaterialTheme.typography.labelSmall, color = OneflowColors.TextSecondary)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.domStorageEnabled = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url ?: return blockedResponse()
                                if (url.host != "oneflow-preview.invalid") {
                                    return blockedResponse()
                                }
                                val rel = url.path?.trimStart('/').orEmpty().ifBlank { entryRelativePath }
                                return try {
                                    val target = pathGuard.resolveContained(rel)
                                    if (!Files.exists(target) || !Files.isRegularFile(target)) {
                                        blockedResponse()
                                    } else {
                                        val bytes = Files.readAllBytes(target)
                                        val mime = when {
                                            rel.endsWith(".html", true) -> "text/html"
                                            rel.endsWith(".css", true) -> "text/css"
                                            rel.endsWith(".svg", true) -> "image/svg+xml"
                                            else -> "text/plain"
                                        }
                                        WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes))
                                    }
                                } catch (_: SecurityException) {
                                    blockedResponse()
                                }
                            }

                            private fun blockedResponse(): WebResourceResponse =
                                WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    403,
                                    "Forbidden",
                                    emptyMap(),
                                    ByteArrayInputStream("Blocked by Oneflow preview isolation".toByteArray())
                                )
                        }
                        loadUrl("https://oneflow-preview.invalid/$entryRelativePath")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            )
        }
    }
}
