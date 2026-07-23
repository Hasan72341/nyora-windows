package com.nyora.windows.ai.onnx

import com.nyora.windows.ai.AiEndpointPolicy
import com.nyora.windows.ai.AiResponseReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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
import java.net.URLEncoder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Machine translation via the free Google web endpoint (client=gtx), plus the
 * manga-specific repair layer and optional LLM refinement — a direct port of
 * nyora-web's core/translate/mt.js (itself a port of nyora-android's
 * translator/Translator.kt). All bubbles of a page are joined with the same
 * ||| delimiter and translated in ONE request; if the split comes back
 * misaligned, it bisects rather than falling back to one request per block.
 *
 * Pure logic — no ONNX. Networking uses OkHttp; JSON via kotlinx.serialization.
 */
object MangaMt {

    data class RefineCfg(
        val provider: String,
        val endpoint: String?,
        val apiKey: String,
        val model: String?,
        val context: String?,
    )

    private const val DELIM = "\n\n\n|||\n\n\n"

    // Target languages offered in the reader settings (Google translate codes).
    val TL_LANGS: List<Pair<String, String>> = listOf(
        "en" to "English", "es" to "Spanish", "pt" to "Portuguese", "fr" to "French",
        "de" to "German", "it" to "Italian", "ru" to "Russian", "id" to "Indonesian",
        "ar" to "Arabic", "tr" to "Turkish", "pl" to "Polish", "vi" to "Vietnamese",
        "th" to "Thai", "hi" to "Hindi", "ko" to "Korean", "zh-CN" to "Chinese",
    )

    // Source (page) languages the OCR engines support. 'auto' resolves from the
    // manga source's language in the reader.
    val TL_SOURCES: List<Pair<String, String>> = listOf(
        "auto" to "Auto (source language)", "ja" to "Japanese", "zh" to "Chinese",
        "ko" to "Korean", "en" to "English",
    )

    // OCR language → Google translate source code.
    private val GTX_SOURCE = mapOf("ja" to "ja", "zh" to "zh-CN", "ko" to "ko", "en" to "en")

    // LLM refinement defaults (port of Android's translatePageDialoguesAtOnce).
    private data class AiDefault(val endpoint: String, val model: String)

    private val AI_DEFAULTS = mapOf(
        "openai" to AiDefault("https://api.openai.com/v1", "gpt-4o-mini"),
        "anthropic" to AiDefault("https://api.anthropic.com", "claude-haiku-4-5-20251001"),
    )

    private val client = OkHttpClient()
    // BYOK requests carry an authorization secret. Never follow a redirect: a
    // redirect could otherwise change an initially safe endpoint into an HTTP or
    // attacker-controlled destination after the request has been constructed.
    private val refinementClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_MEDIA = "application/json".toMediaType()

