package com.nyora.windows.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Future Modern 2026 Palette — pure-black AMOLED base
private val primaryColor    = Color(0xFFE63946) // Vibrant Red
private val surfaceColor    = Color(0xFF121216) // Slightly elevated AMOLED (surface1)
private val backgroundColor = Color(0xFF000000) // Pure black AMOLED base

/**
 * The visual appearance modes Nyora supports.
 *
 * - [AMOLED] : pure black (#000000) background + surfaces for OLED panels (default).
 * - [LIGHT]  : a tasteful light scheme.
 */
enum class AppearanceMode { AMOLED, LIGHT }

/**
 * The selectable accent colors. Each case carries its own [Color], used as the
 * Material3 `primary` and surfaced via [LocalNyoraAccent] so the DesignSystem
 * brushes/glows recolor live.
 */
enum class Accent(val color: Color) {
    RED(Color(0xFFE63946)),
    SAKURA(Color(0xFFFF6B81)),
    IRIS(Color(0xFF7C5CFF)),
    MINT(Color(0xFF5CE1C0)),
    ORANGE(Color(0xFFFF8A4C)),
    GOLD(Color(0xFFE9C46A)),
    AZURE(Color(0xFF4CA6FF)),
    VIOLET(Color(0xFFB14CFF)),
}

/**
 * Live accent exposed to the composition so downstream design tokens (gradients,
 * glows) can recolor reactively. Defaults to [Accent.RED].
 */
val LocalNyoraAccent = staticCompositionLocalOf { Accent.RED }

// Retained for backward compatibility — now delegates to the AMOLED values.
fun nyoraDarkColorScheme(accent: Color): ColorScheme = nyoraAmoledColorScheme(accent)

// Pure-black AMOLED scheme, parameterized by accent.
fun nyoraAmoledColorScheme(accent: Color): ColorScheme = darkColorScheme(
    primary         = accent,
    secondary       = Color(0xFFF1FAEE),
    tertiary        = Color(0xFFA8DADC),
    background      = Color(0xFF000000),
    surface         = Color(0xFF000000),
    onPrimary       = Color.White,
    onBackground    = Color(0xFFF1FAEE),
    onSurface       = Color(0xFFF1FAEE),
    surfaceVariant  = Color(0xFF0A0A0A),
)

// Tasteful light scheme, parameterized by accent.
fun nyoraLightColorScheme(accent: Color): ColorScheme = lightColorScheme(
    primary         = accent,
    secondary       = Color(0xFF457B9D),
    tertiary        = Color(0xFF1D3557),
    background      = Color(0xFFFAFAFA),
    surface         = Color(0xFFFFFFFF),
    onPrimary       = Color.White,
    onBackground    = Color(0xFF1A1A1C),
    onSurface       = Color(0xFF1A1A1C),
    surfaceVariant  = Color(0xFFEDEDF0),
)

/**
 * Backward-compatible dark scheme using the default [Accent.RED]/[primaryColor].
 * Retained so existing references keep compiling.
 */
val NyoraDarkColors: ColorScheme = nyoraDarkColorScheme(primaryColor)

// Hyper-Modern 24px (ROUND_TWELVE) Shapes
val NyoraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun NyoraTheme(
    appearance: AppearanceMode = AppearanceMode.AMOLED,
    accent: Accent = Accent.RED,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (appearance) {
        AppearanceMode.AMOLED -> nyoraAmoledColorScheme(accent.color)
        AppearanceMode.LIGHT  -> nyoraLightColorScheme(accent.color)
    }
    // Push the theme-reactive palette into NyoraTokens so the ~397 token call sites recolor
    // on light/amoled swap. SideEffect runs after a successful composition, so the snapshot
    // writes here are applied outside composition and safely invalidate token readers.
    androidx.compose.runtime.SideEffect {
        println("NYORA-DBG NyoraTheme appearance=$appearance applyLight=${appearance == AppearanceMode.LIGHT}")
        NyoraTokens.applyPalette(if (appearance == AppearanceMode.LIGHT) LightPalette else AmoledPalette)
    }
    CompositionLocalProvider(LocalNyoraAccent provides accent) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes      = NyoraShapes,
            content     = content,
        )
    }
}
