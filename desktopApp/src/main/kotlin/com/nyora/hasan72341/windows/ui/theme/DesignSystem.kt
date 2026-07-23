package com.nyora.windows.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

/**
 * Nyora "Midnight Sakura" Design System.
 *
 * One signature idea repeated everywhere: soft neon glow bleed + frosted glass on a
 * near-black noir field. Every screen should consume these tokens, brushes, modifiers
 * and composables instead of hardcoding `Color.White.copy(alpha = …)`.
 *
 * Brush builders and the [AnimeAsyncImage]/glow helpers read [LocalNyoraAccent] (from
 * Theme.kt) so glows/gradients recolor live with the user's selected accent.
 *
 * Targets Compose Multiplatform 1.7.3 (Desktop). Hover is real on desktop and is
 * detected via the experimental [onPointerEvent] modifier (Enter/Exit).
 */

// ---------------------------------------------------------------------------------------
// (a) Color tokens
// ---------------------------------------------------------------------------------------

/**
 * Flat, allocation-free color vocabulary for the whole app.
 *
 * - [bg]                noir background.
 * - [surface1]/[surface2]/[surface3] the elevation "surface ladder".
 * - accent constants ([crimson], [sakura], [iris], [mint]) — note the *live* accent is
 *   [LocalNyoraAccent]; these constants are the curated signature hues for fixed motifs.
 * - [glass1]..[glass5]  the white-alpha frosted-glass ramp (0.03 / 0.05 / 0.08 / 0.12 / 0.18).
 * - hairline border colors for thin gradient strokes.
 */
object NyoraTokens {

    // --- THEME-REACTIVE tokens --------------------------------------------------------
    // Each of these is a `var ... by mutableStateOf(...)` so that reading it inside a
    // @Composable registers a snapshot dependency. Swapping the palette via
    // [applyPalette] therefore recomposes every one of the ~397 call sites that read
    // `NyoraTokens.bg`, `NyoraTokens.surface1`, etc. — WITHOUT touching any call site.
    // The defaults below are the neutral DARK values, so first composition before
    // any [applyPalette] still renders the signature noir look.

    // Background + surface ladder.
    var bg: Color       by mutableStateOf(Color(0xFF000000))
    var surface1: Color by mutableStateOf(Color(0xFF121216))
    var surface2: Color by mutableStateOf(Color(0xFF17171C))
    var surface3: Color by mutableStateOf(Color(0xFF1F1F26))

    // Signature accent hues (mirror Theme.kt's Accent palette) — theme-INDEPENDENT.
    val crimson: Color = Color(0xFFE63946)
    val sakura: Color  = Color(0xFFFF6B81)
    val iris: Color    = Color(0xFF7C5CFF)
    val mint: Color    = Color(0xFF5CE1C0)

    // Frosted-glass alpha ramp (white-on-noir in AMOLED, black-on-paper in light).
    var glass1: Color by mutableStateOf(Color.White.copy(alpha = 0.03f))
    var glass2: Color by mutableStateOf(Color.White.copy(alpha = 0.05f))
    var glass3: Color by mutableStateOf(Color.White.copy(alpha = 0.08f))
    var glass4: Color by mutableStateOf(Color.White.copy(alpha = 0.12f))
    var glass5: Color by mutableStateOf(Color.White.copy(alpha = 0.18f))

    // Thin hairline border stops.
    var hairlineStrong: Color by mutableStateOf(Color.White.copy(alpha = 0.18f))
    var hairlineFaint: Color  by mutableStateOf(Color.White.copy(alpha = 0.04f))

    // Black masking ramp for hero bottom gradients.
    // maskTransparent is always Color.Transparent (theme-independent) so stays a val.
    val maskTransparent: Color = Color.Transparent
    var maskNoir: Color        by mutableStateOf(Color(0xFF000000).copy(alpha = 0.92f))

