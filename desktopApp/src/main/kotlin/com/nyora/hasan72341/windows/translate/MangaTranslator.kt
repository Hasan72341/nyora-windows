package com.nyora.windows.translate

import com.nyora.windows.ai.AiRefiner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * A translated speech bubble overlaid onto the manga page, in ORIGINAL image
 * pixels.
 *
 * [fillArgb] is the sampled balloon background (forced opaque) to repaint the
 * bubble with, and [textArgb] is the contrasting color to draw the translated
 * text in. Both are 0xAARRGGBB ints.
 */
data class TransBlock(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val original: String,
    val translated: String,
    val fillArgb: Int,
    val textArgb: Int,
    /**
     * The balloon-shaped fill region in ORIGINAL image pixels: 8 boundary points
     * (clockwise from North) found by ray-casting out of the text rect through the
     * light interior until the dark balloon border, so the repaint conforms to the
     * speech bubble instead of a crude rectangle. Empty if ray-casting found nothing.
     */
    val fillPolygon: List<Pair<Int, Int>>,
)

/**
 * Full translation result for a single page image.
 *
 * [ocrAvailable] is false only when the Windows OCR engine could not be reached
 * (no PowerShell host, or no installed language pack), which the UI surfaces as
 * "OCR unavailable". Coordinates are in ORIGINAL image pixels.
 */
data class PageTranslation(
    val imageWidth: Int,
    val imageHeight: Int,
    val blocks: List<TransBlock>,
    val ocrAvailable: Boolean,
)

/**
 * Orchestrates the OCR -> translate pipeline for a manga page. Every public
 * call fails soft: errors yield an empty translation rather than throwing.
 */
class MangaTranslator {

    private val http = OkHttpClient()

