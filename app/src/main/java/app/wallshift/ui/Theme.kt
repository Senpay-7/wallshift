package app.wallshift.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Bg = Color(0xFF0F0F12)

private val Colors = darkColorScheme(
    primary = Color(0xFFB8C7FF),
    onPrimary = Color(0xFF0F1B44),
    primaryContainer = Color(0xFF2B3A66),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondaryContainer = Color(0xFF2E3242),
    onSecondaryContainer = Color(0xFFDCE1F5),
    background = Bg,
    onBackground = Color(0xFFE4E4EA),
    surface = Bg,
    onSurface = Color(0xFFE4E4EA),
    surfaceVariant = Color(0xFF22232A),
    onSurfaceVariant = Color(0xFFB4B6C2),
    surfaceContainer = Color(0xFF18191E),
    surfaceContainerHigh = Color(0xFF1F2027),
    surfaceContainerHighest = Color(0xFF26272F),
    outline = Color(0xFF4A4C58),
    outlineVariant = Color(0xFF33343D),
)

/** Always dark. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