    // Body text alpha (white-on-noir / black-on-paper).
    var onSurfaceHigh: Color   by mutableStateOf(Color.White.copy(alpha = 0.92f))
    var onSurfaceBody: Color   by mutableStateOf(Color.White.copy(alpha = 0.80f))
    var onSurfaceMuted: Color  by mutableStateOf(Color.White.copy(alpha = 0.55f))
    var onSurfaceFaint: Color  by mutableStateOf(Color.White.copy(alpha = 0.35f))
}

// ---------------------------------------------------------------------------------------
// (a2) Theme-aware palettes
// ---------------------------------------------------------------------------------------

/**
 * A complete set of theme-reactive color values for [NyoraTokens]. Two curated instances
 * exist — [DarkPalette] (neutral dark) and [LightPalette] (paper) — and the active one is
 * pushed into [NyoraTokens] via [NyoraTokens.applyPalette] from `NyoraTheme`.
 *
 * Only the theme-DEPENDENT tokens live here; the accent hues (crimson/sakura/iris/mint)
 * and [NyoraTokens.maskTransparent] are theme-independent and are not part of the palette.
 */
data class NyoraPalette(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val glass1: Color,
    val glass2: Color,
    val glass3: Color,
    val glass4: Color,
    val glass5: Color,
    val hairlineStrong: Color,
    val hairlineFaint: Color,
    val maskNoir: Color,
    val onSurfaceHigh: Color,
    val onSurfaceBody: Color,
    val onSurfaceMuted: Color,
    val onSurfaceFaint: Color,
)

/** Tasteful neutral DARK palette — the signature noir look on a near-black surface ladder
 *  (NOT pure black), and the token defaults. */
val DarkPalette = NyoraPalette(
    bg             = Color(0xFF0E0E12),
    surface1       = Color(0xFF16161C),
    surface2       = Color(0xFF1B1B22),
    surface3       = Color(0xFF1F1F26),
    glass1         = Color.White.copy(alpha = 0.03f),
    glass2         = Color.White.copy(alpha = 0.05f),
    glass3         = Color.White.copy(alpha = 0.08f),
    glass4         = Color.White.copy(alpha = 0.12f),
    glass5         = Color.White.copy(alpha = 0.18f),
    hairlineStrong = Color.White.copy(alpha = 0.18f),
    hairlineFaint  = Color.White.copy(alpha = 0.04f),
    maskNoir       = Color(0xFF000000).copy(alpha = 0.92f),
    onSurfaceHigh  = Color.White.copy(alpha = 0.92f),
    onSurfaceBody  = Color.White.copy(alpha = 0.80f),
    onSurfaceMuted = Color.White.copy(alpha = 0.55f),
    onSurfaceFaint = Color.White.copy(alpha = 0.35f),
)

/** Tasteful paper-light palette — black-alpha glass on a near-white surface ladder. */
val LightPalette = NyoraPalette(
    bg             = Color(0xFFFAFAFA),
    surface1       = Color(0xFFFFFFFF),
    surface2       = Color(0xFFF1F1F4),
    surface3       = Color(0xFFE6E6EA),
    glass1         = Color.Black.copy(alpha = 0.03f),
    glass2         = Color.Black.copy(alpha = 0.05f),
    glass3         = Color.Black.copy(alpha = 0.08f),
    glass4         = Color.Black.copy(alpha = 0.12f),
    glass5         = Color.Black.copy(alpha = 0.18f),
    hairlineStrong = Color.Black.copy(alpha = 0.12f),
    hairlineFaint  = Color.Black.copy(alpha = 0.05f),
    maskNoir       = Color(0xFF000000).copy(alpha = 0.6f),
    onSurfaceHigh  = Color.Black.copy(alpha = 0.90f),
    onSurfaceBody  = Color.Black.copy(alpha = 0.74f),
    onSurfaceMuted = Color.Black.copy(alpha = 0.52f),
    onSurfaceFaint = Color.Black.copy(alpha = 0.36f),
)

/**
 * Push [p] into the theme-reactive [NyoraTokens] vars. Because each var is backed by a
 * [mutableStateOf], assigning here invalidates every composition that read a token, so the
 * whole UI recolors to match the active [AppearanceMode]. Call from a `SideEffect` in
 * `NyoraTheme` so it runs after a successful composition.
 */