    // encodeURIComponent equivalent (Java URLEncoder differs on space & a few marks).
    private fun encodeURIComponent(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")

    private suspend fun gtx(text: String, target: String, source: String = "auto"): String =
        withContext(Dispatchers.IO) {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
                "&sl=${encodeURIComponent(source)}&tl=${encodeURIComponent(target)}" +
                "&q=${encodeURIComponent(text)}"
            val res = client.newCall(Request.Builder().url(url).get().build()).execute()
            res.use {
                if (!it.isSuccessful) throw RuntimeException("translate failed (${it.code})")
                val bodyText = AiResponseReader.readUtf8(it.body)
                    ?: throw RuntimeException("Translation response is too large or empty")
                val data = json.parseToJsonElement(bodyText)
                val first = (data as? JsonArray)?.getOrNull(0) as? JsonArray ?: return@use ""
                first.joinToString("") { seg ->
                    ((seg as? JsonArray)?.getOrNull(0) as? JsonPrimitive)
                        ?.takeUnless { p -> p is JsonNull }?.contentOrEmpty() ?: ""
                }
            }
        }

    private fun JsonPrimitive.contentOrEmpty(): String = if (this is JsonNull) "" else content

    // --- manga-specific repair of the plain-MT output ---------------------

    // Set phrases gtx reliably gets WRONG (reads interjections as literal
    // statements) — answered directly and never sent to Google.
    private val LEXICON: Map<String, String> = mapOf(
        "しまった" to "Damn it", "ヤバい" to "This is bad", "やばい" to "This is bad",
        "まずい" to "This is bad", "くそ" to "Damn", "くそっ" to "Damn it",
        "ちくしょう" to "Dammit", "やめろ" to "Stop it", "まさか" to "No way",
        "さすが" to "As expected", "よし" to "All right", "なるほど" to "I see",
        "うるさい" to "Shut up", "てめえ" to "You bastard", "ざけんな" to "Screw you",
        "どういうことだ" to "What do you mean", "ありえない" to "Impossible",
    )

    private val FULLWIDTH = mapOf(
        '！' to "!", '？' to "?", '。' to ".", '、' to ",", '．' to ".", '，' to ",",
    )
    private val ASCII_PUNCT_RE = Regex("[！？。、．，]")
    private fun asciiPunct(s: String): String =
        ASCII_PUNCT_RE.replace(s) { FULLWIDTH[it.value[0]] ?: it.value }

    private val FIX_SPACED_BANG = Regex("([!?])(\\s+[!?])+")
    private val WS = Regex("\\s+")
    private val ELLIPSIS = Regex("…")
    private val DOTS4 = Regex("\\.{4,}")
    private val WS_BEFORE_PUNCT = Regex("\\s+([,.!?;:])")
    private val WS_2 = Regex("\\s{2,}")

    private fun fixPunct(s: String): String =
        FIX_SPACED_BANG.replace(s) { WS.replace(it.value, "") } // "! ! !" → "!!!"
            .let { ELLIPSIS.replace(it, "...") }
            .let { DOTS4.replace(it, "...") }
            .let { WS_BEFORE_PUNCT.replace(it, "$1") }
            .let { WS_2.replace(it, " ") }
            .trim()

    // Clamp any run in the output to the longest run in the source.
    private val SRC_RUN_RE = Regex("(.)\\1+")
    private val EN_RUN_RE = Regex("(\\p{L})\\1{2,}")
    private fun clampRuns(en: String, src: String): String {
        val matches = SRC_RUN_RE.findAll(src).toList()
        // No run in the source means there is nothing to clamp AGAINST — bail out.
        if (matches.isEmpty()) return en
        var maxLen = 2
        for (r in matches) maxLen = max(maxLen, r.value.length)
        val cap = maxLen
        return EN_RUN_RE.replace(en) { m ->
            val ch = m.groupValues[1]
            ch.repeat(min(m.value.length, cap))
        }
    }

    // A stutter (ま、まさか…) is a first-mora repeat.
    private val STUTTER = Regex("^(.)[、,]\\s*(?=\\1)")
    private data class Stripped(val text: String, val stutter: Boolean)
    private fun stripStutter(t: String): Stripped =
        if (STUTTER.containsMatchIn(t)) Stripped(STUTTER.replaceFirst(t, ""), true)
        else Stripped(t, false)

    private val RESTORE_RE = Regex("^([A-Za-z])(\\w*)")
    private fun restoreStutter(en: String): String {
        val m = RESTORE_RE.find(en) ?: return en
        val first = m.groupValues[1]
        return "$first-${first.lowercase()}${en.substring(1)}"
    }

    private fun capitalize(s: String): String =
        if (s.isNotEmpty()) s.substring(0, 1).uppercase() + s.substring(1) else s

    private fun polish(en: String?, src: String, stutter: Boolean): String {
        var out = fixPunct(en ?: "")
        out = clampRuns(out, src)
        if (stutter) out = restoreStutter(out)
        return capitalize(out)
    }

    // Split a joined reply back into segments; null when it can't align.
    private val SPLIT_RE = Regex("\\s*\\|\\s*\\|\\s*\\|\\s*")
    private fun splitParts(full: String, n: Int): List<String>? {
        val parts = SPLIT_RE.split(full).map { it.trim() }
        return if (parts.size == n) parts else null
    }

    // Translate a run of segments, halving on misalignment (~log2(n) round trips).
    private suspend fun translateRun(texts: List<String>, target: String, source: String): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (texts.size == 1) {
            return listOf(
                try {
                    gtx(texts[0], target, source).trim()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    ""
                },
            )
        }
        try {
            val parts = splitParts(gtx(texts.joinToString(DELIM), target, source), texts.size)
            if (parts != null) return parts
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // A transient provider/misaligned batch falls back to smaller
            // requests; cancellation must instead stop the whole reader job.
        }
        val mid = ceil(texts.size / 2.0).toInt()
        return coroutineScope {
            val a = async { translateRun(texts.subList(0, mid), target, source) }
            val b = async { translateRun(texts.subList(mid, texts.size), target, source) }
            val (ra, rb) = awaitAll(a, b)
            ra + rb
        }
    }

    private class Prep(
        val src: String,
        val direct: String? = null,
        val send: String? = null,
        val stutter: Boolean = false,
        var out: String? = null,
    )

    private val TRAILING_PUNCT = Regex("[！？!?。．.…、,\\s]+$")

