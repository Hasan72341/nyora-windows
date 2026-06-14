package com.nyora.windows.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Future Modern 2026 Palette — neutral DARK base
private val primaryColor    = Color(0xFFE63946) // Vibrant Red
private val surfaceColor    = Color(0xFF16161C) // Elevated dark surface (surface1)
private val backgroundColor = Color(0xFF0E0E12) // Neutral dark base

/**
 * The visual appearance modes Nyora supports.
 *
 * - [DARK]  : a tasteful neutral dark scheme (~#0E0E12 background, slightly elevated
 *             surfaces — NOT pure black), the default.
 * - [LIGHT] : a tasteful light scheme.
 */
enum class AppearanceMode { DARK, LIGHT }

/**
 * The shared named Nyora color schemes (ported from nyora-android). Each case carries
 * a [lightPrimary] and a [darkPrimary]; the active [AppearanceMode] selects which one is
 * used as the Material3 `primary` / accent. The scheme is surfaced via [LocalNyoraAccent]
 * so the DesignSystem brushes/glows recolor live.
 *
 * Order matches the canonical spec: [SYSTEM] (Dynamic, the OS/wallpaper accent, default)
 * first, then the named anime schemes.
 */
enum class Accent(
    /** Display name shown beneath the picker preview card. */
    val label: String,
    /** Material primary used in LIGHT appearance. */
    val lightPrimary: Color,
    /** Material primary used in DARK appearance. */
    val darkPrimary: Color,
) {
    SYSTEM ("Dynamic", Color(0xFFE63946), Color(0xFFE63946)),
    TOTORO ("Totoro",  Color(0xFF3C6090), Color(0xFFA6C8FF)),
    MIKU   ("Miku",    Color(0xFF00696D), Color(0xFF6FDDE2)),
    ASUKA  ("Asuka",   Color(0xFF904A40), Color(0xFFFFB4A8)),
    MION   ("Mion",    Color(0xFF3B693A), Color(0xFFA1D39A)),
    RIKKA  ("Rikka",   Color(0xFF68548D), Color(0xFFD3BBFD)),
    SAKURA ("Sakura",  Color(0xFF8C4A60), Color(0xFFFFB1C8)),
    MAMIMI ("Mamimi",  Color(0xFF465D91), Color(0xFFAFC6FF)),
    KANADE ("Kanade",  Color(0xFF353543), Color(0xFFFFFFFF)),
    ITSUKA ("Itsuka",  Color(0xFF974800), Color(0xFFFFBA8F)),
    YUKI   ("Yuki",    Color(0xFF43474A), Color(0xFFFFFFFF));

    /**
     * Resolves the primary for the given [appearance]. [SYSTEM] (Dynamic) follows the
     * live Windows accent regardless of appearance, falling back to a neutral red off
     * Windows or when the registry read fails.
     */
    fun colorFor(appearance: AppearanceMode): Color = when (this) {
        SYSTEM -> WindowsNative.accentColor ?: Color(0xFFE63946)
        else   -> if (appearance == AppearanceMode.LIGHT) lightPrimary else darkPrimary
    }

    /**
     * Backward-compatible non-composable accessor returning the DARK primary (or the live
     * SYSTEM accent). Retained so the ~80 existing `LocalNyoraAccent.current.color` tint
     * sites keep compiling; prefer the appearance-aware [resolvedColor] in new code.
     */
    val color: Color get() = when (this) {
        SYSTEM -> WindowsNative.accentColor ?: Color(0xFFE63946)
        else   -> darkPrimary
    }
}

/**
 * Live scheme exposed to the composition so downstream design tokens (gradients, glows)
 * can recolor reactively. Defaults to [Accent.SYSTEM] (Dynamic).
 */
val LocalNyoraAccent = staticCompositionLocalOf { Accent.SYSTEM }

/**
 * Live appearance exposed to the composition so [resolvedColor] can pick the correct
 * light/dark primary. Defaults to [AppearanceMode.DARK].
 */
val LocalNyoraAppearance = staticCompositionLocalOf { AppearanceMode.DARK }

/** Appearance-aware accent accessor: resolves the scheme's primary for the active appearance. */
@Composable
fun Accent.resolvedColor(): Color = colorFor(LocalNyoraAppearance.current)

// Neutral dark scheme (NOT pure black), parameterized by accent.
fun nyoraDarkColorScheme(accent: Color): ColorScheme = darkColorScheme(
    primary         = accent,
    secondary       = Color(0xFFF1FAEE),
    tertiary        = Color(0xFFA8DADC),
    background      = Color(0xFF0E0E12),
    surface         = Color(0xFF16161C),
    onPrimary       = Color.White,
    onBackground    = Color(0xFFF1FAEE),
    onSurface       = Color(0xFFF1FAEE),
    surfaceVariant  = Color(0xFF1F1F26),
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
 * Backward-compatible dark scheme using the default [primaryColor].
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
    appearance: AppearanceMode = AppearanceMode.DARK,
    accent: Accent = Accent.SYSTEM,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (appearance) {
        AppearanceMode.DARK  -> nyoraDarkColorScheme(accent.colorFor(appearance))
        AppearanceMode.LIGHT -> nyoraLightColorScheme(accent.colorFor(appearance))
    }
    // Push the theme-reactive palette into NyoraTokens so the ~397 token call sites recolor
    // on light/dark swap. SideEffect runs after a successful composition, so the snapshot
    // writes here are applied outside composition and safely invalidate token readers.
    androidx.compose.runtime.SideEffect {
        NyoraTokens.applyPalette(if (appearance == AppearanceMode.LIGHT) LightPalette else DarkPalette)
    }
    CompositionLocalProvider(
        LocalNyoraAccent provides accent,
        LocalNyoraAppearance provides appearance,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = WindowsNative.typography(Typography()),
            shapes      = NyoraShapes,
            content     = content,
        )
    }
}
