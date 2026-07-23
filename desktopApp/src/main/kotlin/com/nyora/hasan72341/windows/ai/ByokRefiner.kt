package com.nyora.windows.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * "Bring your own key" refiner targeting any OpenAI-compatible Chat Completions
 * endpoint (`POST {baseUrl}/chat/completions`). Works with OpenAI, OpenRouter,
 * Groq, Together, a local LM Studio / Ollama `/v1` server, etc. Anthropic users
 * can point [baseUrl] at an OpenAI-compatible proxy.
 *
 * The user supplies the base URL, API key, and model; nothing is hard-coded to a
 * vendor. Lines are polished concurrently (capped) and every call fails soft.
 */
class ByokRefiner(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : AiRefiner {

    override val label: String = "Custom AI key"

    private val json = Json { ignoreUnknownKeys = true }

    // This legacy per-line refiner is not used by the current reader (which
    // batches through MangaMt), but it remains a callable production path. Keep
    // it subject to the same credential-routing rules rather than leaving a
    // redirect or clear-text escape hatch behind.
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val endpoint: String? by lazy {
        // Allow the user to paste either ".../v1" or the full endpoint, but
        // never construct a request until the base passes endpoint policy.
        AiEndpointPolicy.requestUrl(baseUrl, "/chat/completions")
    }

    override suspend fun isAvailable(): Boolean =
        apiKey.isNotBlank() && endpoint != null && model.isNotBlank()

    override suspend fun polish(texts: List<String>, targetLang: String): List<String> {
        if (texts.isEmpty() || !isAvailable()) return texts
        val system = RefinePrompts.polishInstructions(targetLang)
        val gate = Semaphore(4)
        return coroutineScope {
            texts.map { line ->
                async(Dispatchers.IO) {
                    val trimmed = line.trim()
                    if (trimmed.length < 2) return@async line
                    gate.withPermit { polishOne(trimmed, system) ?: line }
                }
            }.awaitAll()
        }
    }

    /** One chat-completion round trip. Returns null on any error so the caller keeps the draft. */
    private suspend fun polishOne(line: String, system: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                put("model", model)
                put("temperature", 0.3)
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", system) })
                    add(buildJsonObject { put("role", "user"); put("content", line) })
                })
            }
            val body = json.encodeToString(JsonObject.serializer(), payload)
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint ?: return@runCatching null)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = AiResponseReader.readUtf8(response.body) ?: return@use null
                val content = json.parseToJsonElement(text)
                    .jsonObject["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject?.get("content")
                    ?.jsonPrimitive?.content
                    ?: return@use null
                val cleaned = RefinePrompts.clean(content)
                if (cleaned.isEmpty() || RefinePrompts.isRefusal(cleaned)) null else cleaned
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        }
    }
}
