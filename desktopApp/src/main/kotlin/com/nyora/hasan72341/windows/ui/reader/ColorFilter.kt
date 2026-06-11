package com.nyora.windows.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nyora.windows.AppState
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import kotlin.math.cos
import kotlin.math.sin

// =======================================================================================
// Reader color grading — all GPU-side via a Compose ColorMatrix ColorFilter on the page
// image. There is NO server image endpoint; nothing is baked into the bytes.
//
// Compose's [ColorMatrix] is a row-major 4x5 FloatArray (20 floats):
//
//     | a00 a01 a02 a03 a04 |   R
//     | a10 a11 a12 a13 a14 |   G
//     | a20 a21 a22 a23 a24 |   B
//     | a30 a31 a32 a33 a34 |   A
//
// Columns 0..3 are the R,G,B,A multipliers; column 4 is the additive offset. The offset
// column is applied in the 0..255 channel scale (so a normalized "+0.1 brightness" maps
// to +25.5 in that column).
// =======================================================================================

/** The named palette preset keys, in display order, for the chip UI. */
val READER_PALETTES: List<String> = listOf(
    "",            // none
    "Grayscale",
    "HighContrast",
    "Soft",
    "Sepia",
    "Noir",
    "Invert",
    "Cool",
    "Warm",
    "DuotoneRed",
    "DuotoneBlue",
)

/** True when every grading parameter is at its neutral identity value. */
private fun isNeutral(
    brightness: Double,
    contrast: Double,
    saturation: Double,
    hue: Double,
    palette: String,
): Boolean =
    brightness == 0.0 &&
        contrast == 1.0 &&
        saturation == 1.0 &&
        hue == 0.0 &&
        palette.isEmpty()

/**
 * Builds the GPU [ColorFilter] for the reader page image by composing, in order:
 * brightness (additive), contrast (scale around mid-gray 0.5), saturation
 * ([ColorMatrix.setToSaturation]), a luminance-preserving hue rotation, and the selected
 * named [palette] preset.
 *
 * Returns `null` when all parameters are neutral so callers can skip the filter entirely.
 *
 * @param brightness -1.0 .. 1.0 (0 = none) — added to every channel.
 * @param contrast    0.0 .. 2.0 (1 = none) — scale around 0.5.
 * @param saturation  0.0 .. 2.0 (1 = none).
 * @param hue      -180.0 .. 180.0 degrees (0 = none).
 * @param palette  one of [READER_PALETTES] ("" = none).
 */
fun buildReaderColorFilter(
    brightness: Double,
    contrast: Double,
    saturation: Double,
    hue: Double,
    palette: String,
): ColorFilter? {
    if (isNeutral(brightness, contrast, saturation, hue, palette)) return null

    // Start from identity and left-multiply each stage so the visual order is:
    // brightness -> contrast -> saturation -> hue -> palette.
    val acc = ColorMatrix() // identity

    if (brightness != 0.0) {
        acc.timesAssignLeft(brightnessMatrix(brightness.toFloat()))
    }
    if (contrast != 1.0) {
        acc.timesAssignLeft(contrastMatrix(contrast.toFloat()))
    }
    if (saturation != 1.0) {
        val sat = ColorMatrix().apply { setToSaturation(saturation.toFloat()) }
        acc.timesAssignLeft(sat)
    }
    if (hue != 0.0) {
        acc.timesAssignLeft(hueMatrix(hue.toFloat()))
    }

    paletteMatrix(palette)?.let { acc.timesAssignLeft(it) }

    return ColorFilter.colorMatrix(acc)
}

// ---------------------------------------------------------------------------------------
// Matrix builders
// ---------------------------------------------------------------------------------------