fun NyoraTokens.applyPalette(p: NyoraPalette) {
    bg             = p.bg
    surface1       = p.surface1
    surface2       = p.surface2
    surface3       = p.surface3
    glass1         = p.glass1
    glass2         = p.glass2
    glass3         = p.glass3
    glass4         = p.glass4
    glass5         = p.glass5
    hairlineStrong = p.hairlineStrong
    hairlineFaint  = p.hairlineFaint
    maskNoir       = p.maskNoir
    onSurfaceHigh  = p.onSurfaceHigh
    onSurfaceBody  = p.onSurfaceBody
    onSurfaceMuted = p.onSurfaceMuted
    onSurfaceFaint = p.onSurfaceFaint
}

// ---------------------------------------------------------------------------------------
// (g) Motion specs
// ---------------------------------------------------------------------------------------

/** Selection / scale spring — nothing snaps, everything springs (damping 0.7, stiffness 300). */
val NyoraSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 450f)

/** Opacity / glass tween — 220ms EaseOutCubic. */
val NyoraSmooth: AnimationSpec<Float> = tween(durationMillis = 150, easing = EaseOutCubic)

// ---------------------------------------------------------------------------------------
// (b) Brush builders — read LocalNyoraAccent so they recolor with the live accent.
// ---------------------------------------------------------------------------------------

/**
 * Hero gradient: a gentle vertical sakura -> accent -> transparent ramp over noir.
 *
 * Simplified from the old busy 10-stop diagonal wash to a clean, subtle vertical ramp: a
 * soft sakura head biasing into the live accent and dissolving to transparent in the lower
 * third. Low alpha so it tints rather than washes. Used as a `.background(brush)`, so it
 * respects its host's clip.
 */
@Composable
fun heroGradient(): Brush = SolidColor(NyoraTokens.bg)

/**
 * Aurora gradient: a single soft centered radial of accent blended with iris, fading to
 * transparent well before the edges so it never rings or shows a hard boundary.
 *
 * Simplified from the old multi-color accent/iris/mint stack to one tasteful low-alpha
 * radial. Used as a `.background(brush)`; respects its host's clip. For the full ambient
 * backdrop over noir prefer [Modifier.auroraMesh].
 */
@Composable
fun auroraGradient(): Brush = SolidColor(NyoraTokens.bg)

/**
 * Glow gradient: an accent core fading to transparent — for beacons, progress lines,
 * focus rings and the [glowBorder] modifier. Defaults to the live accent.
 */
@Composable
fun glowGradient(color: Color = LocalNyoraAccent.current.color): Brush = SolidColor(color)

/** The diagonal white 0.18 -> 0.04 hairline used as the gradient border on every glass card. */
@Composable
fun glassBorderBrush(): Brush = SolidColor(NyoraTokens.hairlineStrong)

// ---------------------------------------------------------------------------------------
// (b1) FLAT + LINEAR-GRADIENT design language.
//
// The signature is no longer "frosted glass with glow bleed" — it is FLAT surfaces with
// crisp hairlines, accented by a single opaque diagonal linear gradient built from the live
// accent. These three brushes are the whole gradient vocabulary; they read both
// [LocalNyoraAccent] (live accent) and the theme-reactive [NyoraTokens], so they recolor on
// accent change AND on light/amoled swap.
// ---------------------------------------------------------------------------------------

/**
 * The signature accent gradient: an OPAQUE TopStart -> BottomEnd diagonal linear ramp from
 * the live accent into a slightly iris-shifted variant of itself. Opaque so it reads on both
 * light and amoled. Used for primary buttons, active rails, accent fills.
 */
