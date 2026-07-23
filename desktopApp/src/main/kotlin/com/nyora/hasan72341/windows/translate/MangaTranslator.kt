package com.nyora.windows.translate

import com.nyora.windows.ai.AiRefiner
import com.nyora.windows.ai.onnx.MangaMt
import com.nyora.windows.ai.onnx.MangaOcr
import com.nyora.windows.ai.onnx.OnnxColorizer
import com.nyora.windows.ai.onnx.OnnxDetector
import com.nyora.windows.ai.onnx.PaddleOcr
import com.nyora.windows.ai.onnx.SeriesGlossary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.UUID
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

/** The reason a colorization request did not produce an image. */
sealed class ColorizePageResult {
    data class Success(val path: String) : ColorizePageResult()
    object ModelUnavailable : ColorizePageResult()
    object InputTooLarge : ColorizePageResult()
    object Failed : ColorizePageResult()
}

/**
 * Orchestrates the OCR -> translate pipeline for a manga page. Every public
 * call fails soft: errors yield an empty translation rather than throwing.
 */
class MangaTranslator(colorizedCacheDirectory: File) {

    private val http = OkHttpClient()
    private val colorizedCache = ColorizedPageCache(colorizedCacheDirectory)

    /**
     * Reader pages come from third-party sources. Keep both compressed input and
     * decoded image sizes bounded before OCR/colorization allocates native or JVM
     * image buffers. The limits are deliberately high enough for normal manga
     * spreads while rejecting decompression bombs and corrupt oversized inputs.
     */
    private data class DownloadedImage(val bytes: ByteArray, val image: BufferedImage)

    private fun downloadImage(imageUrl: String): DownloadedImage? {
        val request = Request.Builder().url(imageUrl).get().build()
        val bytes = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_IMAGE_BYTES) return null

            body.byteStream().use { input ->
                val out = ByteArrayOutputStream(
                    declaredLength.coerceIn(0L, MAX_IMAGE_BYTES).toInt(),
                )
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read.toLong()
                    if (total > MAX_IMAGE_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }
        val image = decodeBoundedImage(bytes) ?: return null
        return DownloadedImage(bytes, image)
    }

