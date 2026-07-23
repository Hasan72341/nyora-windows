package com.nyora.windows.ai.onnx

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.max

/**
 * Shared model download + integrity cache for the on-device ONNX engines
 * (colorizer + translation vision stack). Mirrors nyora-web's model.js /
 * fetchWithProgress: pin every model to a commit-SHA URL, verify its SHA-256
 * BEFORE it is used, and cache the verified bytes on disk so later sessions
 * start offline-fast. A tampered or truncated download is rejected here rather
 * than failing deep inside the ONNX parser.
 */
object OnnxModels {

    // Models are deliberately downloaded only from immutable HTTPS URLs.  The
    // SHA-256 check below protects integrity, but these limits protect the
    // machine while an untrusted response is still being streamed to disk.
    private const val MAX_REDIRECTS = 5
    private const val ABSOLUTE_MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L
    private const val MAX_UNHINTED_DOWNLOAD_BYTES = 32L * 1024L * 1024L
    private const val SIZE_SLACK_MIN_BYTES = 1L * 1024L * 1024L
    private const val SIZE_SLACK_DIVISOR = 20L // 5%; accommodates CDN metadata drift.

    /** ~/.nyora/models — verified model bytes, keyed by their SHA-256. */
    val cacheDir: File by lazy {
        File(System.getProperty("user.home"), ".nyora/models").apply {
            if (!isDirectory && !mkdirs()) {
                throw IOException("Could not create Nyora model cache")
            }
        }
    }

    fun modelFile(sha256: String): File {
        validateSha256(sha256)
        return File(cacheDir, "$sha256.onnx")
    }

    /** True when the model is present on disk and its bytes still match [sha256]. */
    fun isCached(sha256: String): Boolean {
        val f = modelFile(sha256)
        return f.isFile &&
            f.length() in 1..ABSOLUTE_MAX_DOWNLOAD_BYTES &&
            runCatching { sha256(f).equals(sha256, ignoreCase = true) }.getOrDefault(false)
    }

    /**
     * Ensure the model at [url] is present and verified locally; download it with
     * [onProgress] (0..100) otherwise. Returns the verified local file. Idempotent,
     * and safe to call concurrently for the same model (last writer wins on rename).
     */
    @Synchronized
    fun ensure(url: String, sha256: String, sizeHint: Long = 0L, onProgress: (Int) -> Unit = {}): File {
        validateSha256(sha256)
        val maxBytes = maximumAllowedBytes(sizeHint)
        val f = modelFile(sha256)
        if (isCached(sha256)) { onProgress(100); return f }

        val tmp = File.createTempFile("$sha256-", ".part", cacheDir)
        try {
            val conn = openVerifiedConnection(url)
            try {
                val declaredLength = conn.contentLengthLong
                validateDeclaredLength(declaredLength, sizeHint, maxBytes)
                val total = if (declaredLength > 0) declaredLength else sizeHint
                var received = 0L

                conn.inputStream.buffered().use { input ->
                    tmp.outputStream().buffered().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var n: Int
                        while (input.read(buf).also { n = it } >= 0) {
                            received += n
                            if (received > maxBytes) {
                                throw IOException("Model download exceeded its allowed size")
                            }
                            out.write(buf, 0, n)
                            if (total > 0) onProgress(minOf(100, (received * 100 / total).toInt()))
                        }
                    }
                }

                if (declaredLength >= 0 && received != declaredLength) {
                    throw IOException("Model download was truncated")
                }
            } finally {
                conn.disconnect()
            }

            val actual = sha256(tmp)
            if (!actual.equals(sha256, ignoreCase = true)) {
                throw IOException("Model failed its integrity check — download rejected")
            }
            moveVerifiedFile(tmp, f)
            onProgress(100)
            return f
        } finally {
            // Failed downloads, mismatched hashes and cancelled callers must not
            // leave a partial artefact that can consume storage or be mistaken
            // for a cache hit by a later process.
            if (tmp.exists()) tmp.delete()
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val b = ByteArray(1 shl 16)
            var n: Int
            while (s.read(b).also { n = it } >= 0) md.update(b, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateSha256(value: String) {
        require(value.length == 64 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Expected a SHA-256 hex digest"
        }
    }

    private fun maximumAllowedBytes(sizeHint: Long): Long {
        if (sizeHint < 0) throw IOException("Invalid expected model size")
        if (sizeHint == 0L) return MAX_UNHINTED_DOWNLOAD_BYTES
        if (sizeHint > ABSOLUTE_MAX_DOWNLOAD_BYTES) {
            throw IOException("Expected model exceeds the download safety limit")
        }
        val slack = max(SIZE_SLACK_MIN_BYTES, sizeHint / SIZE_SLACK_DIVISOR)
        return minOf(ABSOLUTE_MAX_DOWNLOAD_BYTES, sizeHint + slack)
    }

    private fun validateDeclaredLength(declared: Long, sizeHint: Long, maxBytes: Long) {
        if (declared < -1L) throw IOException("Invalid model Content-Length")
        if (declared > maxBytes) throw IOException("Model Content-Length exceeds its allowed size")
        if (declared >= 0L && sizeHint > 0L) {
            val slack = max(SIZE_SLACK_MIN_BYTES, sizeHint / SIZE_SLACK_DIVISOR)
            val minExpected = max(0L, sizeHint - slack)
            if (declared < minExpected) {
                throw IOException("Model Content-Length is unexpectedly small")
            }
        }
    }

    private fun openVerifiedConnection(initialUrl: String): HttpURLConnection {
        var current = URL(initialUrl)
        var redirects = 0
        while (true) {
            validateHttpsUrl(current)
            val conn = (current.openConnection() as? HttpURLConnection)
                ?: throw IOException("Model URL is not an HTTP(S) URL")
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept-Encoding", "identity")

            val code = try {
                conn.responseCode
            } catch (error: Exception) {
                conn.disconnect()
                throw error
            }
            if (code !in 300..399) {
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IOException("Model server returned HTTP $code")
                }
                return conn
            }

            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location.isNullOrBlank()) throw IOException("Model redirect has no Location")
            if (++redirects > MAX_REDIRECTS) throw IOException("Model redirect limit exceeded")
            current = URL(current, location)
        }
    }

    private fun validateHttpsUrl(url: URL) {
        if (!url.protocol.equals("https", ignoreCase = true) ||
            url.host.isBlank() ||
            !url.userInfo.isNullOrBlank()
        ) {
            throw IOException("Model downloads require a safe HTTPS URL")
        }
    }

    private fun moveVerifiedFile(tmp: File, destination: File) {
        try {
            Files.move(
                tmp.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