@Composable
fun accentGradient(): Brush {
    val accent = LocalNyoraAccent.current.color
    return Brush.linearGradient(
        colors = listOf(accent, lerp(accent, NyoraTokens.iris, 0.35f)),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
}

/**
 * The same accent hues as [accentGradient] but at low alpha (~0.16) — for the FILL of
 * selected chips, segmented-control selections and soft accent washes that sit under text.
 */
@Composable
fun accentGradientSubtle(): Brush {
    val accent = LocalNyoraAccent.current.color
    return Brush.linearGradient(
        colors = listOf(
            accent.copy(alpha = 0.16f),
            lerp(accent, NyoraTokens.iris, 0.35f).copy(alpha = 0.16f),
        ),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
}

/**
 * A near-flat surface ramp: [NyoraTokens.surface1] -> [NyoraTokens.surface2] linear gradient
 * for header bands and large surface fills that want a whisper of depth without any glow.
 */
@Composable
fun surfaceGradient(): Brush = Brush.linearGradient(
    colors = listOf(NyoraTokens.surface1, NyoraTokens.surface2),
    start = Offset.Zero,
    end = Offset.Infinite,
)

// ---------------------------------------------------------------------------------------
// (b2) Premium gradient engine — clean centered ambient aurora over solid noir.
//
// Both former "depth" levers were overcooking the UI and are now disabled:
//   - filmGrain  — was a white noise bitmap tiled with BlendMode.Overlay; it washed dark
//                  glass out toward white and showed faint square tile seams. Now a no-op.
//   - auroraMesh — was four corner-anchored additive blobs whose bright corners poked past
//                  rounded clips as a "square" seam. Now ONE clean centered radial glow that
//                  fades to transparent before the corners, over a solid noir base.
// ---------------------------------------------------------------------------------------

/**
 * **Film-grain dithering** — now a SAFE NO-OP.
 *
 * The previous implementation tiled a 220x220 WHITE noise bitmap with [BlendMode.Overlay].
 * Overlaying white *lightens* the substrate (washing out the dark glass surfaces) and the
 * tile boundaries could read as faint square seams across every pane. Disabling it removes
 * the washout AND the square seams everywhere at once. The signature is preserved by the
 * clean centered [auroraMesh] glow instead.
 *
 * Kept as an identity modifier so every existing call site (`Modifier.…filmGrain()`) still
 * compiles unchanged. [alpha] is accepted and ignored.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.filmGrain(alpha: Float = 0.05f): Modifier = this

/**
 * **Mesh aurora** — a clean, premium, CENTERED ambient glow over solid noir.
 *
 * The previous version composited four corner-anchored radial blobs with [BlendMode.Plus].
 * The bright color painted into the rectangular corners poked past the [RoundedCornerShape]
 * clip that [glassCard] applies later, showing up as the visible "square" seam — and the
 * additive stacking read muddy.
 *
 * This reimplementation can NEVER produce a square:
 *   1. Paint the noir base ([NyoraTokens.bg]) so the surface is solid dark.
 *   2. Lay ONE soft [Brush.radialGradient] centered on the box, using the LIVE accent gently
 *      blended with [NyoraTokens.iris], at a low peak alpha (~0.10 * [intensity]).
 *   3. The glow fades to [Color.Transparent] by ~62% of the radius, and the radius is sized
 *      so the glow never reaches the rectangular corners — every corner stays pure noir, so
 *      there is nothing bright to poke past a rounded clip. No corner blobs, no Plus stacking.
 *
 * [intensity] scales the glow's peak alpha (1f = signature strength). Kept as a Modifier so
 * callers compose it exactly as before.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun Modifier.auroraMesh(intensity: Float = 1f): Modifier =
    this.drawBehind { drawRect(color = NyoraTokens.bg) }

// --- (b3) OPTIONAL SkSL aurora shader — disabled (see rememberAuroraShaderBrush). ---

/**
 * **Disabled** — always returns `null`.
 *
 * This previously compiled an SkSL four-point mesh shader that reproduced the same
 * corner-anchored-blob aurora (and therefore the same bright-corner "square" risk under a
 * rounded clip). It is now intentionally a no-op so the clean centered [auroraMesh] is the
 * single source of the ambient glow. Callers already treat `null` as "fall back to
 * [auroraMesh]", so nothing breaks. Kept present so every call site still compiles.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun rememberAuroraShaderBrush(size: Size): Brush? = null

// ---------------------------------------------------------------------------------------
// (c) Modifier extensions
// ---------------------------------------------------------------------------------------

/**
 * The FLAT card surface (was frosted-glass): a solid surface fill, a crisp 1.dp
 * [NyoraTokens.hairlineFaint] border and a small (4.dp) drop shadow for the barest lift.
 * No 18.dp shadow, no inner top highlight — flat by design and legible on both light and
 * amoled. Name + signature are unchanged so all ~call sites stay identical.
 */
@Composable
fun Modifier.glassCard(
    shape: Shape = RoundedCornerShape(20.dp),
    fill: Color = NyoraTokens.surface1,
): Modifier {
    return this
        .shadow(elevation = 4.dp, shape = shape, clip = false)
        .clip(shape)
        .background(fill)
        .border(width = 1.dp, color = NyoraTokens.hairlineFaint, shape = shape)
}

/**
 * A lightweight FLAT overlay — surface fill + crisp hairline — for chips, pills and
 * floating bars that sit over content without any shadow. Flat to match [glassCard].
 */
@Composable
fun Modifier.glassOverlay(
    shape: Shape = RoundedCornerShape(50),
    fill: Color = NyoraTokens.surface1,
): Modifier {
    return this
        .clip(shape)
        .background(fill)
        .border(width = 1.dp, color = NyoraTokens.hairlineFaint, shape = shape)
}

/**
 * A STATIC accent border for focus rings / beacons (was a breathing glow). Draws a crisp
 * 1.5.dp accent-colored stroke — no pulse, no glow shadow — so it reads cleanly on both
 * light and amoled. Name + signature unchanged.
 */
@Composable
fun Modifier.glowBorder(
    color: Color = LocalNyoraAccent.current.color,
    shape: Shape = RoundedCornerShape(20.dp),
): Modifier {
    return this.border(
        width = 1.5.dp,
        color = color,
        shape = shape,
    )
}

/**
 * Desktop hover lift, FLATTENED: on pointer-enter the element springs scale 1f -> 1.03f and
 * brightens a 1.dp accent-tinted border; on exit it springs back. No big glow shadow, so it
 * reads correctly on both light and amoled. "Quiet until touched, then responds."
 *
 * Hover is detected with the experimental [onPointerEvent] (Enter/Exit) modifier, which
 * is stable on Compose Desktop 1.7.3. Name + signature unchanged.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Modifier.hoverLift(
    shape: Shape = RoundedCornerShape(20.dp),
    scaleTo: Float = 1.03f,
    glowColor: Color = LocalNyoraAccent.current.color,
): Modifier {
    var hovered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (hovered) scaleTo else 1f,
        animationSpec = NyoraSpring,
        label = "hoverScale",
    )
    val borderColor = if (hovered) glowColor.copy(alpha = 0.45f) else NyoraTokens.hairlineFaint
    return this
        .onPointerEvent(PointerEventType.Enter) { hovered = true }
        .onPointerEvent(PointerEventType.Exit) { hovered = false }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(width = 1.dp, color = borderColor, shape = shape)
}

/**
 * Animated diagonal shimmer sweep for loading placeholders. Renders a moving accent-neutral
 * highlight band diagonally across a [NyoraTokens.surface1] base via an infinite transition,
 * so skeletons read as "loading" instead of a dead grey block.
 */
@Composable
fun Modifier.shimmerPlaceholder(shape: Shape = RoundedCornerShape(16.dp)): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerSweep",
    )
    val base = NyoraTokens.surface1
    val highlight = NyoraTokens.glass4
    return this
        .clip(shape)
        .drawWithContent {
            drawRect(base)
            val band = size.width * 0.55f
            val startX = -band + (size.width + band) * progress
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(startX, 0f),
                    end = Offset(startX + band, size.height),
                ),
            )
            drawContent()
        }
}

