package com.nyora.windows.ai.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device manga speech-bubble detector — a JVM/onnxruntime port of the
 * detection half of nyora-web's core/translate/tl-worker.js.
 *
 * Runs Kiuyha/Manga-Bubble-YOLO yolo26n.onnx (~6 MB, Apache-2.0). It is an
 * END-TO-END YOLO export: the output tensor is (1, 300, 6) laid out as
 * [x1, y1, x2, y2, score, cls] in INPUT (1280×1280 letterboxed) space, so the
 * boxes are decoded directly — NO NMS is required. Everything runs locally;
 * page images never leave the device.
 *
 * Pipeline (identical to the web reference `detectTile` + `dedupe`):
 *   1. Letterbox the source into a 1280×1280 square, preserving aspect ratio:
 *      scale = min(SIZE/w, SIZE/h), draw at the top-left, pad the remainder with
 *      the YOLO grey (114,114,114).
 *   2. Build a float32 [1,3,1280,1280] CHW / RGB-in-0..1 tensor and run inference.
 *   3. Decode each of the 300 rows: keep score >= DETECTOR_THRESHOLD (0.2),
 *      un-letterbox (divide by scale) back to ORIGINAL pixels, drop boxes <= 6px.
 *      The low bar is intentional — bubbles score 0.8+, but free text (shouts,
 *      signs, credits) lands at 0.2–0.5; IoU-dedupe + the empty-read filter
 *      downstream clean up any false positives.
 *   4. IoU-dedupe (best score wins at IoU > 0.45), then drop nested boxes —
 *      when the model marks both a bubble and the text inside it, keep the
 *      container (overlap / inner-area > 0.75). Finally sort into manga reading
 *      order: top-to-bottom, then right-to-left.
 *
 * Fail-soft: any error (download, session, inference) yields an empty list.
 */
object OnnxDetector {

    /**
     * Kiuyha/Manga-Bubble-YOLO, pinned to a commit SHA so the bytes can't be
     * swapped under us. See tl-worker.js DETECTOR_URL.
     */
    const val MODEL_URL =
        "https://huggingface.co/Kiuyha/Manga-Bubble-YOLO/resolve/fb646500455e8a8a3a807fd27b855c8e4fc63766/onnx/yolo26n.onnx"

    /**
     * Expected SHA-256 = Hugging Face's LFS oid (the content hash). Taken from
     * tl-worker.js MODEL_SHA256['yolo26n.onnx']. Verified by OnnxModels.ensure
     * before the bytes ever reach the native ONNX protobuf parser.
     */
    const val MODEL_SHA256 = "b45c2e12cf0c3c1d2abfbbb9123c9f96f040f2ac36a0842382ecd9d859c851c7"

    /** Approximate download size, used only for progress when no Content-Length. */
    const val MODEL_BYTES = 6_100_000L

    /** yolo26n was trained at 1280×1280 (DETECTOR_SIZE in the web source). */
    private const val SIZE = 1280

    /** DETECTOR_THRESHOLD — keep the bar low; dedupe + empty-read filter clean up. */
    private const val THRESHOLD = 0.2f

    /** IoU above which two boxes are considered the same detection. */
    private const val IOU_DEDUPE = 0.45

    /** Fraction of the smaller box that must be covered to call it "nested". */
    private const val NEST_COVER = 0.75

    /** A detected bubble/text box in ORIGINAL image pixels. */
    data class DetBox(val x: Int, val y: Int, val w: Int, val h: Int, val score: Float)

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var session: OrtSession? = null

    /** True once the model is downloaded + verified (gate the Translate toggle on this). */
    fun isReady(): Boolean = OnnxModels.isCached(MODEL_SHA256)

    /** Download + verify the model with progress (0..100), without opening a session. */
    fun downloadModel(onProgress: (Int) -> Unit = {}) {
        OnnxModels.ensure(MODEL_URL, MODEL_SHA256, MODEL_BYTES, onProgress)
    }

    private fun ensureSession(): OrtSession {
        session?.let { return it }
        synchronized(this) {
            session?.let { return it }
            val f = OnnxModels.ensure(MODEL_URL, MODEL_SHA256, MODEL_BYTES)
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return env.createSession(f.absolutePath, opts).also { session = it }
        }
    }

