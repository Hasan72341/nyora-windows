package com.nyora.windows.ai.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device Japanese manga OCR — a JVM/onnxruntime port of nyora-web's
 * core/translate/tl-worker.js (the `ja` engine / `loadMangaOcr` +
 * `mangaOcrBatch` path). Runs kha-white/manga-ocr — a ViT image encoder feeding
 * a character-level Japanese BERT decoder — as a VisionEncoderDecoder, greedy
 * decoded by hand because the ONNX export has no KV cache / merged decoder, so
 * transformers.js (and any high-level runtime) can't drive it.
 *
 * Models (quantized ONNX from onnx-community/manga-ocr-base-ONNX, Apache-2.0,
 * ~87 MB encoder + ~30 MB decoder) and the plain-text vocab from
 * kha-white/manga-ocr-base, both pinned to the exact commit the web worker uses.
 *
 * Pipeline (identical to the web reference):
 *   1. squash the crop to SIZE=224×224 RGB on a white ground (ViTImageProcessor),
 *      normalize per channel as (x/255 − .5)/.5 = x/127.5 − 1, CHW float32.
 *   2. encoder → hidden states [1, T, C].
 *   3. greedy autoregressive decode: seed with START=[CLS]=2, feed the growing id
 *      sequence + hidden states back into the decoder each step (no KV cache),
 *      argmax the last position, stop at EOS=[SEP]=3 or MAX_TOKENS=64.
 *   4. detokenize via vocab.txt: drop specials ([PAD]/[CLS]/[SEP]/<unusedN>…),
 *      strip '##' wordpiece continuations, join with no spaces (Japanese), then
 *      manga-ocr's post_process (h2z full-width, '…'→'...', dot-run collapse).
 *
 * Fail-soft: any download / session / inference error yields "".
 */
object MangaOcr {

    // Model choice follows nyora-mac (NativeOcrProvider): the ENCODER is the fp16
    // export (fp32 I/O, fp16 compute) — more accurate than the web's uint8 variant,
    // which the web only uses because its wasm EP lacks a ConvInteger kernel. The
    // decoder stays uint8 (autoregressive, per-token on CPU, takes fp32
    // encoder_hidden_states). Same HF commit as the web.
    private const val ENCODER_URL =
        "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/f9023406bb2f6b17df67bc4a327c56ecd20611f0/onnx/encoder_model_fp16.onnx"
    private const val DECODER_URL =
        "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/f9023406bb2f6b17df67bc4a327c56ecd20611f0/onnx/decoder_model_uint8.onnx"
    private const val VOCAB_URL =
        "https://huggingface.co/kha-white/manga-ocr-base/resolve/aa6573bd10b0d446cbf622e29c3e084914df9741/vocab.txt"

    // SHA-256s follow nyora-mac's verified catalogue (HF LFS oids): encoder fp16 +
    // decoder uint8. The vocab digest was computed from the pinned commit's bytes
    // (mac/web skip hashing plain-text dicts); re-verify it here anyway.
    private const val ENCODER_SHA256 = "1a6a57bc3608195c4577b13ac3aadab810dce42fa22c5a3acf0570bffc013b60"
    private const val DECODER_SHA256 = "cc7a42534759864c7b6937aaacc4cc91b37c9207eeae05ee359a04e6d4d222a5"
    private const val VOCAB_SHA256 = "344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d"

    private const val ENCODER_BYTES = 87_000_000L
    private const val DECODER_BYTES = 30_000_000L
    private const val VOCAB_BYTES = 24_072L

    private const val SIZE = 224          // ViTImageProcessor: 224×224, (x/255 − .5)/.5
    private const val START = 2L          // [CLS] (decoder_start_token_id)
    private const val EOS = 3L            // [SEP] (eos_token_id)
    private const val MAX_TOKENS = 64     // bubbles are short; generation cap

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var encoder: OrtSession? = null
    @Volatile private var decoder: OrtSession? = null
    @Volatile private var vocab: List<String>? = null

    /** True once both models + the vocab are downloaded and verified. */
    fun isReady(): Boolean =
        OnnxModels.isCached(ENCODER_SHA256) &&
            OnnxModels.isCached(DECODER_SHA256) &&
            OnnxModels.isCached(VOCAB_SHA256)

    /**
     * Download + verify the encoder, decoder and vocab with a single 0..100
     * progress stream (weighted by download size), without building sessions.
     */
    fun downloadModels(onProgress: (Int) -> Unit = {}) {
        // Weight the shared bar by byte size so it advances smoothly across files.
        val encW = 0.72; val decW = 0.26; val vocW = 0.02
        var encP = 0; var decP = 0; var vocP = 0
        fun push() { onProgress(minOf(100, (encP * encW + decP * decW + vocP * vocW).toInt())) }
        OnnxModels.ensure(ENCODER_URL, ENCODER_SHA256, ENCODER_BYTES) { encP = it; push() }
        OnnxModels.ensure(DECODER_URL, DECODER_SHA256, DECODER_BYTES) { decP = it; push() }
        OnnxModels.ensure(VOCAB_URL, VOCAB_SHA256, VOCAB_BYTES) { vocP = it; push() }
        onProgress(100)
    }

