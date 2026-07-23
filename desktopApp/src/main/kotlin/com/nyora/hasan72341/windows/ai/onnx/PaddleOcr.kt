package com.nyora.windows.ai.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device Chinese / English / Korean OCR — a JVM/onnxruntime port of the
 * PP-OCR *line pipeline* in nyora-web's core/translate/tl-worker.js
 * (the `loadPaddle` / `detTextLines` / `paddleRecLine` / `paddleOcrCrop`
 * section, plus the PADDLE map + PADDLE_DET_URL). Everything runs locally;
 * bubble crops never leave the device.
 *
 * Pipeline, identical to the web reference:
 *   1. A shared **DB text-line detector** (PP-OCRv5_mobile_det) runs on the whole
 *      bubble crop. Its prob map is binarised (DET_BIN=0.3), connected components
 *      are collected, scored (mean prob > DET_BOX_SCORE=0.5) and expanded by the
 *      DB unclip ratio (DET_UNCLIP=2.0) into proper text-LINE boxes, returned in
 *      reading order (rows clustered by vertical overlap, top→bottom, left→right).
 *   2. Each line box is cropped, resized to height PADDLE_H=48 (width ∝ aspect,
 *      capped at PADDLE_MAX_W), BGR-normalised (x/255 − .5)/.5, and fed to the
 *      per-language **CTC recognizer**. Output is [1, T, C] (already softmaxed);
 *      greedy CTC over table = ["blank"] + dict + [" "] yields the line text.
 *   3. Line texts are joined with the language joiner ("" for zh, " " for en/ko).
 *
 * zh + en share one recognizer (PP-OCRv6_small_rec — covers CN + printed EN +
 * pinyin); ko uses korean_PP-OCRv5_mobile_rec (v6's dict has no Hangul).
 *
 * Fail-soft everywhere: any download / inference / decode error yields "".
 */
object PaddleOcr {

    // ---- model artefacts (URLs pinned to commit SHAs; SHA-256 = HF LFS oid) ----
    // The three ONNX SHA-256s are copied verbatim from tl-worker.js's MODEL_SHA256.

    private const val DET_URL =
        "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/e6f4fa85f00e168c862bc462aebca69eef9b3d3d/inference.onnx"
    private const val DET_SHA = "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d"
    private const val DET_BYTES = 4_900_000L

    private const val ZH_REC_URL =
        "https://huggingface.co/ogkalu/ppocr-v6-onnx/resolve/8caf024d9ec9df361c3b89adc812a68ae803ea1b/PP-OCRv6_small_rec.onnx"
    private const val ZH_REC_SHA = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634"
    private const val ZH_REC_BYTES = 21_200_000L
    private const val ZH_DICT_URL =
        "https://huggingface.co/ogkalu/ppocr-v6-onnx/resolve/8caf024d9ec9df361c3b89adc812a68ae803ea1b/PP-OCRv6_small_rec.txt"
    // The web reference does NOT hash the plain-text dicts (small non-LFS blobs
    // made immutable only by the commit pin). These SHA-256s were computed from
    // the pinned bytes for this port so OnnxModels can verify them like any model.
    // TODO(verify): re-confirm against the upstream commit if the pin is ever bumped.
    // RISK: if the pinned dict ever 404s or is force-pushed, download fails and the
    // language reports not-ready — recognition then fails soft to "".
    private const val ZH_DICT_SHA = "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d"

    private const val KO_REC_URL =
        "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/5c6f574b8e2230adf4287b33e736d71b9fabd28e/inference.onnx"
    private const val KO_REC_SHA = "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08"
    private const val KO_REC_BYTES = 13_400_000L
    private const val KO_DICT_URL =
        "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/0a8a6354f10388ecd601f9a86639dd3c44d95057/ppocr/utils/dict/ppocrv5_korean_dict.txt"
    private const val KO_DICT_SHA = "a88071c68c01707489baa79ebe0405b7beb5cca229f4fc94cc3ef992328802d7"

    // ---- pipeline constants (verbatim from tl-worker.js) -----------------------
    private const val PADDLE_H = 48
    private const val PADDLE_MAX_W = 1536
    private const val DET_MAX_SIDE = 960
    private const val DET_BIN = 0.3f
    private const val DET_BOX_SCORE = 0.5f
    private const val DET_UNCLIP = 2.0

    private data class LangCfg(
        val recUrl: String, val recSha: String, val recBytes: Long,
        val dictUrl: String, val dictSha: String, val joiner: String,
    )

    /** Per-language config. zh + en share the v6 recognizer (only the joiner differs). */
    private fun cfgFor(lang: String): LangCfg? = when (lang) {
        "zh" -> LangCfg(ZH_REC_URL, ZH_REC_SHA, ZH_REC_BYTES, ZH_DICT_URL, ZH_DICT_SHA, "")
        "en" -> LangCfg(ZH_REC_URL, ZH_REC_SHA, ZH_REC_BYTES, ZH_DICT_URL, ZH_DICT_SHA, " ")
        "ko" -> LangCfg(KO_REC_URL, KO_REC_SHA, KO_REC_BYTES, KO_DICT_URL, KO_DICT_SHA, " ")
        else -> null
    }

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** Shared DB text-line detector session (all three languages). */
    @Volatile private var detSession: OrtSession? = null

    /** Recognizer engine: rec session + CTC table, cached by rec SHA (en/zh share). */
    private class RecEngine(val rec: OrtSession, val table: List<String>)
    private val engines = HashMap<String, RecEngine>()

    // ---- public API ------------------------------------------------------------

    /**
     * True when every artefact this language needs (shared detector + its rec
     * model + its dict) is already downloaded and integrity-verified on disk.
     * Gate any "OCR ready" UI on this.
     */
    fun isReady(lang: String): Boolean {
        val cfg = cfgFor(lang) ?: return false
        return OnnxModels.isCached(DET_SHA) &&
            OnnxModels.isCached(cfg.recSha) &&
            OnnxModels.isCached(cfg.dictSha)
    }

    /**
     * Download + verify every artefact for [lang] (shared detector, rec model,
     * dict) with aggregated [onProgress] (0..100). Does not spin up sessions.
     * Throws on download/verification failure (mirrors OnnxModels.ensure).
     */
    fun downloadModels(lang: String, onProgress: (Int) -> Unit = {}) {
        val cfg = cfgFor(lang) ?: return
        // Weight the bar by rough byte share: detector ~0..18, rec ~18..96, dict ~96..100.
        OnnxModels.ensure(DET_URL, DET_SHA, DET_BYTES, sub(onProgress, 0, 18))
        OnnxModels.ensure(cfg.recUrl, cfg.recSha, cfg.recBytes, sub(onProgress, 18, 96))
        OnnxModels.ensure(cfg.dictUrl, cfg.dictSha, 0L, sub(onProgress, 96, 100))
        onProgress(100)
    }

    /**
     * Recognize the text in a single bubble [crop] for [lang] ∈ {"zh","en","ko"}:
     * run the DB line detector → CTC-decode each line → join with the lang joiner.
     * Returns "" on any error, an unsupported language, or an empty read.
     */
    fun recognize(crop: BufferedImage, lang: String): String {
        return try {
            val cfg = cfgFor(lang) ?: return ""
            val det = ensureDetSession()
            val eng = ensureRecEngine(cfg)

            var lines = detTextLines(det, crop)
            // Detector found nothing (tiny / odd crop) — treat the whole crop as one line.
            if (lines.isEmpty()) lines = listOf(Rect(0, 0, crop.width, crop.height))

            val parts = ArrayList<String>(lines.size)
            for (rect in lines) {
                val line = try {
                    recLine(eng, crop, rect)
                } catch (_: Throwable) {
                    "" // one bad line must not sink the crop
                }
                if (line.isNotEmpty()) parts.add(line)
            }
            parts.joinToString(cfg.joiner).trim()
        } catch (_: Throwable) {
            ""
        }
    }

    // ---- session / engine loading ---------------------------------------------

    private fun sessionFor(file: File): OrtSession {
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(file.absolutePath, opts)
    }

    private fun ensureDetSession(): OrtSession {
        detSession?.let { return it }
        synchronized(this) {
            detSession?.let { return it }
            val f = OnnxModels.ensure(DET_URL, DET_SHA, DET_BYTES)
            return sessionFor(f).also { detSession = it }
        }
    }

    private fun ensureRecEngine(cfg: LangCfg): RecEngine {
        engines[cfg.recSha]?.let { return it }
        synchronized(this) {
            engines[cfg.recSha]?.let { return it }
            val recFile = OnnxModels.ensure(cfg.recUrl, cfg.recSha, cfg.recBytes)
            val dictFile = OnnxModels.ensure(cfg.dictUrl, cfg.dictSha, 0L)
            val table = buildTable(dictFile)
            return RecEngine(sessionFor(recFile), table).also { engines[cfg.recSha] = it }
        }
    }

    /** CTC table: ["blank"] + dict + [" "] (use_space_char). Mirrors loadPaddle. */
    private fun buildTable(dictFile: File): List<String> {
        val dict = dictFile.readText(Charsets.UTF_8)
            .split('\n')
            .map { it.removeSuffix("\r") }
            .toMutableList()
        // A trailing newline leaves a final empty element — drop only that one.
        if (dict.isNotEmpty() && dict.last().isEmpty()) dict.removeAt(dict.size - 1)
        val table = ArrayList<String>(dict.size + 2)
        table.add("")            // index 0 = CTC blank
        table.addAll(dict)
        table.add(" ")           // trailing space class
        return table
    }

    // ---- DB text-line detector -------------------------------------------------

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * Run the DB detector on [crop] → line boxes in crop coordinates, reading order.
     * Port of detTextLines(): resize to a 32-multiple ≤ DET_MAX_SIDE, ImageNet-norm
     * BGR input, binarise the prob map, flood-fill components, score + unclip, then
     * cluster into rows and read top→bottom / left→right.
     */
    private fun detTextLines(det: OrtSession, crop: BufferedImage): List<Rect> {
        val w0 = crop.width
        val h0 = crop.height
        val scale = min(1.0, DET_MAX_SIDE.toDouble() / max(w0, h0))
        val w = max(32, (Math.round(w0 * scale / 32.0) * 32).toInt())
        val h = max(32, (Math.round(h0 * scale / 32.0) * 32).toInt())

        val rgb = renderScaled(crop, w, h)
        val plane = w * h
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val px = rgb[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            input[i] = ((b / 255f - 0.485f) / 0.229f)              // BGR, ImageNet mean/std
            input[i + plane] = ((g / 255f - 0.456f) / 0.224f)
            input[i + 2 * plane] = ((r / 255f - 0.406f) / 0.225f)
        }

        // out prob map [1,1,H,W]; H/W should equal our input h/w — read the real shape.
        val (prob, ph, pw) = runDet(det, input, w, h)
        if (prob.isEmpty()) return emptyList()

        val binPlane = ph * pw
        val bin = BooleanArray(binPlane) { prob[it] > DET_BIN }
        val seen = BooleanArray(binPlane)
        val qx = IntArray(binPlane)
        val qy = IntArray(binPlane)

        data class Box(val x: Int, val y: Int, val x2: Int, val y2: Int)
        val boxes = ArrayList<Box>()

        for (sy in 0 until ph) {
            for (sx in 0 until pw) {
                val idx = sy * pw + sx
                if (!bin[idx] || seen[idx]) continue
                var head = 0
                var tail = 0
                qx[tail] = sx; qy[tail] = sy; tail++
                seen[idx] = true
                var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
                var sum = 0f; var n = 0
                while (head < tail) {
                    val cxx = qx[head]; val cyy = qy[head]; head++
                    sum += prob[cyy * pw + cxx]; n++
                    if (cxx < minX) minX = cxx
                    if (cxx > maxX) maxX = cxx
                    if (cyy < minY) minY = cyy
                    if (cyy > maxY) maxY = cyy
                    // 4-connectivity
                    if (cxx + 1 < pw) { val ni = cyy * pw + (cxx + 1); if (bin[ni] && !seen[ni]) { seen[ni] = true; qx[tail] = cxx + 1; qy[tail] = cyy; tail++ } }
                    if (cxx - 1 >= 0) { val ni = cyy * pw + (cxx - 1); if (bin[ni] && !seen[ni]) { seen[ni] = true; qx[tail] = cxx - 1; qy[tail] = cyy; tail++ } }
                    if (cyy + 1 < ph) { val ni = (cyy + 1) * pw + cxx; if (bin[ni] && !seen[ni]) { seen[ni] = true; qx[tail] = cxx; qy[tail] = cyy + 1; tail++ } }
                    if (cyy - 1 >= 0) { val ni = (cyy - 1) * pw + cxx; if (bin[ni] && !seen[ni]) { seen[ni] = true; qx[tail] = cxx; qy[tail] = cyy - 1; tail++ } }
                }
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                if (bw < 3 || bh < 3 || n < 10) continue
                if (sum / n < DET_BOX_SCORE) continue
                // DB unclip: offset = area × ratio / perimeter
                val off = Math.round((bw.toDouble() * bh * DET_UNCLIP) / (2.0 * (bw + bh))).toInt()
                boxes.add(
                    Box(
                        x = max(0, minX - off),
                        y = max(0, minY - off),
                        x2 = min(pw, maxX + off),
                        y2 = min(ph, maxY + off),
                    )
                )
            }
        }

        val sxr = w0.toDouble() / pw
        val syr = h0.toDouble() / ph
        val lines = boxes.map { b ->
            Rect(
                x = Math.round(b.x * sxr).toInt(),
                y = Math.round(b.y * syr).toInt(),
                w = max(1, Math.round((b.x2 - b.x) * sxr).toInt()),
                h = max(1, Math.round((b.y2 - b.y) * syr).toInt()),
            )
        }
        return readingOrder(lines)
    }

    /** Runs the detector and returns (probData, height, width) from the real output shape. */
    private fun runDet(det: OrtSession, input: FloatArray, w: Int, h: Int): Triple<FloatArray, Int, Int> {
        val inName = det.inputNames.first()
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())
        ).use { tensor ->
            det.run(mapOf(inName to tensor)).use { out ->
                val t = out.get(0) as OnnxTensor
                val shape = (t.info as TensorInfo).shape // [1,1,H,W]
                val ph = if (shape.size >= 4) shape[shape.size - 2].toInt() else h
                val pw = if (shape.size >= 4) shape[shape.size - 1].toInt() else w
                val buf = t.floatBuffer
                val arr = FloatArray(buf.remaining())
                buf.get(arr)
                return Triple(arr, ph, pw)
            }
        }
    }

    /**
     * Cluster line boxes into rows by vertical-center overlap, then order rows
     * top→bottom and each row left→right. Port of the row-clustering tail of
     * detTextLines (the detector often splits one visual line into word boxes).
     */
    private fun readingOrder(lines: List<Rect>): List<Rect> {
        if (lines.size <= 1) return lines
        val sorted = lines.sortedBy { it.y + it.h / 2.0 }
        data class Row(var cy: Double, var h: Int, val items: MutableList<Rect>)
        val rows = ArrayList<Row>()
        for (l in sorted) {
            val cy = l.y + l.h / 2.0
            val row = rows.firstOrNull { abs(it.cy - cy) < max(it.h, l.h) * 0.55 }
            if (row != null) {
                row.items.add(l)
                row.cy = (row.cy * (row.items.size - 1) + cy) / row.items.size
                row.h = max(row.h, l.h)
            } else {
                rows.add(Row(cy, l.h, mutableListOf(l)))
            }
        }
        rows.sortBy { it.cy }
        return rows.flatMap { r -> r.items.sortedBy { it.x } }
    }

    // ---- CTC recognizer --------------------------------------------------------

    /**
     * Recognize one text line: crop [rect] from [crop], resize to height PADDLE_H
     * (width ∝ aspect, ≤ PADDLE_MAX_W), BGR-normalise (x/255 − .5)/.5, run the
     * recognizer → [1,T,C], greedy-CTC decode. Port of paddleRecLine.
     */
    private fun recLine(eng: RecEngine, crop: BufferedImage, rect: Rect): String {
        val w = max(16, min(PADDLE_MAX_W, Math.round(rect.w * (PADDLE_H.toDouble() / rect.h)).toInt()))
        val rgb = renderSubRect(crop, rect, w, PADDLE_H)
        val plane = PADDLE_H * w
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val px = rgb[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            input[i] = (b / 127.5f - 1f)               // BGR order, (x/255 − .5)/.5
            input[i + plane] = (g / 127.5f - 1f)
            input[i + 2 * plane] = (r / 127.5f - 1f)
        }

        val inName = eng.rec.inputNames.first()
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(input), longArrayOf(1, 3, PADDLE_H.toLong(), w.toLong())
        ).use { tensor ->
            eng.rec.run(mapOf(inName to tensor)).use { out ->
                val t = out.get(0) as OnnxTensor
                val shape = (t.info as TensorInfo).shape // [1, T, C]
                val tSteps = shape[1].toInt()
                val c = shape[2].toInt()
                val buf = t.floatBuffer
                val data = FloatArray(buf.remaining())
                buf.get(data)

                val sb = StringBuilder()
                var prev = 0
                for (s in 0 until tSteps) {
                    val off = s * c
                    var best = 0
                    var bestV = Float.NEGATIVE_INFINITY
                    for (i in 0 until c) {
                        val v = data[off + i]
                        if (v > bestV) { bestV = v; best = i }
                    }
                    if (best != 0 && best != prev) {
                        if (best < eng.table.size) sb.append(eng.table[best])
                    }
                    prev = best
                }
                return sb.toString().trim()
            }
        }
    }

    // ---- image helpers ---------------------------------------------------------

    /** Render [src] scaled to [w]×[h] and return its packed ARGB pixels (row-major). */
    private fun renderScaled(src: BufferedImage, w: Int, h: Int): IntArray {
        val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        canvas.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
        return canvas.getRGB(0, 0, w, h, IntArray(w * h), 0, w)
    }

    /** Render sub-[rect] of [src] scaled into [w]×[h] and return packed ARGB pixels. */
    private fun renderSubRect(src: BufferedImage, rect: Rect, w: Int, h: Int): IntArray {
        // Clamp the source rect into the image bounds (detector rects can round over).
        val sx = rect.x.coerceIn(0, src.width - 1)
        val sy = rect.y.coerceIn(0, src.height - 1)
        val sw = rect.w.coerceIn(1, src.width - sx)
        val sh = rect.h.coerceIn(1, src.height - sy)
        val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        canvas.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, sx, sy, sx + sw, sy + sh, null)
            dispose()
        }
        return canvas.getRGB(0, 0, w, h, IntArray(w * h), 0, w)
    }

    /** Map a 0..100 child progress onto the [lo,hi] slice of the parent bar. */
    private fun sub(onProgress: (Int) -> Unit, lo: Int, hi: Int): (Int) -> Unit =
        { p -> onProgress(lo + (p.coerceIn(0, 100) * (hi - lo)) / 100) }
}
