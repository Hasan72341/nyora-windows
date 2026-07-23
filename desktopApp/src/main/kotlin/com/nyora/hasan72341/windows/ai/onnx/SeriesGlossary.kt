package com.nyora.windows.ai.onnx

import com.nyora.windows.ai.AiResponseReader
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Series/fandom context for accurate character names & terms. Faithful port of
 * nyora-web's core/translate/engine.js `seriesGlossary()` hybrid lookup and its
 * helpers. Pure network + string logic — no ONNX.
 *
 * Hybrid lookup:
 *   1. MangaBaka resolves the (often messy scanlation) title — it aggregates
 *      AniList/MAL/Kitsu/MangaUpdates and matches secondary ja/ko/romanized
 *      titles far better than fuzzy search. It also returns the AniList ID.
 *   2. AniList, queried BY ID, supplies what MangaBaka lacks: the character
 *      roster with native → romanized names (the big accuracy win).
 *   3. FANDOM supplies the canonical English spellings, for a far bigger roster
 *      than AniList's main cast.
 * Either half failing degrades gracefully; one lookup per title, cached.
 */
object SeriesGlossary {

    data class NamePair(val native: String, val romaji: String)

    data class NameHit(val native: String, val romaji: String, val match: String)

    data class Glossary(
        val name: String,
        val genres: List<String>,
        val desc: String,
        val names: List<NamePair>,
        val roster: List<String>,
        val wiki: String,
    )

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // One lookup per title, cached — mirrors the JS `seriesCache` promise map.
    private val cache = ConcurrentHashMap<String, Deferred<Glossary?>>()

    private val JSON_MEDIA = "application/json".toMediaType()

    /** Fail soft for a provider error, but never convert reader cancellation into a cache miss. */
    private fun <T> Result<T>.getOrNullUnlessCancelled(): T? = getOrElse { error ->
        if (error is CancellationException) throw error
        null
    }

    // ---- string helpers (ports of stripHtml / escapeRe / romajiKey) ----------

    /** Strip HTML tags, collapse whitespace, trim. */
    private fun stripHtml(s: String?): String =
        (s ?: "").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    // Names can contain regex metacharacters (e.g. "Aki (Devil)"). Kept for
    // parity; Kotlin's literal String.replace is used for substitution instead.
    private fun escapeRe(s: String): String =
        s.replace(Regex("[.*+?^\${}()|\\[\\]\\\\]"), "\\\\$0")

    // AniList romanises with doubled vowels ("Satoru Gojou", "Yuuji Itadori")
    // while the wiki carries the spelling readers actually know ("Satoru Gojo",
    // "Yuji Itadori"). Fold both to a comparable key so the wiki's spelling wins.
    private fun romajiKey(s: String?): String =
        Normalizer.normalize(s ?: "", Normalizer.Form.NFD)
            .lowercase()
            .replace(Regex("[\\u0300-\\u036f]"), "") // strip macrons (ō → o)
            .replace(Regex("[^a-z]"), "")
            .replace("ou", "o").replace("oo", "o")
            .replace("uu", "u").replace("aa", "a")
            .replace("ee", "e").replace("ii", "i")

    private fun preferRoster(romaji: String, roster: List<String>): String {
        if (roster.isEmpty()) return romaji
        val k = romajiKey(romaji)
        if (k.isEmpty()) return romaji
        return roster.firstOrNull { romajiKey(it) == k } ?: romaji
    }

    // A Japanese name appears as the full name OR just the surname/given part,
    // split by ・/space. Longest variant first so "早川アキ" wins over a bare "アキ".
    private fun nameVariants(native: String?): List<String> {
        val n = (native ?: "").trim()
        if (n.isEmpty()) return emptyList()
        val parts = n.split(Regex("[\\s・･·]+")).filter { it.length >= 2 }
        return (listOf(n) + parts).sortedByDescending { it.length }
    }

    // ---- JSON navigation helpers --------------------------------------------

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.let { if (it.isString) it.content else it.contentOrNull }
    private fun JsonElement?.strList(): List<String> =
        this.arr()?.mapNotNull { it.str() } ?: emptyList()

    // ---- network -------------------------------------------------------------

