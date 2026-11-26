package ai.oneflow.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object OneflowColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF141414)
    val SurfaceElevated = Color(0xFF1B1B1B)
    val Border = Color(0xFF303030)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA0A0A0)
    val TextMuted = Color(0xFF707070)
    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFF87171)
}

private val OneflowColorScheme = darkColorScheme(
    primary = OneflowColors.TextPrimary,
    onPrimary = OneflowColors.Background,
    background = OneflowColors.Background,
    onBackground = OneflowColors.TextPrimary,
    surface = OneflowColors.Surface,
    onSurface = OneflowColors.TextPrimary,
    surfaceVariant = OneflowColors.SurfaceElevated,
    onSurfaceVariant = OneflowColors.TextSecondary,
    outline = OneflowColors.Border,
    error = OneflowColors.Error
)

@Composable
fun OneflowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OneflowColorScheme,
        content = content
    )
}