/**
 * Foreground color that reads on top of the live accent fill ([accentGradient]). White on
 * darker accents, near-black on light accents (sakura / mint / a light Material-You seed),
 * so accent-filled labels stay legible whatever hue the user picks. Replaces the hardcoded
 * `Color.White` scattered across accent buttons and chips.
 */
@Composable
fun onAccentColor(): Color {
    val accent = LocalNyoraAccent.current.color
    return if (accent.luminance() > 0.55f) Color(0xFF0E0E12) else Color.White
}

// ---------------------------------------------------------------------------------------
// (c1) Desktop scrollbars
//
// Compose Desktop draws NO scrollbar by default, so every scrolling surface used to have
// zero overflow affordance. [NyoraScrollbar] is a thin, auto-hiding, accent-tinted thumb;
// [NyoraScrollContainer] wraps content + scrollbar in one Box. Call sites hoist a
// rememberLazyListState / rememberLazyGridState / rememberScrollState and pass
// rememberScrollbarAdapter(state).
// ---------------------------------------------------------------------------------------

/** A thin, accent-tinted, auto-hiding vertical scrollbar for the given [adapter]. */
@Composable
fun NyoraScrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    val accent = LocalNyoraAccent.current.color
    VerticalScrollbar(
        adapter = adapter,
        modifier = modifier,
        style = LocalScrollbarStyle.current.copy(
            thickness = 8.dp,
            hoverDurationMillis = 250,
            unhoverColor = accent.copy(alpha = 0.20f),
            hoverColor = accent.copy(alpha = 0.55f),
            shape = RoundedCornerShape(4.dp),
        ),
    )
}

