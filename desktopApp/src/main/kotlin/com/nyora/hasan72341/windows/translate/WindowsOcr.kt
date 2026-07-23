package com.nyora.windows.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.color.ColorSpace
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.awt.image.ColorConvertOp
import java.awt.image.RescaleOp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/** A merged speech-bubble text block detected by OCR, in ORIGINAL image pixel coordinates. */
data class OcrBox(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val text: String,
    val conf: Float,
)

/**
 * On-device OCR backed by the built-in Windows OCR engine (`Windows.Media.Ocr`,
 * a.k.a. "Windows ML" text recognition) — the same engine the platform exposes
 * to UWP/WinUI apps. We reach it from the JVM by shelling out to Windows
 * PowerShell 5.1 running the bundled `windows_ocr.ps1`, which decodes the image,
 * runs the system OCR engine, and prints line boxes as JSON. This mirrors how the
 * Linux build shells out to the `tesseract` CLI, so everything downstream (the
 * bubble repaint + translation overlay) is unchanged.
 *
 * Everything fails soft: when PowerShell or the OCR engine is unavailable the
 * caller gets `(emptyList, available = false)` and surfaces an "OCR unavailable"
 * hint instead of crashing the reader.
 *
 * The recognition pipeline mirrors the nyora-android / Linux approach:
 *  1. Preprocess (upscale -> grayscale -> mild contrast boost) so text pops over
 *     screentone before OCR. Boxes are scaled back to original pixels afterwards.
 *  2. Run Windows OCR and read its line-level boxes.
 *  3. Cluster lines into speech bubbles by spatial proximity.
 *  4. Drop page-sized "bubbles" (art) and blank ones.
 */
object WindowsOcr {

    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_PROCESS_OUTPUT_BYTES = 1 * 1024 * 1024
    private const val TERMINATE_GRACE_SECONDS = 3L

    /** Windows PowerShell hosts to probe, in order. PowerShell 7 (`pwsh`) lacks
     *  the WinRT projection the OCR script relies on, so it is intentionally not
     *  a candidate. */
    private val HOST_CANDIDATES = listOf(
        "powershell.exe",
        "powershell",
        "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
    )

    /**
     * Extract the bundled OCR script for one invocation. The caller deletes it in
     * a finally block once PowerShell exits, avoiding process-lifetime temp files.
     */
    private fun extractScript(): java.nio.file.Path? {
        return runCatching {
            val text = WindowsOcr::class.java.getResourceAsStream("/windows_ocr.ps1")
                ?.bufferedReader()?.use { it.readText() }
                ?: return@runCatching null
            val tmp = Files.createTempFile("nyora_win_ocr_", ".ps1")
            try {
                Files.writeString(tmp, text)
                tmp
            } catch (t: Throwable) {
                runCatching { Files.deleteIfExists(tmp) }
                throw t
            }
        }.getOrNull()
    }

