package com.nyora.windows.ai.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

/**
 * On-device manga colorizer — a JVM/onnxruntime port of nyora-web's
 * core/colorize/worker.js, running manga-colorization-v2 (fp16 ONNX, ~62 MB, a
 * GAN trained on manga/anime art) entirely locally. Pages never leave the device.
 *
 * Pipeline (identical to the web reference):
 *   1. resize_pad to SIZE=576 (portrait → width=SIZE, landscape → height=SIZE*1.5),
 *      padded to a multiple of 32 with white.
 *   2. grayscale → input float32 [1,5,H,W] (ch0 = luma/255, ch1-4 = 0 = automatic).
 *   3. session → rgb float32 [1,3,H,W] in 0..1.
 *   4. upscale model colour to the original size, then luminance-combine: keep the
 *      SOURCE Y (crisp line art), take Cb/Cr from the model colour scaled by SAT=1.28.
 */
object OnnxColorizer {

    const val MODEL_URL =
        "https://huggingface.co/Faridzar/manga-colorization-v2-onnx/resolve/5515e06d31b08ffd107af686cba5e98e95e8d4cf/manga-colorize-fp16.onnx"
    const val MODEL_SHA256 = "39660d0047ea6f1a0ddee6aa89054997f95ea566f4d56ff762f66dbcf1a1a7ef"
    const val MODEL_BYTES = 61_650_260L

    private const val SIZE = 576
    private const val SAT = 1.28
    /** Full-resolution luminance recombination owns several RGB buffers. */
    private const val MAX_SOURCE_PIXELS = 10_000_000L
    private const val MAX_SOURCE_DIMENSION = 16_384
    /** Bound the five-channel input and native ONNX intermediates for extreme aspect ratios. */
    private const val MAX_MODEL_PIXELS = 1_500_000L
    private const val MAX_MODEL_DIMENSION = 4_096

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var session: OrtSession? = null
    private val runMutex = Mutex()

    private data class Geometry(
        val originalWidth: Int,
        val originalHeight: Int,
        val validWidth: Int,
        val validHeight: Int,
        val modelWidth: Int,
        val modelHeight: Int,
    ) {
        val plane: Int get() = modelWidth * modelHeight
    }

    /** A per-process verification cache; changed files are re-hashed before use. */
    private data class ModelFingerprint(val path: String, val bytes: Long, val modifiedAt: Long)
    @Volatile private var verifiedModel: ModelFingerprint? = null

    /** True once the model is downloaded + verified (gate the Colorize toggle on this). */
    fun isReady(): Boolean {
        val file = runCatching { OnnxModels.modelFile(MODEL_SHA256) }.getOrNull() ?: return false
        val before = fingerprint(file) ?: return false
        if (verifiedModel == before) return true

        val verified = runCatching { OnnxModels.isCached(MODEL_SHA256) }.getOrDefault(false)
        verifiedModel = if (verified) fingerprint(file) else null
        return verified && verifiedModel != null
    }

    /** Download + verify the model with progress, without spinning up the session. */
    fun downloadModel(onProgress: (Int) -> Unit = {}) {
        val file = OnnxModels.ensure(MODEL_URL, MODEL_SHA256, MODEL_BYTES, onProgress)
        verifiedModel = fingerprint(file)
    }

    private fun ensureSession(): OrtSession {
        session?.let { return it }
        synchronized(this) {
            session?.let { return it }
            // Downloading is an explicit Settings action. A reader colorization
            // request must never consume 62 MB unexpectedly after a race/delete.
            require(isReady()) { "Colorizer model is not downloaded or did not verify" }
            val f = OnnxModels.modelFile(MODEL_SHA256)
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return env.createSession(f.absolutePath, opts).also { session = it }
        }
    }

    /** Whether the source and derived tensor geometry are safe to colorize. */
    fun canColorize(width: Int, height: Int): Boolean = geometryFor(width, height) != null

    /** Colorize [src] → a new coloured [BufferedImage] at the original resolution. */
    suspend fun colorize(src: BufferedImage, onProgress: (Int) -> Unit = {}): BufferedImage {
        // Kept for API compatibility with model-download call sites. Inference
        // cannot download; progress is therefore only meaningful for downloads.
        @Suppress("UNUSED_VARIABLE") val ignoredProgress = onProgress
        val geometry = geometryFor(src.width, src.height)
            ?: throw IllegalArgumentException("Page is too large or too extreme to colorize safely")
        require(isReady()) { "Colorizer model is not downloaded or did not verify" }

        // Page navigation can enqueue several different indices. Serializing all
        // inference bounds native ORT/work-buffer memory and avoids running the
        // same mutable session concurrently.
        return runMutex.withLock {
            // Mutex acquisition is cancellable, so navigation away from a page
            // drops queued inference rather than running it after it is stale.
            coroutineContext.ensureActive()
            try {
                val output = colorize(ensureSession(), src, geometry)
                coroutineContext.ensureActive()
                output
            } catch (outOfMemory: OutOfMemoryError) {
                // Do not retain a potentially fragmented native session after an
                // allocation failure. The next explicit colorization can recover.
                closeSessionLocked()
                throw outOfMemory
            }
        }
    }