/**
 * Wraps a scrolling [content] and overlays a [NyoraScrollbar] pinned to the right edge.
 * The scrollbar floats over the content (CenterEnd), so lay out content with a little end
 * padding if it would otherwise collide with the thumb.
 */
@Composable
fun NyoraScrollContainer(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        content()
        NyoraScrollbar(
            adapter = adapter,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 4.dp, horizontal = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------
// (d) AnimeAsyncImage
// ---------------------------------------------------------------------------------------

/**
 * The signature image element: a Coil3 [SubcomposeAsyncImage] with a shimmer loading
 * state, an inner top highlight overlay, a soft drop shadow, and an accent glow on hover.
 *
 * Drop-in for raw `AsyncImage` calls across screens — pass the same cover URL/model.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AnimeAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape = RoundedCornerShape(20.dp),
) {
    var hovered by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val glowColor = LocalNyoraAccent.current.color
    val glowAlpha by animateFloatAsState(
        targetValue = if (hovered) 0.45f else 0f,
        animationSpec = NyoraSmooth,
        label = "imageGlow",
    )
    // The drop shadow is only rendered while hovered. A 16dp shadow on every visible
    // grid cell is pure overdraw during scroll, so non-hovered covers now cost nothing.
    val elevation by animateDpAsState(
        targetValue = if (hovered) 16.dp else 0.dp,
        animationSpec = tween(durationMillis = 150, easing = EaseOutCubic),
        label = "imageElev",
    )
    val border = glassBorderBrush()

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = glowColor.copy(alpha = glowAlpha),
                spotColor = glowColor.copy(alpha = glowAlpha),
            )
            .clip(shape)
            .background(NyoraTokens.surface1)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
    ) {
        // Plain AsyncImage — NOT SubcomposeAsyncImage. Subcomposition per cover is the
        // dominant scroll-jank source in grids/lists; this composes inline. Cached covers
        // appear instantly (no per-image fade), which removes the "delayed reveal" feel.
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onState = { state ->
                loading = state is AsyncImagePainter.State.Loading ||
                    state is AsyncImagePainter.State.Empty
            },
        )

        // Shimmer only while the bytes are still resolving.
        if (loading) {
            Box(Modifier.fillMaxSize().shimmerPlaceholder(shape))
        }

        // Hairline border to match glass cards.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(width = 1.dp, brush = border, shape = shape),
        )
    }
}

// ---------------------------------------------------------------------------------------
// (e) SectionHeader
// ---------------------------------------------------------------------------------------

/**
 * Oversized, tracking-tight section header with an optional all-caps subtitle treatment.
 * Establishes the obsessive vertical rhythm at the top of each content section.
 */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (subtitle != null) {
            SystemTag(text = subtitle)
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.5).sp,
            color = NyoraTokens.onSurfaceHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------------------
// (f) SystemTag
// ---------------------------------------------------------------------------------------

/**
 * The recurring ALL-CAPS micro-label motif: 9.5sp, Black, +0.5sp tracking, accent-colored
 * by default. Used as section eyebrows, status chips and the "FEATURED"-style tags.
 */
@Composable
fun SystemTag(
    text: String,
    color: Color? = null,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color ?: LocalNyoraAccent.current.color,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------------------------------
// (h) Canonical interactive components — Material 3 Expressive vocabulary.
//
// One shared set of CTAs / chips / icon buttons / tags so every call site recolors with the
// live accent and gets consistent Expressive shape + spring press/hover. Prefer these over
// raw Button / OutlinedButton / inline Box+clickable.
// ---------------------------------------------------------------------------------------

/** Primary CTA: opaque accent-gradient fill, onAccent label, spring press-scale. */
@Composable
fun NyoraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, NyoraSpring, label = "press")
    val onAccent = onAccentColor()
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
            .clip(RoundedCornerShape(14.dp))
            .background(accentGradient())
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = onAccent, modifier = Modifier.size(18.dp))
        Text(text, color = onAccent, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Secondary CTA: tonal glass fill with a hairline; accent icon, high-contrast label. */
@Composable
fun NyoraTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, NyoraSpring, label = "press")
    val accent = LocalNyoraAccent.current.color
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
            .glassOverlay(shape = RoundedCornerShape(14.dp), fill = NyoraTokens.surface2)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        Text(text, color = NyoraTokens.onSurfaceHigh, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Low-emphasis text button (accent label). */
@Composable
fun NyoraTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val accent = LocalNyoraAccent.current.color
    Text(
        text = text,
        color = if (enabled) accent else NyoraTokens.onSurfaceFaint,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** A selectable filter chip with ONE canonical selected treatment (accent wash + border). */
@Composable
fun NyoraFilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalNyoraAccent.current.color
    val sel by animateFloatAsState(if (selected) 1f else 0f, NyoraSpring, label = "chipSel")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.16f * sel))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.55f) else NyoraTokens.hairlineStrong,
                shape = RoundedCornerShape(50),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (selected) NyoraTokens.onSurfaceHigh else NyoraTokens.onSurfaceMuted,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** A square glass icon button (optionally accent-tinted). */