    private suspend fun getJson(url: String): JsonElement? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            val body = AiResponseReader.readUtf8(res.body) ?: return@withContext null
            runCatching { json.parseToJsonElement(body) }.getOrNullUnlessCancelled()
        }
    }

    private class MangaBaka(
        val title: String,
        val description: String,
        val genres: List<String>,
        val anilistId: Int?,
    )

    private suspend fun mangaBakaResolve(q: String): MangaBaka? {
        val root = getJson(
            "https://api.mangabaka.dev/v1/series/search?q=" +
                java.net.URLEncoder.encode(q, "UTF-8") + "&limit=1",
        ) ?: return null
        val item = root.obj()?.get("data")?.arr()?.firstOrNull()?.obj() ?: return null
        val source = item["source"]?.obj()
        val anilist = source?.get("anilist")
        val idElem = (anilist.obj()?.get("id")) ?: anilist
        val anilistId = (idElem as? JsonPrimitive)
            ?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
            ?.takeIf { it > 0 }
        return MangaBaka(
            title = item["title"].str() ?: q,
            description = stripHtml(item["description"].str()).take(600),
            genres = item["genres"].strList().take(8),
            anilistId = anilistId,
        )
    }

    private suspend fun anilistMedia(query: String, variables: Map<String, Any>): JsonObject? =
        withContext(Dispatchers.IO) {
            val varsObj = buildString {
                append('{')
                variables.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) append(',')
                    append('"').append(k).append("\":")
                    when (v) {
                        is Number -> append(v.toString())
                        else -> append('"').append(v.toString().replace("\"", "\\\"")).append('"')
                    }
                }
                append('}')
            }
            val payload = buildString {
                append('{')
                append("\"query\":").append(JsonPrimitive(query).toString())
                append(",\"variables\":").append(varsObj)
                append('}')
            }
            val req = Request.Builder()
                .url("https://graphql.anilist.co")
                .header("Accept", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = AiResponseReader.readUtf8(res.body) ?: return@withContext null
                val root = runCatching { json.parseToJsonElement(body) }
                    .getOrNullUnlessCancelled() ?: return@withContext null
                root.obj()?.get("data")?.obj()?.get("Media")?.obj()
            }
        }

    // FANDOM wiki-slug candidates built from the series title. Verified against
    // jujutsu-kaisen, spy-x-family, chainsawman, onepiece, demonslayer, etc.
    private fun wikiSlugs(titles: List<String?>): List<String> {
        val out = mutableListOf<String>()
        for (t in titles) {
            val s = (t ?: "").lowercase().trim()
            if (s.isEmpty()) continue
            val flat = s.replace(Regex("[^a-z0-9]"), "")
            val dash = s.replace(Regex("[^a-z0-9]+"), "-").replace(Regex("^-+|-+$"), "")
            for (c in listOf(flat, dash)) if (c.length > 1 && c !in out) out.add(c)
        }
        return out.take(4)
    }

    private class Fandom(val wiki: String, val names: List<String>)

    private suspend fun fandomRoster(titles: List<String?>): Fandom? {
        for (slug in wikiSlugs(titles)) {
            try {
                val url = "https://$slug.fandom.com/api.php?action=query&list=categorymembers" +
                    "&cmtitle=Category:Characters&cmnamespace=0&cmlimit=200&format=json&origin=*"
                val root = getJson(url) ?: continue
                val members = root.obj()?.get("query")?.obj()?.get("categorymembers")?.arr() ?: continue
                // Article pages only; drop disambiguations/subpages and absurdly long titles.
                val names = members.mapNotNull { it.obj()?.get("title").str()?.trim() }
                    .filter { it.isNotEmpty() && it.length <= 40 && !Regex("[:/(]").containsMatchIn(it) }
                if (names.size >= 5) return Fandom("$slug.fandom.com", names)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // try the next slug candidate
            }
        }
        return null
    }

    private const val CHARS = "characters(perPage:25,sort:ROLE){nodes{name{full native}}}"

    // ---- public API ----------------------------------------------------------

    /** Resolve the series glossary for a title, cached per title. */
    suspend fun resolve(title: String): Glossary? {
        val q = title.trim()
        if (q.isEmpty()) return null
        val deferred = cache.computeIfAbsent(q) {
            scope.async {
                try {
                    resolveUncached(q)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }
        return deferred.await()
    }

    private suspend fun resolveUncached(q: String): Glossary? {
        val mb = runCatching { mangaBakaResolve(q) }.getOrNullUnlessCancelled()

        var media: JsonObject? = null
        if (mb?.anilistId != null) {
            media = runCatching {
                anilistMedia(
                    "query(\$id:Int){Media(id:\$id,type:MANGA){title{romaji english} " +
                        "description(asHtml:false) genres $CHARS}}",
                    mapOf("id" to mb.anilistId),
                )
            }.getOrNullUnlessCancelled()
        }
        if (media == null) {
            // No MangaBaka hit / no AniList ID — fall back to fuzzy title search.
            media = runCatching {
                anilistMedia(
                    "query(\$q:String){Media(search:\$q,type:MANGA){title{romaji english} " +
                        "description(asHtml:false) genres $CHARS}}",
                    mapOf("q" to (mb?.title ?: q)),
                )
            }.getOrNullUnlessCancelled()
        }

        val mediaTitle = media?.get("title")?.obj()
        val romajiTitle = mediaTitle?.get("romaji").str()
        val name = mediaTitle?.get("english").str().orEmptyOrNull()
            ?: romajiTitle.orEmptyOrNull()
            ?: mb?.title.orEmptyOrNull()
            ?: return null

        val mediaGenres = media?.get("genres").strList()
        val genres = if (mediaGenres.isNotEmpty()) mediaGenres else (mb?.genres ?: emptyList())

        val desc = if (media != null) stripHtml(media["description"].str()).take(600)
        else (mb?.description ?: "")

        // native → romanized pairs: the ONLY way to spot a name in Japanese OCR.
        val names = (media?.get("characters")?.obj()?.get("nodes")?.arr() ?: JsonArray(emptyList()))
            .mapNotNull { node ->
                val nm = node.obj()?.get("name")?.obj()
                NamePair(
                    native = (nm?.get("native").str() ?: "").trim(),
                    romaji = (nm?.get("full").str() ?: "").trim(),
                )
            }
            .filter { it.romaji.isNotEmpty() }

        val fandom = runCatching {
            fandomRoster(listOf(name, romajiTitle, mb?.title, q))
        }.getOrNullUnlessCancelled()
        val roster = fandom?.names ?: emptyList()

        // Let the wiki's canonical spelling win over AniList's romanisation.
        val merged = names.map { it.copy(romaji = preferRoster(it.romaji, roster)) }
        return Glossary(
            name = name,
            genres = genres,
            desc = desc,
            names = merged,
            roster = roster,
            wiki = fandom?.wiki ?: "",
        )
    }

    private fun String?.orEmptyOrNull(): String? = this?.takeIf { it.isNotEmpty() }

    // Which characters actually appear on THIS page? Longest match first, so
    // substituting the full name never leaves a half-replaced "早川Aki".
    fun detectNames(texts: List<String>, names: List<NamePair>): List<NameHit> {
        val hay = texts.joinToString("\n")
        val hits = mutableListOf<NameHit>()
        for (e in names) {
            if (e.native.isEmpty()) continue
            val match = nameVariants(e.native).firstOrNull { hay.contains(it) }
            if (match != null) hits.add(NameHit(e.native, e.romaji, match))
        }
        return hits.sortedByDescending { it.match.length }
    }

    // Swap native names for their canonical romanization BEFORE machine
    // translation so the translator passes the name straight through instead of
    // inventing a reading. Fixes names even with NO LLM key configured.
    // Uses literal (non-regex) replacement, so a name containing $ or regex
    // metacharacters cannot corrupt the text.
    fun applyNames(texts: List<String>, hits: List<NameHit>): List<String> {
        if (hits.isEmpty()) return texts
        return texts.map { text ->
            hits.fold(text) { s, h -> s.replace(h.match, h.romaji) }
        }
    }

    // Prompt context: synopsis + a FOCUSED glossary (only the characters detected
    // on this page) plus the wiki roster for canonical spellings.
    fun glossaryContext(g: Glossary?, hits: List<NameHit>): String {
        if (g == null) return ""
        var s = "Series: ${g.name}. Genres: ${g.genres.joinToString(", ")}. Synopsis: ${g.desc}"
        if (hits.isNotEmpty()) {
            s += "\nCharacters on this page (native = canonical English, already substituted): " +
                hits.joinToString("; ") { "${it.match} = ${it.romaji}" }
        }
        if (g.roster.isNotEmpty()) {
            s += "\nCanonical name spellings from the ${g.wiki} wiki — use these exact spellings: " +
                g.roster.take(60).joinToString("; ")
        }
        return s
    }
}
