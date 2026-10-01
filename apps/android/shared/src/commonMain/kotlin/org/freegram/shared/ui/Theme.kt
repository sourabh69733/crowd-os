package org.freegram.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colours from the approved mockup: teal for the internet path, amber for nearby and offline. */
@Immutable
data class FreegramColors(
    val paper: Color, val surface: Color, val surface2: Color, val line: Color,
    val ink: Color, val ink2: Color, val ink3: Color,
    val teal: Color, val tealSoft: Color, val onTeal: Color,
    val amber: Color, val amberSoft: Color, val red: Color, val redSoft: Color,
)

private val Light = FreegramColors(
    paper = Color(0xFFEDF1EF), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF4F7F6), line = Color(0xFFD3DCD9),
    ink = Color(0xFF16211E), ink2 = Color(0xFF4A5955), ink3 = Color(0xFF75847F),
    teal = Color(0xFF0E7A6B), tealSoft = Color(0xFFD6ECE8), onTeal = Color.White,
    amber = Color(0xFFB87803), amberSoft = Color(0xFFF7EAD0), red = Color(0xFFB83A2A), redSoft = Color(0xFFF6DFDB),
)

private val Dark = FreegramColors(
    paper = Color(0xFF111816), surface = Color(0xFF18221F), surface2 = Color(0xFF1E2A27), line = Color(0xFF2C3A36),
    ink = Color(0xFFE4ECEA), ink2 = Color(0xFFA9B8B4), ink3 = Color(0xFF7D8D88),
    teal = Color(0xFF3FBFA9), tealSoft = Color(0xFF173A34), onTeal = Color(0xFF06231E),
    amber = Color(0xFFF0B43C), amberSoft = Color(0xFF3A2C10), red = Color(0xFFEF7B6A), redSoft = Color(0xFF3D1F1A),
)

val LocalFreegramColors = staticCompositionLocalOf { Light }

@Composable
fun FreegramTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(primary = c.teal, onPrimary = c.onTeal, background = c.paper, surface = c.surface, onSurface = c.ink,
            onBackground = c.ink, surfaceVariant = c.surface2, outline = c.line, error = c.red, secondary = c.amber)
    } else {
        lightColorScheme(primary = c.teal, onPrimary = c.onTeal, background = c.paper, surface = c.surface, onSurface = c.ink,
            onBackground = c.ink, surfaceVariant = c.surface2, outline = c.line, error = c.red, secondary = c.amber)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalFreegramColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object Fg {
    val colors: FreegramColors @Composable get() = LocalFreegramColors.current
}