@Composable
fun NyoraIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    accentTint: Boolean = false,
) {
    val accent = LocalNyoraAccent.current.color
    Box(
        modifier = modifier
            .size(size)
            .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = if (accentTint) accent else NyoraTokens.onSurfaceBody,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

/** Semantic tag tone. */
enum class NyoraTone { Neutral, Accent, Success, Warn, Danger }

/** A small status tag; [tone] maps to a consistent color, delegating to [SystemTag]. */
@Composable
fun NyoraTag(text: String, tone: NyoraTone = NyoraTone.Neutral, modifier: Modifier = Modifier) {
    val accent = LocalNyoraAccent.current.color
    val color = when (tone) {
        NyoraTone.Neutral -> NyoraTokens.onSurfaceMuted
        NyoraTone.Accent -> accent
        NyoraTone.Success -> NyoraTokens.mint
        NyoraTone.Warn -> Color(0xFFF2B84B)
        NyoraTone.Danger -> Color(0xFFE5484D)
    }
    SystemTag(text = text, color = color, modifier = modifier)
}

/**
 * One soft, edge-inset accent glow at the container level — restores the signature luminous
 * "glow bleed" without the old square-seam artifact by drawing a single centered radial that
 * fades to transparent WELL before the edges. Apply on a top-level Surface/Box, never on a
 * clipped-corner element.
 */
@Composable
fun Modifier.ambientGlow(intensity: Float = 0.06f): Modifier {
    val accent = LocalNyoraAccent.current.color
    return this.drawBehind {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = intensity), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 0.32f),
                radius = size.maxDimension * 0.7f,
            ),
        )
    }
}
