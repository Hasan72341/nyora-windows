package com.nyora.windows.ai

import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream

/**
 * Bounded UTF-8 response reader for credential-bearing AI requests.
 *
 * OkHttp may transparently decompress a response, so Content-Length alone is
 * insufficient. This caps both declared and streamed bytes before JSON parsing
 * can allocate an unbounded string.
 */
object AiResponseReader {
    const val MAX_RESPONSE_BYTES = 4L * 1024L * 1024L

    fun readUtf8(body: ResponseBody?, maxBytes: Long = MAX_RESPONSE_BYTES): String? {
        body ?: return null
        if (maxBytes <= 0 || body.contentLength() > maxBytes) return null

        body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count.toLong()
                if (total > maxBytes) return null
                out.write(buffer, 0, count)
            }
            return out.toString(Charsets.UTF_8)
        }
    }
}