    /** Locate a working Windows PowerShell host, or null if none run. */
    fun findHost(): String? {
        for (host in HOST_CANDIDATES) {
            val ok = runCatching {
                val process = ProcessBuilder(host, "-NoProfile", "-Command", "exit 0")
                    .redirectErrorStream(true)
                    .start()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly(); false
                } else {
                    process.exitValue() == 0
                }
            }.getOrDefault(false)
            if (ok) return host
        }
        return null
    }

    /**
     * Recognize text in [imageBytes] for the given BCP-47 [lang] (e.g. "ja",
     * "zh-Hans", "ko", "en"). Pass "" to use the user's profile languages.
     *
     * Returns `(bubbles, available)`:
     * - `available` is false when no PowerShell host runs, the script could not be
     *   extracted, or the Windows OCR engine reported itself unavailable (no
     *   language pack installed for the requested language and none in the user
     *   profile).
     * - On a recognition that simply finds nothing, an empty list is returned with
     *   `available == true`.
     *
     * Bubble boxes are clustered and reported in ORIGINAL image pixel coordinates.
     */
    suspend fun recognize(
        imageBytes: ByteArray,
        lang: String = "ja",
    ): Pair<List<OcrBox>, Boolean> = withContext(Dispatchers.IO) {
        val host = findHost() ?: return@withContext Pair(emptyList(), false)
        val script = extractScript() ?: return@withContext Pair(emptyList(), false)

        try {
            runCatching {
                val original = ImageIO.read(ByteArrayInputStream(imageBytes))
                    ?: return@runCatching Pair(emptyList<OcrBox>(), true)
                val imgW = original.width
                val imgH = original.height
                if (imgW <= 0 || imgH <= 0) return@runCatching Pair(emptyList<OcrBox>(), true)

                // Avoid blowing past the OCR engine's max dimension on tall webtoon pages.
                val scale = if (maxOf(imgW, imgH) * 1.5f > 4000f) 1.0f else 1.5f
                val preprocessed = preprocess(original, scale)

                val tmp = Files.createTempFile("nyora_ocr_", ".png")
                try {
                    ImageIO.write(preprocessed, "png", tmp.toFile())

                    val stdout = runOcrProcess(host, script, tmp, lang)
                        ?: return@runCatching Pair(emptyList<OcrBox>(), true)

                    val payload = parsePayload(stdout)
                        ?: return@runCatching Pair(emptyList<OcrBox>(), true)
                    if (!payload.available) return@runCatching Pair(emptyList<OcrBox>(), false)

                    // Lines come back in PREPROCESSED (scaled) coords; convert to original.
                    val words = payload.lines.mapNotNull { it.toWord(scale) }
                    val bubbles = clusterIntoBubbles(words, imgW, imgH, isCjk(lang))
                    Pair(bubbles, true)
                } finally {
                    runCatching { Files.deleteIfExists(tmp) }
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Pair(emptyList(), true)
            }
        } finally {
            runCatching { Files.deleteIfExists(script) }
        }
    }

    /** Bounded, concurrently drained PowerShell output with an effective timeout. */
    private fun runOcrProcess(
        host: String,
        script: java.nio.file.Path,
        image: java.nio.file.Path,
        lang: String,
    ): String? {
        val process = runCatching {
            ProcessBuilder(
                host,
                "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", script.toAbsolutePath().toString(),
                "-ImagePath", image.toAbsolutePath().toString(),
                "-Lang", lang,
            ).redirectErrorStream(true).start()
        }.getOrNull() ?: return null

        val output = ByteArrayOutputStream()
        val outputExceeded = AtomicBoolean(false)
        val outputFailed = AtomicBoolean(false)
        val drainer = Thread({
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_PROCESS_OUTPUT_BYTES) {
                            outputExceeded.set(true)
                            process.destroyForcibly()
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } catch (_: Throwable) {
                outputFailed.set(true)
            }
        }, "NyoraWindowsOcrOutput").apply {
            isDaemon = true
            start()
        }

        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                terminate(process)
                return null
            }
            drainer.join(TimeUnit.SECONDS.toMillis(TERMINATE_GRACE_SECONDS))
            if (drainer.isAlive || outputExceeded.get() || outputFailed.get()) return null
            return output.toString(Charsets.UTF_8)
        } catch (interrupted: InterruptedException) {
            terminate(process)
            Thread.currentThread().interrupt()
            throw CancellationException("Windows OCR process interrupted").also { it.initCause(interrupted) }
        } catch (_: Throwable) {
            terminate(process)
            return null
        } finally {
            runCatching { process.inputStream.close() }
            if (drainer.isAlive) drainer.interrupt()
        }
    }

    private fun terminate(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (!runCatching { process.waitFor(TERMINATE_GRACE_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)) {
            process.destroyForcibly()
            runCatching { process.waitFor(TERMINATE_GRACE_SECONDS, TimeUnit.SECONDS) }
        }
    }

    private fun isCjk(lang: String): Boolean {
        val l = lang.lowercase()
        // Korean keeps inter-word spaces, so only Japanese/Chinese get spaces stripped.
        return l.startsWith("ja") || l.startsWith("zh")
    }

    /**
     * Preprocess the page for OCR: upscale [scale]x (bilinear), convert to
     * grayscale, then apply a mild contrast boost centered on mid-grey so dialogue
     * text separates from screentone. Windows OCR is fairly robust, so the boost is
     * gentler than the Tesseract path.
     */
    private fun preprocess(original: BufferedImage, scale: Float): BufferedImage {
        val targetW = (original.width * scale).toInt().coerceAtLeast(1)
        val targetH = (original.height * scale).toInt().coerceAtLeast(1)

        val scaled = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val scaleOp = AffineTransformOp(
            AffineTransform.getScaleInstance(
                targetW.toDouble() / original.width,
                targetH.toDouble() / original.height,
            ),
            AffineTransformOp.TYPE_BILINEAR,
        )
        scaleOp.filter(toRgb(original), scaled)

        val grayConv = ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null)
        val gray = BufferedImage(targetW, targetH, BufferedImage.TYPE_BYTE_GRAY)
        grayConv.filter(scaled, gray)

        val contrast = 1.2f
        val offset = (1f - contrast) * 128f
        val out = BufferedImage(targetW, targetH, BufferedImage.TYPE_BYTE_GRAY)
        RescaleOp(contrast, offset, null).filter(gray, out)
        return out
    }

    /** Ensure we operate on a plain RGB image (drops alpha / odd color models). */
    private fun toRgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_RGB) return src
        val rgb = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        try {
            g.drawImage(src, 0, 0, null)
        } finally {
            g.dispose()
        }
        return rgb
    }

    /** Extract the JSON object the script prints (last `{...}` line of stdout). */
    private fun parsePayload(stdout: String): OcrPayload? {
        val line = stdout.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("{") && it.endsWith("}") }
            ?: return null
        return runCatching { json.decodeFromString<OcrPayload>(line) }.getOrNull()
    }

    /**
     * Cluster line boxes into speech bubbles by spatial proximity ([isClose]),
     * merging transitively. Each cluster becomes one [OcrBox] (merged bounding box
     * + reading-order text + average confidence). Page-sized clusters (area > 55% of
     * the page) and blank clusters are dropped. When [stripSpaces] is set (CJK), the
     * spaces Windows OCR inserts between Japanese/Chinese glyphs are removed.
     */
    private fun clusterIntoBubbles(
        words: List<Word>,
        imgW: Int,
        imgH: Int,
        stripSpaces: Boolean,
    ): List<OcrBox> {
        if (words.isEmpty()) return emptyList()

        val pageArea = imgW.toLong() * imgH.toLong()
        val sorted = words.indices.sortedWith(compareBy({ words[it].top }, { words[it].left }))
        val used = HashSet<Int>()
        val bubbles = ArrayList<OcrBox>()

        for (seed in sorted) {
            if (seed in used) continue
            val cluster = ArrayList<Word>()
            cluster.add(words[seed])
            used.add(seed)

            var changed: Boolean
            do {
                changed = false
                for (j in sorted) {
                    if (j in used) continue
                    val candidate = words[j]
                    if (cluster.any { isClose(it, candidate) }) {
                        cluster.add(candidate)
                        used.add(j)
                        changed = true
                    }
                }
            } while (changed)

            val minLeft = cluster.minOf { it.left }
            val minTop = cluster.minOf { it.top }
            val maxRight = cluster.maxOf { it.left + it.width }
            val maxBottom = cluster.maxOf { it.top + it.height }

            var text = cluster
                .sortedWith(compareBy({ it.top }, { it.left }))
                .joinToString(" ") { it.text }
                .trim()
            if (stripSpaces) text = text.replace(" ", "")
            if (text.isEmpty()) continue

            val area = (maxRight - minLeft).toLong() * (maxBottom - minTop).toLong()
            if (area > pageArea * 0.55) continue

            bubbles.add(
                OcrBox(
                    left = minLeft,
                    top = minTop,
                    width = (maxRight - minLeft).coerceAtLeast(1),
                    height = (maxBottom - minTop).coerceAtLeast(1),
                    text = text,
                    conf = cluster.map { it.conf }.average().toFloat(),
                ),
            )
        }
        return bubbles
    }

    /**
     * Two boxes belong to the same bubble if they are vertically adjacent with
     * horizontal overlap, horizontally adjacent with vertical overlap, or they
     * intersect. Mirrors the nyora-android / Linux `isClose` heuristic.
     */
    private fun isClose(a: Word, b: Word): Boolean {
        val aRight = a.left + a.width
        val aBottom = a.top + a.height
        val bRight = b.left + b.width
        val bBottom = b.top + b.height

        val hOverlap = (minOf(aRight, bRight) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val vOverlap = (minOf(aBottom, bBottom) - maxOf(a.top, b.top)).coerceAtLeast(0)

        val vGap = when {
            aBottom < b.top -> b.top - aBottom
            bBottom < a.top -> a.top - bBottom
            else -> 0
        }
        val hGap = when {
            aRight < b.left -> b.left - aRight
            bRight < a.left -> a.left - bRight
            else -> 0
        }

        val minW = minOf(a.width, b.width).toFloat()
        val minH = minOf(a.height, b.height).toFloat()
        val avgW = (a.width + b.width) / 2f
        val avgH = (a.height + b.height) / 2f

        val verticalNeighbor = vGap <= maxOf(40f, avgH * 0.9f) && hOverlap >= minW * 0.2f
        val horizontalNeighbor = hGap <= maxOf(40f, avgW * 0.6f) && vOverlap >= minH * 0.2f
        val intersects = hOverlap > 0 && vOverlap > 0

        return verticalNeighbor || horizontalNeighbor || intersects
    }

    /** A single OCR'd line, already converted to ORIGINAL image pixels. */
    private data class Word(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val text: String,
        val conf: Float,
    )

    @Serializable
    private data class OcrPayload(
        val available: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
        val lines: List<OcrLineDto> = emptyList(),
    )

    @Serializable
    private data class OcrLineDto(
        val text: String = "",
        val x: Double = 0.0,
        val y: Double = 0.0,
        val w: Double = 0.0,
        val h: Double = 0.0,
    ) {
        fun toWord(scale: Float): Word? {
            if (text.isBlank() || w <= 0 || h <= 0) return null
            return Word(
                left = (x / scale).toInt(),
                top = (y / scale).toInt(),
                width = (w / scale).toInt().coerceAtLeast(1),
                height = (h / scale).toInt().coerceAtLeast(1),
                text = text.trim(),
                conf = 90f, // Windows OCR doesn't expose per-line confidence.
            )
        }
    }
}