    private fun colorize(session: OrtSession, src: BufferedImage, geometry: Geometry): BufferedImage {
        val ow = geometry.originalWidth
        val oh = geometry.originalHeight
        val vw = geometry.validWidth
        val vh = geometry.validHeight
        val mw = geometry.modelWidth
        val mh = geometry.modelHeight

        // 1. resize_pad — valid (unpadded) region, then pad up to a multiple of 32.
        val modelCanvas = BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB)
        modelCanvas.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            color = Color.WHITE
            fillRect(0, 0, mw, mh)
            drawImage(src, 0, 0, vw, vh, null)
            dispose()
        }

        // 2. grayscale → 5-channel input (ch1-4 stay 0 = automatic colourisation).
        val plane = geometry.plane
        val input = FloatArray(5 * plane)
        var i = 0
        for (y in 0 until mh) {
            for (x in 0 until mw) {
                val rgb = modelCanvas.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                input[i++] = ((0.299 * r + 0.587 * g + 0.114 * b) / 255.0).toFloat()
            }
        }

        // 3. inference → rgb [1,3,mh,mw] in 0..1.
        val inName = session.inputNames.first()
        val rgb: FloatArray
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 5, mh.toLong(), mw.toLong())).use { tensor ->
            session.run(mapOf(inName to tensor)).use { out ->
                val t = out.get(0) as OnnxTensor
                rgb = FloatArray(3 * plane)
                t.floatBuffer.get(rgb)
            }
        }

        // model colour → mw×mh image
        val colorCanvas = BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB)
        for (p in 0 until plane) {
            val r = clamp255(rgb[p] * 255f)
            val g = clamp255(rgb[plane + p] * 255f)
            val b = clamp255(rgb[2 * plane + p] * 255f)
            colorCanvas.setRGB(p % mw, p / mw, (r shl 16) or (g shl 8) or b)
        }

        // upscale the VALID region of the model colour to the original size
        val colorFull = BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB)
        colorFull.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(colorCanvas.getSubimage(0, 0, vw.coerceAtMost(mw), vh.coerceAtMost(mh)), 0, 0, ow, oh, null)
            dispose()
        }

        // 4. combine: source Y (crisp lines) + model Cb/Cr * SAT.
        val outImg = BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                val o = src.getRGB(x, y)
                val or = (o shr 16) and 0xFF
                val og = (o shr 8) and 0xFF
                val ob = o and 0xFF
                val Y = 0.299 * or + 0.587 * og + 0.114 * ob

                val c = colorFull.getRGB(x, y)
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF
                val Cb = (-0.168736 * cr - 0.331264 * cg + 0.5 * cb) * SAT
                val Cr = (0.5 * cr - 0.418688 * cg - 0.081312 * cb) * SAT

                val rr = clamp255((Y + 1.402 * Cr).toFloat())
                val gg = clamp255((Y - 0.344136 * Cb - 0.714136 * Cr).toFloat())
                val bb = clamp255((Y + 1.772 * Cb).toFloat())
                outImg.setRGB(x, y, (rr shl 16) or (gg shl 8) or bb)
            }
        }
        return outImg
    }

    private fun geometryFor(width: Int, height: Int): Geometry? {
        if (
            width !in 1..MAX_SOURCE_DIMENSION ||
            height !in 1..MAX_SOURCE_DIMENSION ||
            width.toLong() * height.toLong() > MAX_SOURCE_PIXELS
        ) {
            return null
        }

        // The model is trained at a fixed short-side resolution. An excessively
        // tall/narrow webtoon image would otherwise turn that formula into a huge
        // 5-channel tensor despite a modest source pixel count.
        val rawValidWidth: Double
        val rawValidHeight: Double
        if (height < width) {
            rawValidHeight = SIZE * 1.5
            rawValidWidth = width.toDouble() / (height.toDouble() / rawValidHeight)
        } else {
            rawValidWidth = SIZE.toDouble()
            rawValidHeight = height.toDouble() / (width.toDouble() / SIZE)
        }
        if (
            !rawValidWidth.isFinite() || !rawValidHeight.isFinite() ||
            rawValidWidth !in 1.0..MAX_MODEL_DIMENSION.toDouble() ||
            rawValidHeight !in 1.0..MAX_MODEL_DIMENSION.toDouble()
        ) {
            return null
        }
        val validWidth = ceil(rawValidWidth).toInt()
        val validHeight = ceil(rawValidHeight).toInt()
        val modelWidth = roundUpTo32(validWidth)
        val modelHeight = roundUpTo32(validHeight)
        val plane = modelWidth.toLong() * modelHeight.toLong()
        if (
            modelWidth > MAX_MODEL_DIMENSION ||
            modelHeight > MAX_MODEL_DIMENSION ||
            plane !in 1..MAX_MODEL_PIXELS
        ) {
            return null
        }
        return Geometry(width, height, validWidth, validHeight, modelWidth, modelHeight)
    }

    private fun roundUpTo32(value: Int): Int =
        maxOf(32, ceil(value / 32.0).toInt() * 32)

    private fun fingerprint(file: java.io.File): ModelFingerprint? =
        if (file.isFile && file.length() in 1..MODEL_BYTES + MODEL_BYTES / 20) {
            ModelFingerprint(file.absolutePath, file.length(), file.lastModified())
        } else {
            null
        }

    /** Must be called with [runMutex] held. */
    private fun closeSessionLocked() {
        val old = session ?: return
        session = null
        runCatching { old.close() }
    }

    private fun clamp255(v: Float): Int = when {
        v < 0f -> 0
        v > 255f -> 255
        else -> v.toInt()
    }
}