    /**
     * Download [imageUrl], OCR it for source language [ocrLang] (BCP-47, e.g.
     * "ja"), and translate every detected bubble into [target]. When [refiner] is
     * non-null the machine-translation draft is polished into more natural
     * dialogue (Windows AI or a BYOK model). For each bubble we sample the balloon
     * background from the ORIGINAL image and compute a contrasting text color so
     * the overlay can repaint the bubble naturally instead of stamping a dark box.
     */
    suspend fun translatePageImage(
        imageUrl: String,
        ocrLang: String,
        target: String,
        refiner: AiRefiner? = null,
    ): PageTranslation = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(imageUrl).build()
            val bytes = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching PageTranslation(0, 0, emptyList(), true)
                response.body?.bytes() ?: return@runCatching PageTranslation(0, 0, emptyList(), true)
            }

            // Decode the ORIGINAL image once; bubble boxes are in its pixel space.
            val image: BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))
                ?: return@runCatching PageTranslation(0, 0, emptyList(), true)
            val width = image.width
            val height = image.height

            val (boxes, available) = WindowsOcr.recognize(bytes, ocrLang)
            if (!available) {
                return@runCatching PageTranslation(width, height, emptyList(), false)
            }

            val usable = boxes.filter { it.text.trim().isNotEmpty() }
            if (usable.isEmpty()) {
                return@runCatching PageTranslation(width, height, emptyList(), true)
            }

            val machine = GoogleTranslate.translateAll(usable.map { it.text }, target)
            // Optional AI polish of the machine-translation draft. Fails soft to [machine].
            val translations = if (refiner != null) {
                runCatching { refiner.polish(machine, langName(target)) }
                    .getOrDefault(machine)
                    .let { if (it.size == machine.size) it else machine }
            } else {
                machine
            }
            val blocks = usable.mapIndexedNotNull { index, box ->
                val translated = translations.getOrElse(index) { box.text }
                if (translated.isBlank()) return@mapIndexedNotNull null

                val fillRgb = sampleBubbleBackground(image, box)
                val r = (fillRgb shr 16) and 0xFF
                val g = (fillRgb shr 8) and 0xFF
                val b = fillRgb and 0xFF
                val fillArgb = (0xFF000000.toInt()) or fillRgb
                val luminance = 0.299 * r + 0.587 * g + 0.114 * b
                val textArgb = if (luminance > 140) 0xFF1A1A1A.toInt() else 0xFFF5F5F5.toInt()

                TransBlock(
                    left = box.left,
                    top = box.top,
                    width = box.width,
                    height = box.height,
                    original = box.text,
                    translated = translated,
                    fillArgb = fillArgb,
                    textArgb = textArgb,
                    fillPolygon = rayCastBubble(image, box, luminance),
                )
            }

            PageTranslation(width, height, blocks, true)
        }.getOrDefault(PageTranslation(0, 0, emptyList(), true))
    }

    /** Map a translate target code (e.g. "en") to a human language name for the
     *  AI polish prompt; falls back to the code itself. */
    private fun langName(code: String): String = when (code.lowercase()) {
        "en" -> "English"
        "es" -> "Spanish"
        "fr" -> "French"
        "de" -> "German"
        "id" -> "Indonesian"
        "pt" -> "Portuguese"
        "ru" -> "Russian"
        "ar" -> "Arabic"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "zh", "zh-cn", "zh-hans" -> "Chinese"
        else -> code
    }

    /**
     * Sample the balloon's interior background from the ORIGINAL [image]. We read
     * four points inset ~18% from each corner of [box] (clamped to image bounds)
     * and pick the BRIGHTEST (max r+g+b) — that is the speech-balloon white/light
     * rather than a darker stroke or character art. Returns a 0xRRGGBB int.
     */
    private fun sampleBubbleBackground(image: BufferedImage, box: OcrBox): Int {
        val white = 0xFFFFFF
        return runCatching {
            val insetX = (box.width * 0.18f).toInt()
            val insetY = (box.height * 0.18f).toInt()
            val left = box.left + insetX
            val right = box.left + box.width - insetX
            val top = box.top + insetY
            val bottom = box.top + box.height - insetY

            val points = listOf(
                left to top,
                right to top,
                left to bottom,
                right to bottom,
            )

            var best: Int? = null
            var bestSum = -1
            for ((px, py) in points) {
                val x = px.coerceIn(0, image.width - 1)
                val y = py.coerceIn(0, image.height - 1)
                val rgb = image.getRGB(x, y) and 0xFFFFFF
                val sum = ((rgb shr 16) and 0xFF) + ((rgb shr 8) and 0xFF) + (rgb and 0xFF)
                if (sum > bestSum) {
                    bestSum = sum
                    best = rgb
                }
            }
            best ?: white
        }.getOrDefault(white)
    }

    /**
     * Ray-casts 8 directions out of [box]'s edges through the light balloon interior
     * until it hits the dark balloon border (or a distance cap), returning the 8
     * boundary points (clockwise from North) in ORIGINAL image pixels — the
     * speech-bubble-shaped fill region. A pixel darker than ~60% of the sampled
     * balloon background [bgLum] counts as the border.
     */
    private fun rayCastBubble(image: BufferedImage, box: OcrBox, bgLum: Double): List<Pair<Int, Int>> {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return emptyList()
        val cx = box.left + box.width / 2
        val cy = box.top + box.height / 2
        val left = box.left
        val right = box.left + box.width
        val top = box.top
        val bottom = box.top + box.height
        val darkThreshold = (bgLum * 0.6).coerceAtLeast(60.0)
        val cap = (maxOf(box.width, box.height) * 0.9).toInt().coerceIn(6, 240)
        // (startX, startY, dirX, dirY) — clockwise from North.
        val rays = listOf(
            intArrayOf(cx, top, 0, -1),
            intArrayOf(right, top, 1, -1),
            intArrayOf(right, cy, 1, 0),
            intArrayOf(right, bottom, 1, 1),
            intArrayOf(cx, bottom, 0, 1),
            intArrayOf(left, bottom, -1, 1),
            intArrayOf(left, cy, -1, 0),
            intArrayOf(left, top, -1, -1),
        )
        return rays.map { (sx, sy, dx, dy) ->
            var ex = sx.coerceIn(0, w - 1)
            var ey = sy.coerceIn(0, h - 1)
            var k = 1
            while (k <= cap) {
                val nx = sx + dx * k
                val ny = sy + dy * k
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) break
                val rgb = image.getRGB(nx, ny)
                val lum = 0.299 * ((rgb shr 16) and 0xFF) +
                    0.587 * ((rgb shr 8) and 0xFF) +
                    0.114 * (rgb and 0xFF)
                if (lum < darkThreshold) break
                ex = nx
                ey = ny
                k++
            }
            ex to ey
        }
    }
}