/** Brightness add: identity scale, additive offset on RGB (offset column is 0..255). */
private fun brightnessMatrix(amount: Float): ColorMatrix {
    val o = amount * 255f
    return ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, o,
            0f, 1f, 0f, 0f, o,
            0f, 0f, 1f, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/**
 * Contrast scale around mid-gray (0.5). For scale `c`: out = c*in + (0.5 - 0.5c).
 * The constant term is in the 0..255 column, so it is multiplied by 255.
 */
private fun contrastMatrix(c: Float): ColorMatrix {
    val t = (0.5f - 0.5f * c) * 255f
    return ColorMatrix(
        floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/**
 * Standard luminance-preserving hue rotation by [degrees]. Rotates the RGB color cube
 * about the gray axis; the Rec.601 luma weights keep perceived brightness stable.
 */
private fun hueMatrix(degrees: Float): ColorMatrix {
    val rad = Math.toRadians(degrees.toDouble())
    val cosA = cos(rad).toFloat()
    val sinA = sin(rad).toFloat()

    // Rec.601 luma weights.
    val lr = 0.213f
    val lg = 0.715f
    val lb = 0.072f

    return ColorMatrix(
        floatArrayOf(
            lr + cosA * (1 - lr) + sinA * (-lr), lg + cosA * (-lg) + sinA * (-lg), lb + cosA * (-lb) + sinA * (1 - lb), 0f, 0f,
            lr + cosA * (-lr) + sinA * (0.143f), lg + cosA * (1 - lg) + sinA * (0.140f), lb + cosA * (-lb) + sinA * (-0.283f), 0f, 0f,
            lr + cosA * (-lr) + sinA * (-(1 - lr)), lg + cosA * (-lg) + sinA * (lg), lb + cosA * (1 - lb) + sinA * (lb), 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/** Named palette presets. Returns `null` for "" (no palette stage). */
private fun paletteMatrix(palette: String): ColorMatrix? = when (palette) {
    "" -> null

    "Grayscale" -> ColorMatrix().apply { setToSaturation(0f) }

    "HighContrast" -> contrastMatrix(1.55f)

    // Low-contrast, slightly warm wash.
    "Soft" -> {
        val soft = contrastMatrix(0.82f)
        soft.timesAssignLeft(warmTintMatrix(strength = 0.10f))
        soft
    }

    "Sepia" -> ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )

    // Desaturate fully then crush contrast.
    "Noir" -> {
        val noir = ColorMatrix().apply { setToSaturation(0f) }
        noir.timesAssignLeft(contrastMatrix(1.45f))
        noir
    }

    // Negative: invert each channel (out = 1 - in => -1*in + 255 offset).
    "Invert" -> ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )

    "Cool" -> coolTintMatrix(strength = 0.18f)

    "Warm" -> warmTintMatrix(strength = 0.18f)

    // Duotone: collapse to luminance then map onto a two-color ramp toward red/blue.
    "DuotoneRed" -> duotoneMatrix(highR = 1.0f, highG = 0.32f, highB = 0.30f)

    "DuotoneBlue" -> duotoneMatrix(highR = 0.30f, highG = 0.45f, highB = 1.0f)

    else -> null
}

/** Lifts red, drops blue for an amber cast. */
private fun warmTintMatrix(strength: Float): ColorMatrix {
    val s = strength * 255f
    return ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, s,
            0f, 1f, 0f, 0f, s * 0.4f,
            0f, 0f, 1f, 0f, -s,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/** Lifts blue, drops red for a cool cast. */
private fun coolTintMatrix(strength: Float): ColorMatrix {
    val s = strength * 255f
    return ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, -s,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, s,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/**
 * Duotone: compute Rec.601 luminance into all three channels (a gray base), then scale
 * each output channel by the supplied "high" color so the image rides a single hue ramp
 * from black to the high color.
 */
private fun duotoneMatrix(highR: Float, highG: Float, highB: Float): ColorMatrix {
    val lr = 0.299f
    val lg = 0.587f
    val lb = 0.114f
    return ColorMatrix(
        floatArrayOf(
            lr * highR, lg * highR, lb * highR, 0f, 0f,
            lr * highG, lg * highG, lb * highG, 0f, 0f,
            lr * highB, lg * highB, lb * highB, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/**
 * Left-multiply this matrix by [other] in place: `this = other * this`. Compose's
 * [ColorMatrix.timesAssign] computes `this = this * other`, so to apply [other] *after*
 * the transform already accumulated in `this`, we swap and reassign.
 */
private fun ColorMatrix.timesAssignLeft(other: ColorMatrix) {
    // Multiply other (left) by this (right): result = other * this.
    val a = other.values
    val b = this.values
    val out = FloatArray(20)
    for (row in 0 until 4) {
        for (col in 0 until 5) {
            var sum = 0f
            // Columns 0..3 of `a` multiply the matching rows of `b`.
            for (k in 0 until 4) {
                sum += a[row * 5 + k] * b[k * 5 + col]
            }
            // The translation column (index 4) of `a` carries through, plus, for the
            // final column, `a`'s own offset.
            if (col == 4) {
                sum += a[row * 5 + 4]
            }
            out[row * 5 + col] = sum
        }
    }
    for (i in 0 until 20) this.values[i] = out[i]
}

// =======================================================================================
// UI — glass color-grading sheet
// =======================================================================================

/**
 * The reader color-grading sheet: a frosted glass dialog with live Brightness / Contrast /
 * Saturation / Hue sliders, a wrapping row of palette preset chips, a live preview swatch
 * row, and a Reset control. All values are bound to the per-manga reader prefs on [state]
 * and persisted (debounced) via [AppState.saveMangaPrefs].
 *
 * Opened by ReaderScreen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderColorFilterSheet(state: AppState, onDismiss: () -> Unit) {
    val accent = LocalNyoraAccent.current.color

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .padding(16.dp)
                .glassCard(shape = RoundedCornerShape(28.dp), fill = NyoraTokens.surface1)
                .padding(28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionHeader(title = "Color Grade", subtitle = "Reader")

            // Live preview swatch row — shows the current grade applied to gradient swatches.
            PreviewRow(state = state)

            // Sliders.
            GradeSlider(
                label = "Brightness",
                value = state.readerBrightness.toFloat(),
                valueRange = -1f..1f,
                display = formatSigned(state.readerBrightness),
                accent = accent,
            ) {
                state.readerBrightness = it.toDouble()
                state.saveMangaPrefs()
            }
            GradeSlider(
                label = "Contrast",
                value = state.readerContrast.toFloat(),
                valueRange = 0f..2f,
                display = formatMultiplier(state.readerContrast),
                accent = accent,
            ) {
                state.readerContrast = it.toDouble()
                state.saveMangaPrefs()
            }
            GradeSlider(
                label = "Saturation",
                value = state.readerSaturation.toFloat(),
                valueRange = 0f..2f,
                display = formatMultiplier(state.readerSaturation),
                accent = accent,
            ) {
                state.readerSaturation = it.toDouble()
                state.saveMangaPrefs()
            }
            GradeSlider(
                label = "Hue",
                value = state.readerHue.toFloat(),
                valueRange = -180f..180f,
                display = "${state.readerHue.toInt()}°",
                accent = accent,
            ) {
                state.readerHue = it.toDouble()
                state.saveMangaPrefs()
            }

            // Palette preset chips.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SystemTag(text = "Presets")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    READER_PALETTES.forEach { key ->
                        PaletteChip(
                            label = if (key.isEmpty()) "None" else key,
                            selected = state.readerPalette == key,
                            accent = accent,
                        ) {
                            state.readerPalette = key
                            state.saveMangaPrefs()
                        }
                    }
                }
            }

            // Reset control.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    state.readerBrightness = 0.0
                    state.readerContrast = 1.0
                    state.readerSaturation = 1.0
                    state.readerHue = 0.0
                    state.readerPalette = ""
                    state.saveMangaPrefs()
                }) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Reset", color = accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Sheet sub-composables
// ---------------------------------------------------------------------------------------

/** A labelled live grade slider with a right-aligned numeric readout. */
@Composable
private fun GradeSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    accent: Color,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = NyoraTokens.onSurfaceBody,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                display,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = NyoraTokens.surface1,
            ),
        )
    }
}

/** A selectable palette preset chip in the glass language. */
@Composable
private fun PaletteChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val borderModifier =
        if (selected) Modifier.border(width = 1.dp, color = accent.copy(alpha = 0.8f), shape = shape)
        else Modifier.border(width = 1.dp, color = NyoraTokens.hairlineStrong, shape = shape)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else NyoraTokens.surface1)
            .then(borderModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else NyoraTokens.onSurfaceMuted,
        )
    }
}

/**
 * A live preview row: three reference swatches (gray ramp, accent, sakura) rendered with
 * the same [ColorFilter] the reader would apply, so the user sees the grade instantly.
 */
@Composable
private fun PreviewRow(state: AppState) {
    val filter = buildReaderColorFilter(
        brightness = state.readerBrightness,
        contrast = state.readerContrast,
        saturation = state.readerSaturation,
        hue = state.readerHue,
        palette = state.readerPalette,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val swatches = listOf(
            NyoraTokens.onSurfaceMuted, // neutral gray
            NyoraTokens.crimson,
            NyoraTokens.sakura,
            NyoraTokens.iris,
            NyoraTokens.mint,
        )
        val swatchShape = RoundedCornerShape(12.dp)
        swatches.forEach { base ->
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .weight(1f)
                    .clip(swatchShape)
                    .border(width = 1.dp, color = NyoraTokens.hairlineStrong, shape = swatchShape)
                    .drawBehind { drawRect(color = base, colorFilter = filter) },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------------------

private fun formatSigned(v: Double): String {
    val rounded = (v * 100).toInt()
    return if (rounded > 0) "+$rounded%" else "$rounded%"
}

private fun formatMultiplier(v: Double): String =
    String.format("%.2f×", v)