    suspend fun translateBatch(texts: List<String>, target: String, source: String = "auto"): List<String> {
        if (texts.isEmpty()) return emptyList()
        val src = GTX_SOURCE[source] ?: source.ifEmpty { "auto" }

        // Answer known interjections locally; the lexicon is English-only.
        val prepared = texts.map { raw ->
            val t = (raw).trim()
            val bare = TRAILING_PUNCT.replace(t, "")
            val hit = if (target == "en") LEXICON[bare] else null
            if (hit != null) {
                Prep(src = t, direct = fixPunct(hit + asciiPunct(t.substring(bare.length))))
            } else {
                val (text, stutter) = stripStutter(t)
                Prep(src = t, send = text, stutter = stutter)
            }
        }

        val pending = prepared.filter { it.send != null }
        val got = translateRun(pending.map { it.send!! }, target, src)
        pending.forEachIndexed { i, p -> p.out = got[i] }

        return prepared.map { p ->
            if (p.direct != null) p.direct else polish(p.out, p.src, p.stutter)
        }
    }

    private val REFINE_SPLIT = Regex("\\s*\\|\\|\\|\\s*")

    suspend fun refineBatch(
        originals: List<String>,
        drafts: List<String>,
        target: String,
        cfg: RefineCfg,
    ): List<String>? {
        val langName = TL_LANGS.firstOrNull { it.first == target }?.second ?: "English"
        val system = "You are an expert manga translator. Translate each dialogue segment into " +
            langName + ", preserving tone and keeping lines short enough for speech bubbles. " +
            "The segments come from ONE manga page in reading order — keep them coherent with each other. " +
            (if (!cfg.context.isNullOrEmpty()) "\nUse this series context for accurate character names and terms:\n" + cfg.context + "\n" else "") +
            "Reply with ONLY the translated segments, in the same order, separated by \" ||| \". " +
            "No numbering, no commentary, and exactly " + originals.size + " segments."
        val user = "Original segments:\n" + originals.joinToString("\n|||\n") +
            (if (drafts.size == originals.size)
                "\n\nDraft machine translations (improve on these):\n" + drafts.joinToString("\n|||\n")
            else "")

        if (cfg.apiKey.isBlank()) return null
        val defaults = AI_DEFAULTS[cfg.provider] ?: AI_DEFAULTS["openai"]!!
        val configuredEndpoint = cfg.endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: defaults.endpoint
        val endpoint = AiEndpointPolicy.normalizeBaseUrl(configuredEndpoint) ?: return null
        val model = cfg.model?.ifEmpty { null } ?: defaults.model

        val out: String = withContext(Dispatchers.IO) {
            if (cfg.provider == "anthropic") {
                val body = buildJsonObject {
                    put("model", model)
                    put("max_tokens", 4096)
                    put("system", system)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", user)
                        })
                    })
                }.toString()
                val requestUrl = AiEndpointPolicy.requestUrl(endpoint, "/v1/messages") ?: return@withContext ""
                val req = Request.Builder()
                    .url(requestUrl)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-key", cfg.apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("anthropic-dangerous-direct-browser-access", "true")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
                refinementClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw RuntimeException("AI refinement failed (${res.code})")
                    val bodyText = AiResponseReader.readUtf8(res.body)
                        ?: throw RuntimeException("AI refinement response is too large or empty")
                    val data = json.parseToJsonElement(bodyText).jsonObject
                    val text = (data["content"] as? JsonArray)?.getOrNull(0)
                        ?.let { (it as? JsonObject)?.get("text") as? JsonPrimitive }
                        ?.contentOrEmpty() ?: ""
                    text.trim()
                }
            } else {
                val body = buildJsonObject {
                    put("model", model)
                    put("temperature", 0.3)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "system")
                            put("content", system)
                        })
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", user)
                        })
                    })
                }.toString()
                val requestUrl = AiEndpointPolicy.requestUrl(endpoint, "/chat/completions") ?: return@withContext ""
                val req = Request.Builder()
                    .url(requestUrl)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
                refinementClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw RuntimeException("AI refinement failed (${res.code})")
                    val bodyText = AiResponseReader.readUtf8(res.body)
                        ?: throw RuntimeException("AI refinement response is too large or empty")
                    val data = json.parseToJsonElement(bodyText).jsonObject
                    val content = (data["choices"] as? JsonArray)?.getOrNull(0)
                        ?.let { (it as? JsonObject)?.get("message") as? JsonObject }
                        ?.let { it["content"] as? JsonPrimitive }
                        ?.contentOrEmpty() ?: ""
                    content.trim()
                }
            }
        }

        val parts = REFINE_SPLIT.split(out).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size == originals.size) parts else null
    }
}