    /**
     * Detect speech-bubble / free-text boxes in [image]. Returns boxes in the
     * ORIGINAL image's pixel coordinates, deduped by IoU, each with score >= 0.2,
     * in manga reading order. Returns an empty list on any failure.
     */
    fun detect(image: BufferedImage): List<DetBox> {
        return try {
            val ow = image.width
            val oh = image.height
            if (ow <= 0 || oh <= 0) return emptyList()

            // 1. Letterbox into SIZE×SIZE, preserving aspect, top-left, grey pad.
            val scale = min(SIZE.toDouble() / ow, SIZE.toDouble() / oh)
            val dw = max(1, (ow * scale).roundToInt())
            val dh = max(1, (oh * scale).roundToInt())
            val canvas = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
            canvas.createGraphics().apply {
                setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                color = Color(114, 114, 114)
                fillRect(0, 0, SIZE, SIZE)
                drawImage(image, 0, 0, dw, dh, null)
                dispose()
            }

            // 2. float32 [1,3,SIZE,SIZE], CHW, RGB in 0..1.
            val plane = SIZE * SIZE
            val input = FloatArray(3 * plane)
            var i = 0
            for (y in 0 until SIZE) {
                for (x in 0 until SIZE) {
                    val rgb = canvas.getRGB(x, y)
                    input[i] = ((rgb shr 16) and 0xFF) / 255f
                    input[i + plane] = ((rgb shr 8) and 0xFF) / 255f
                    input[i + 2 * plane] = (rgb and 0xFF) / 255f
                    i++
                }
            }

            // 3. inference → (1, 300, 6).
            val session = ensureSession()
            val inName = session.inputNames.first()
            val raw: FloatArray
            val rows: Int
            val stride: Int
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()),
            ).use { tensor ->
                session.run(mapOf(inName to tensor)).use { out ->
                    val t = out.get(0) as OnnxTensor
                    val dims = t.info.shape // [1, 300, 6]
                    if (dims.size != 3 || dims[2] < 6) return emptyList()
                    rows = dims[1].toInt()
                    stride = dims[2].toInt()
                    raw = FloatArray(rows * stride)
                    t.floatBuffer.get(raw)
                }
            }

            // 4. decode + un-letterbox into original pixels.
            val boxes = ArrayList<DetBox>()
            for (r in 0 until rows) {
                val o = r * stride
                val score = raw[o + 4]
                if (score < THRESHOLD) continue
                val x1 = raw[o] / scale
                val y1 = raw[o + 1] / scale
                val x2 = raw[o + 2] / scale
                val y2 = raw[o + 3] / scale
                val bx = max(0, x1.roundToInt())
                val by = max(0, y1.roundToInt())
                val bw = (x2 - x1).roundToInt()
                val bh = (y2 - y1).roundToInt()
                if (bw > 6 && bh > 6) {
                    boxes.add(DetBox(bx, by, bw, bh, score))
                }
            }

            dedupe(boxes)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Intersection area of two boxes (0 if they don't overlap). */
    private fun overlapArea(a: DetBox, b: DetBox): Long {
        val w = min(a.x + a.w, b.x + b.w) - max(a.x, b.x)
        val h = min(a.y + a.h, b.y + b.h) - max(a.y, b.y)
        return if (w > 0 && h > 0) w.toLong() * h.toLong() else 0L
    }

    /**
     * Port of tl-worker.js `dedupe`: IoU-suppress duplicates (best score wins),
     * drop nested inner boxes (keep the container), then manga reading order.
     */
    private fun dedupe(boxes: List<DetBox>): List<DetBox> {
        // 1. IoU-suppress, highest score first.
        val bySoc = boxes.sortedByDescending { it.score }
        val kept = ArrayList<DetBox>()
        for (b in bySoc) {
            val bArea = b.w.toLong() * b.h.toLong()
            val dup = kept.any { k ->
                val ov = overlapArea(k, b)
                val union = k.w.toLong() * k.h.toLong() + bArea - ov
                union > 0 && ov.toDouble() / union.toDouble() > IOU_DEDUPE
            }
            if (!dup) kept.add(b)
        }

        // 2. Drop nested boxes — keep the larger container.
        val byArea = kept.sortedByDescending { it.w.toLong() * it.h.toLong() }
        val out = ArrayList<DetBox>()
        for (b in byArea) {
            val bArea = b.w.toLong() * b.h.toLong()
            val inside = out.any { k ->
                bArea > 0 && overlapArea(k, b).toDouble() / bArea.toDouble() > NEST_COVER
            }
            if (!inside) out.add(b)
        }

        // 3. Reading order: top-to-bottom, then right-to-left (manga).
        return out.sortedWith(
            compareBy(
                { it.y + it.h / 2.0 },
                { -(it.x + it.w / 2.0) },
            ),
        )
    }
}