    private fun ensureLoaded(onProgress: (Int) -> Unit = {}) {
        if (encoder != null && decoder != null && vocab != null) return
        synchronized(this) {
            if (encoder != null && decoder != null && vocab != null) return
            downloadModels(onProgress)
            val encFile = OnnxModels.modelFile(ENCODER_SHA256)
            val decFile = OnnxModels.modelFile(DECODER_SHA256)
            val vocFile = OnnxModels.modelFile(VOCAB_SHA256)
            encoder = env.createSession(encFile.absolutePath, OrtSession.SessionOptions())
            decoder = env.createSession(decFile.absolutePath, OrtSession.SessionOptions())
            vocab = loadVocab(vocFile)
        }
    }

    private fun loadVocab(f: File): List<String> =
        // Match the web: split on '\n', strip a trailing '\r'; line index == token id.
        f.readText(Charsets.UTF_8).split('\n').map { it.removeSuffix("\r") }

    /**
     * Recognize the Japanese text in [crop]. Returns "" on any failure (missing
     * models, decode error) — the caller treats an empty result as "no text".
     */
    fun recognize(crop: BufferedImage): String {
        return try {
            ensureLoaded()
            val enc = encoder ?: return ""
            val dec = decoder ?: return ""
            val vocab = vocab ?: return ""

            // 1. preprocess → [1,3,224,224] float32.
            val input = preprocess(crop)
            val encIn = enc.inputNames.first()
            val encOut = enc.outputNames.first()

            // 2. encoder → hidden states [1, T, C].
            val hidden: FloatArray
            val T: Long
            val C: Long
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
                enc.run(mapOf(encIn to t)).use { out ->
                    val h = out.get(0) as OnnxTensor
                    val dims = h.info.shape // [1, T, C]
                    T = dims[1]
                    C = dims[2]
                    hidden = FloatArray((T * C).toInt())
                    h.floatBuffer.get(hidden)
                }
            }

            // 3. greedy autoregressive decode. No KV cache: re-feed the full id
            //    sequence + hidden states each step, argmax the last position.
            val seq = ArrayList<Long>(MAX_TOKENS + 1)
            seq.add(START)
            for (step in 0 until MAX_TOKENS) {
                val len = seq.size
                val ids = LongArray(len) { seq[it] }
                var tok: Long
                OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, len.toLong())).use { idTensor ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(hidden), longArrayOf(1, T, C)).use { hidTensor ->
                        dec.run(mapOf("input_ids" to idTensor, "encoder_hidden_states" to hidTensor)).use { out ->
                            val logits = out.get(0) as OnnxTensor
                            val dims = logits.info.shape // [1, len, V]
                            val V = dims[2].toInt()
                            val buf = logits.floatBuffer
                            // argmax over the last position's V logits.
                            val off = (len - 1) * V
                            var best = 0
                            var bestV = Float.NEGATIVE_INFINITY
                            for (v in 0 until V) {
                                val value = buf.get(off + v)
                                if (value > bestV) { bestV = value; best = v }
                            }
                            tok = best.toLong()
                        }
                    }
                }
                if (tok == EOS) break
                seq.add(tok)
            }

            // 4. detokenize: skip the START seed + specials, strip '##', join.
            val sb = StringBuilder()
            for (idx in 1 until seq.size) {
                val id = seq[idx].toInt()
                if (id == 0) continue // [PAD]
                val t = vocab.getOrNull(id) ?: continue
                if (t.isEmpty() || t.startsWith("[") || t.startsWith("<unused")) continue
                sb.append(if (t.startsWith("##")) t.substring(2) else t)
            }
            postprocess(sb.toString())
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Squash [crop] to 224×224 RGB on a white ground (ViTImageProcessor squashes
     * aspect) and normalize per channel to (x/255 − .5)/.5, CHW float32.
     */
    private fun preprocess(crop: BufferedImage): FloatArray {
        val canvas = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
        canvas.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, SIZE, SIZE)
            drawImage(crop, 0, 0, SIZE, SIZE, null)
            dispose()
        }
        val plane = SIZE * SIZE
        val input = FloatArray(3 * plane)
        var i = 0
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val rgb = canvas.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                input[i] = (r / 127.5f) - 1f
                input[i + plane] = (g / 127.5f) - 1f
                input[i + 2 * plane] = (b / 127.5f) - 1f
                i++
            }
        }
        return input
    }

    /**
     * manga-ocr's own post_process: strip whitespace → '…'→'...' → collapse runs
     * of ・/. to that many dots → half-width ASCII to full-width (h2z). JA only.
     */
    private fun postprocess(text: String): String {
        var t = text.replace(Regex("\\s+"), "")
        t = t.replace("…", "...")
        t = Regex("[・.]{2,}").replace(t) { m -> ".".repeat(m.value.length) }
        val sb = StringBuilder(t.length)
        for (c in t) {
            // half-width printable ASCII '!'..'~' → full-width (+0xFEE0).
            sb.append(if (c.code in 0x21..0x7E) (c.code + 0xFEE0).toChar() else c)
        }
        return sb.toString()
    }
}
