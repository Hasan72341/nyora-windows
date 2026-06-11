package com.nyora.windows.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Unofficial Google Translate client (keyless `translate_a/single` endpoint).
 *
 * Every call fails soft: on any error the original text is returned so that
 * translation can never crash the reader.
 */
object GoogleTranslate {

    private val http = OkHttpClient()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Translate [text] into [target] language (e.g. "en"). [source] defaults to
     * automatic detection. Returns the translated text, or the original text on
     * any failure. Blank input returns "".
     */
    suspend fun translate(text: String, target: String, source: String = "auto"): String {
        if (text.isBlank()) return ""
        return withContext(Dispatchers.IO) {
            runCatching {
                val q = URLEncoder.encode(text, "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/single" +
                    "?client=gtx&sl=$source&tl=$target&dt=t&q=$q"
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
                    )
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use text
                    val body = response.body?.string() ?: return@use text
                    // Response shape: [[["Hello","こんにちは",null,...], ...], null, "ja"]
                    val segments = json.parseToJsonElement(body).jsonArray[0].jsonArray
                    val sb = StringBuilder()
                    for (segment in segments) {
                        val piece = segment.jsonArray[0].jsonPrimitive.content
                        sb.append(piece)
                    }
                    sb.toString()
                }
            }.getOrDefault(text)
        }
    }

    /**
     * Translate every entry of [texts] into [target], concurrently (capped at
     * ~6 in-flight requests) while preserving input order. Failures fall back to
     * the original entry.
     */
    suspend fun translateAll(texts: List<String>, target: String): List<String> {
        if (texts.isEmpty()) return emptyList()
        val gate = Semaphore(6)
        return coroutineScope {
            texts
                .map { text ->
                    async(Dispatchers.IO) {
                        gate.withPermit { translate(text, target) }
                    }
                }
                .awaitAll()
        }
    }
}