    private fun decodeBoundedImage(bytes: ByteArray): BufferedImage? {
        return ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (
                    width !in 1..MAX_IMAGE_DIMENSION ||
                    height !in 1..MAX_IMAGE_DIMENSION ||
                    width.toLong() * height.toLong() > MAX_IMAGE_PIXELS
                ) {
                    return null
                }
                reader.read(0)
            } finally {
                reader.dispose()
            }
        }
    }

    /** Explicitly discard the transient colour cache when the reader moves on. */
    fun clearColorizedPageCache() = colorizedCache.clear()

    /** True only for a still-managed, still-present colourized page. */
    fun hasColorizedPage(path: String): Boolean = colorizedCache.contains(path)

    /** Removes a result that completed after its reader session was invalidated. */
    fun discardColorizedPage(path: String) = colorizedCache.remove(path)

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
            val downloaded = downloadImage(imageUrl)
                ?: return@runCatching PageTranslation(0, 0, emptyList(), true)
            // Decode the ORIGINAL image once; bubble boxes are in its pixel space.
            val image = downloaded.image
            val width = image.width
            val height = image.height

            val (boxes, available) = WindowsOcr.recognize(downloaded.bytes, ocrLang)
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
                try {
                    refiner.polish(machine, langName(target))
                        .let { if (it.size == machine.size) it else machine }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    machine
                }
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
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            PageTranslation(0, 0, emptyList(), true)
        }
    }

    /**
     * The nyora-web pipeline (core/translate/engine.js), run fully on-device via
     * onnxruntime: Manga-Bubble-YOLO detection → per-language OCR (manga-ocr for ja,
     * PP-OCR for zh/en/ko) → optional character-name glossary substitution → Google
     * gtx machine translation with manga repair (MangaMt) → optional LLM refinement.
     * Produces the same [PageTranslation] the overlay renders. Fails soft.
     *
     * [source] is one of ja|zh|ko|en. When the required models are not yet downloaded
     * the result carries ocrAvailable=false so the reader can prompt a download.
     */
    suspend fun translatePageImageOnnx(
        imageUrl: String,
        source: String,
        target: String,
        refineCfg: MangaMt.RefineCfg?,
        title: String,
        fandom: Boolean,
    ): PageTranslation = withContext(Dispatchers.IO) {
        runCatching {
            val src = when {
                source.startsWith("ja") -> "ja"
                source.startsWith("zh") -> "zh"
                source.startsWith("ko") -> "ko"
                source.startsWith("en") -> "en"
                else -> "ja"
            }
            val ocrReady = OnnxDetector.isReady() &&
                if (src == "ja") MangaOcr.isReady() else PaddleOcr.isReady(src)
            val image = downloadImage(imageUrl)?.image
                ?: return@runCatching PageTranslation(0, 0, emptyList(), true)
            val width = image.width
            val height = image.height
            if (!ocrReady) return@runCatching PageTranslation(width, height, emptyList(), false)

            // Detect bubbles, OCR each in reading order.
            val boxes = OnnxDetector.detect(image)
            val ocrBoxes = boxes.mapNotNull { b ->
                val x = b.x.coerceIn(0, width - 1)
                val y = b.y.coerceIn(0, height - 1)
                val w = b.w.coerceIn(1, width - x)
                val h = b.h.coerceIn(1, height - y)
                if (w < 2 || h < 2) return@mapNotNull null
                val crop = image.getSubimage(x, y, w, h)
                val text = if (src == "ja") MangaOcr.recognize(crop) else PaddleOcr.recognize(crop, src)
                if (text.isBlank()) null else OcrBox(x, y, w, h, text.trim(), b.score)
            }
            if (ocrBoxes.isEmpty()) return@runCatching PageTranslation(width, height, emptyList(), true)

            // Glossary: substitute canonical character names before MT (web engine.js).
            val rawTexts = ocrBoxes.map { it.text }
            val glossary = if (fandom) {
                try {
                    SeriesGlossary.resolve(title)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            } else null
            val hits = if (glossary != null) SeriesGlossary.detectNames(rawTexts, glossary.names) else emptyList()
            val srcTexts = SeriesGlossary.applyNames(rawTexts, hits)

            // Machine translation + manga repair.
            val mt = MangaMt.translateBatch(srcTexts, target, src)
            // Optional LLM refinement, focused glossary context. Falls back to MT.
            val finalTexts = if (refineCfg != null) {
                val ctx = SeriesGlossary.glossaryContext(glossary, hits)
                try {
                    MangaMt.refineBatch(srcTexts, mt, target, refineCfg.copy(context = ctx)) ?: mt
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    mt
                }
            } else mt

            val blocks = ocrBoxes.mapIndexedNotNull { index, box ->
                val translated = finalTexts.getOrElse(index) { box.text }
                if (translated.isBlank()) return@mapIndexedNotNull null
                val fillRgb = sampleBubbleBackground(image, box)
                val r = (fillRgb shr 16) and 0xFF
                val g = (fillRgb shr 8) and 0xFF
                val b = fillRgb and 0xFF
                val fillArgb = (0xFF000000.toInt()) or fillRgb
                val luminance = 0.299 * r + 0.587 * g + 0.114 * b
                val textArgb = if (luminance > 140) 0xFF1A1A1A.toInt() else 0xFFF5F5F5.toInt()
                TransBlock(
                    left = box.left, top = box.top, width = box.width, height = box.height,
                    original = box.text, translated = translated,
                    fillArgb = fillArgb, textArgb = textArgb,
                    fillPolygon = rayCastBubble(image, box, luminance),
                )
            }
            PageTranslation(width, height, blocks, true)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            PageTranslation(0, 0, emptyList(), true)
        }
    }

    /**
     * Colorize one page on-device (web core/colorize) into a bounded, explicitly
     * evicted cache. It never relies on process-exit cleanup, which otherwise accumulates
     * a PNG for every page across a long-lived desktop session.
     */
    suspend fun colorizePageImage(imageUrl: String): ColorizePageResult = withContext(Dispatchers.IO) {
        try {
            if (!OnnxColorizer.isReady()) return@withContext ColorizePageResult.ModelUnavailable
            colorizedCache.get(imageUrl)?.absolutePath?.let(ColorizePageResult::Success)
                ?: run {
                    val image = downloadImage(imageUrl)?.image ?: return@withContext ColorizePageResult.Failed
                    if (!OnnxColorizer.canColorize(image.width, image.height)) {
                        return@withContext ColorizePageResult.InputTooLarge
                    }
                    val out = OnnxColorizer.colorize(image)
                    colorizedCache.put(imageUrl, out)?.absolutePath
                        ?.let(ColorizePageResult::Success)
                        ?: ColorizePageResult.Failed
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The original source image remains visible when a model, decoder, or
            // cache write fails. Do not turn optional colorization into a reader
            // failure, but preserve cancellation above so stale work stops.
            ColorizePageResult.Failed
        }
    }

    private class ColorizedPageCache(private val configuredDirectory: File) {
        private data class Entry(val file: File, var sizeBytes: Long)

        private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
        private val directory: Path? = prepareDirectory()

        init {
            // Do not carry transient pages across launches. Only our own
            // filename pattern is touched, and never through a symlink.
            clearStaleArtifacts()
        }

        @Synchronized
        fun get(key: String): File? {
            val entry = entries[key] ?: return null
            if (!isManagedFile(entry.file)) {
                entries.remove(key)
                return null
            }
            return entry.file
        }

        @Synchronized
        fun contains(path: String): Boolean = entries.values.any { entry ->
            entry.file.absolutePath == path && isManagedFile(entry.file)
        }

        @Synchronized
        fun remove(path: String) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val (_, entry) = iterator.next()
                if (entry.file.absolutePath == path) {
                    iterator.remove()
                    deleteManagedFile(entry.file)
                    return
                }
            }
        }

        @Synchronized
        fun put(key: String, image: BufferedImage): File? {
            val dir = trustedDirectory() ?: return null
            val tmp = Files.createTempFile(dir, "color-", ".part")
            try {
                if (!ImageIO.write(image, "png", tmp.toFile())) return null
                val size = Files.size(tmp)
                if (size !in 1..MAX_COLORIZED_ENTRY_BYTES) return null

                val destination = promote(tmp, dir) ?: return null
                val destinationFile = destination.toFile()
                if (!isManagedFile(destinationFile)) {
                    deleteManagedFile(destinationFile)
                    return null
                }
                entries.remove(key)?.file?.let(::deleteManagedFile)
                entries[key] = Entry(destinationFile, size)
                evictToBounds()
                return entries[key]?.file
            } finally {
                runCatching { Files.deleteIfExists(tmp) }
            }
        }

        @Synchronized
        fun clear() {
            entries.values.forEach { deleteManagedFile(it.file) }
            entries.clear()
            clearStaleArtifacts()
        }

        private fun prepareDirectory(): Path? = runCatching {
            val path = configuredDirectory.toPath().toAbsolutePath().normalize()
            Files.createDirectories(path)
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return@runCatching null
            }
            path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()

        /**
         * Verify the cache root before each operation. The configured path is
         * app-owned, but a swapped directory/symlink must never make us write to
         * or render from an attacker-controlled location after construction.
         */
        private fun trustedDirectory(): Path? {
            val expected = directory ?: return null
            return runCatching {
                if (Files.isSymbolicLink(expected) || !Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS)) {
                    return@runCatching null
                }
                expected.toRealPath(LinkOption.NOFOLLOW_LINKS).takeIf { it == expected }
            }.getOrNull()
        }

        private fun isManagedFile(file: File): Boolean {
            val dir = trustedDirectory() ?: return false
            val path = file.toPath().toAbsolutePath().normalize()
            return path.parent == dir &&
                isCacheArtifact(path.fileName.toString()) &&
                !Files.isSymbolicLink(path) &&
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        }

        private fun deleteManagedFile(file: File) {
            val path = file.toPath().toAbsolutePath().normalize()
            if (isManagedFile(file)) runCatching { Files.deleteIfExists(path) }
        }

        private fun clearStaleArtifacts() {
            val dir = trustedDirectory() ?: return
            runCatching {
                Files.newDirectoryStream(dir).use { paths ->
                    paths.forEach { path ->
                        if (
                            isCacheArtifact(path.fileName.toString()) &&
                            !Files.isSymbolicLink(path) &&
                            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        ) {
                            Files.deleteIfExists(path)
                        }
                    }
                }
            }
        }

        private fun isCacheArtifact(name: String): Boolean =
            name.matches(CACHE_ARTIFACT_NAME)

        private fun evictToBounds() {
            var total = entries.values.sumOf { it.sizeBytes }
            val iterator = entries.entries.iterator()
            while (
                iterator.hasNext() &&
                (entries.size > MAX_COLORIZED_ENTRIES || total > MAX_COLORIZED_CACHE_BYTES)
            ) {
                val (_, entry) = iterator.next()
                iterator.remove()
                total -= entry.sizeBytes
                deleteManagedFile(entry.file)
            }
        }

        /**
         * Promote into a fresh UUID path without REPLACE_EXISTING. This means a
         * pre-existing file or symlink can only cause a retry, never replacement.
         */
        private fun promote(source: Path, dir: Path): Path? {
            repeat(8) {
                val destination = dir.resolve("color-${UUID.randomUUID()}.png")
                try {
                    return try {
                        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(source, destination)
                    }
                } catch (_: FileAlreadyExistsException) {
                    // A collision (or an untrusted pre-created path) is harmless:
                    // retain the private temp file and try another random name.
                }
            }
            return null
        }

        private companion object {
            val CACHE_ARTIFACT_NAME = Regex("color-[0-9a-fA-F-]+\\.(png|part)")
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 64L * 1024L * 1024L
        const val MAX_IMAGE_PIXELS = 40_000_000L
        const val MAX_IMAGE_DIMENSION = 16_384
        const val MAX_COLORIZED_ENTRIES = 12
        const val MAX_COLORIZED_ENTRY_BYTES = 64L * 1024L * 1024L
        const val MAX_COLORIZED_CACHE_BYTES = 192L * 1024L * 1024L
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
