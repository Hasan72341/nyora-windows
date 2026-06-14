package com.nyora.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nyora.windows.bridge.CatalogEntry
import com.nyora.windows.bridge.CatalogResponse
import com.nyora.windows.bridge.CategoriesResponse
import com.nyora.windows.bridge.CategoryDto
import com.nyora.windows.bridge.DownloadDto
import com.nyora.windows.bridge.DownloadResponse
import com.nyora.windows.bridge.DownloadsResponse
import com.nyora.windows.bridge.GlobalSearchGroup
import com.nyora.windows.bridge.GlobalSearchResponse
import com.nyora.windows.bridge.LocalCbzEntry
import com.nyora.windows.bridge.LocalChapterResponse
import com.nyora.windows.bridge.LocalScanResponse
import com.nyora.windows.bridge.NyoraHttpClient
import com.nyora.windows.bridge.AlternativesResponse
import com.nyora.windows.bridge.AniListFeedMedia
import com.nyora.windows.bridge.AniListFeedResponse
import com.nyora.windows.bridge.AniListSearchResponse
import com.nyora.windows.bridge.DownloadSettingsDto
import com.nyora.windows.bridge.DownloadSettingsResponse
import com.nyora.windows.bridge.MangaPrefsDto
import com.nyora.windows.bridge.NetworkSettingsDto
import com.nyora.windows.bridge.NetworkSettingsResponse
import com.nyora.windows.bridge.StatsResponse
import com.nyora.windows.bridge.OtaStatusResponse
import com.nyora.windows.bridge.SupabaseLocalDataResponse
import com.nyora.windows.bridge.SupabaseOkResponse
import com.nyora.windows.bridge.SupabaseStatusResponse
import com.nyora.windows.bridge.SuggestionsResponse
import com.nyora.windows.bridge.SyncSignInResponse
import com.nyora.windows.ai.AiMode
import com.nyora.windows.ai.AiRefinement
import com.nyora.windows.ai.WindowsAiRefiner
import com.nyora.windows.translate.MangaTranslator
import com.nyora.windows.translate.PageTranslation
import com.nyora.windows.ui.theme.AppearanceMode
import com.nyora.windows.ui.theme.Accent
import com.nyora.windows.ui.theme.WindowsNative
import com.nyora.hasan72341.shared.NyoraFacade
import com.nyora.hasan72341.shared.extension.MangaDetails
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaPage
import com.nyora.hasan72341.shared.model.MangaSource
import com.nyora.hasan72341.shared.repository.BookmarkRow
import com.nyora.hasan72341.shared.repository.HistoryRow
import com.nyora.hasan72341.shared.repository.UpdateRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.awt.Desktop
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpServer
import com.nyora.hasan72341.shared.sync.SupabaseConfig

enum class NavDest {
    EXPLORE, FAVOURITES, HISTORY, BOOKMARKS, UPDATES, LOCAL_FILES, DOWNLOADS, SETTINGS, STATS, SUGGESTIONS
}

enum class ReaderMode { PAGED, WEBTOON, VERTICAL }

enum class ExploreMode { POPULAR, LATEST, SEARCH }

class AppState(
    val facade: NyoraFacade,
    val imageBaseUrl: String,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val http = NyoraHttpClient(imageBaseUrl)
    private val translator = MangaTranslator()

    // ── Navigation ────────────────────────────────────────────────────────────
    var destination by mutableStateOf(NavDest.EXPLORE)
    var showDetails  by mutableStateOf(false)
    var showReader   by mutableStateOf(false)
    var showCatalog  by mutableStateOf(false)
    var showGlobalSearch by mutableStateOf(false)

    // ── Explore ───────────────────────────────────────────────────────────────
    var sources          by mutableStateOf<List<MangaSource>>(emptyList())
    var activeSource     by mutableStateOf<MangaSource?>(null)
    var exploreManga     by mutableStateOf<List<Manga>>(emptyList())
    var exploreHasNext   by mutableStateOf(false)
    var explorePage      by mutableStateOf(1)
    var exploreLoading   by mutableStateOf(false)
    var exploreError     by mutableStateOf<String?>(null)
    var searchQuery      by mutableStateOf("")
    var exploreMode      by mutableStateOf(ExploreMode.POPULAR)

    // ── Catalog ───────────────────────────────────────────────────────────────
    var catalogEntries   by mutableStateOf<List<CatalogEntry>>(emptyList())
    var catalogLoading   by mutableStateOf(false)
    var catalogSearch    by mutableStateOf("")
    var catalogLang      by mutableStateOf("all")

    // ── Global Search ─────────────────────────────────────────────────────────
    var globalQuery       by mutableStateOf("")
    var globalResults     by mutableStateOf<List<GlobalSearchGroup>>(emptyList())
    var globalSearching   by mutableStateOf(false)
    var globalSearchError by mutableStateOf<String?>(null)

    // ── Details ───────────────────────────────────────────────────────────────
    var selectedManga   by mutableStateOf<Manga?>(null)
    var detailsLoading  by mutableStateOf(false)
    var detailsError    by mutableStateOf<String?>(null)
    var isFavourited    by mutableStateOf(false)
    var mangaCategories by mutableStateOf<List<CategoryDto>>(emptyList())

    // ── Reader ────────────────────────────────────────────────────────────────
    var readerManga       by mutableStateOf<Manga?>(null)
    var readerChapter     by mutableStateOf<MangaChapter?>(null)
    var readerPages       by mutableStateOf<List<MangaPage>>(emptyList())
    var readerCurrentPage by mutableStateOf(0)
    var readerLoading     by mutableStateOf(false)
    var rtlReading        by mutableStateOf(false)
    var readerMode        by mutableStateOf(ReaderMode.PAGED)
    var currentPageBookmarked by mutableStateOf(false)

    // ── Per-manga reader prefs (colour correction + per-manga reader mode) ──────
    // These reflect the prefs of the *currently open* reader manga. They are
    // loaded in openChapter() and saved (debounced) via saveMangaPrefs().
    var readerBrightness  by mutableStateOf(0.0)   // -1.0 .. 1.0 (0 = none)
    var readerContrast    by mutableStateOf(1.0)   // 0 .. 2 (1 = none)
    var readerSaturation  by mutableStateOf(1.0)   // 0 .. 2 (1 = none)
    var readerHue         by mutableStateOf(0.0)   // -180 .. 180 degrees (0 = none)
    var readerPalette     by mutableStateOf("")    // "" = none

    // ── In-image translation (OCR + machine translation, per page index) ────────
    // Translations are keyed by page index within the *current* chapter and are
    // reset whenever the chapter changes. Everything fails soft: when the Windows
    // OCR engine can't be reached it flips [translateUnavailable] and never
    // crashes the reader. [translateLangs] is the OCR *source* language as a
    // BCP-47 tag (e.g. "ja", "zh-Hans", "ko"), matching Windows.Media.Ocr.
    var translateEnabled     by mutableStateOf(false)
    var translateTarget      by mutableStateOf("en")
    var translateLangs       by mutableStateOf("ja")
    var translateBusy        by mutableStateOf(false)
    var translateUnavailable by mutableStateOf(false)
    var pageTranslations     by mutableStateOf<Map<Int, PageTranslation>>(emptyMap())

    // ── AI refinement (polish the machine-translation draft) ────────────────────
    // Prefers on-device Windows AI (Phi Silica) when available, else a
    // bring-your-own-key OpenAI-compatible endpoint. [windowsAiAvailable] is
    // probed lazily (see refreshWindowsAi) so the Settings UI can show real state.
    var aiMode             by mutableStateOf(AiMode.OFF)
    var byokBaseUrl        by mutableStateOf("https://api.openai.com/v1")
    var byokApiKey         by mutableStateOf("")
    var byokModel          by mutableStateOf("gpt-4o-mini")
    var windowsAiAvailable by mutableStateOf<Boolean?>(null) // null = not yet probed

    // ── Library ───────────────────────────────────────────────────────────────
    var favourites  by mutableStateOf<List<Manga>>(emptyList())
    var history     by mutableStateOf<List<HistoryRow>>(emptyList())
    var bookmarks   by mutableStateOf<List<BookmarkRow>>(emptyList())
    var updates     by mutableStateOf<List<UpdateRow>>(emptyList())

    // ── Categories ────────────────────────────────────────────────────────────
    var categories        by mutableStateOf<List<CategoryDto>>(emptyList())
    var activeCategoryId  by mutableStateOf<Long?>(null) // null = All

    // ── Downloads ─────────────────────────────────────────────────────────────
    var downloads       by mutableStateOf<List<DownloadDto>>(emptyList())
    var downloadsPolling by mutableStateOf(false)

    // ── Local Files ───────────────────────────────────────────────────────────
    var localFiles       by mutableStateOf<List<LocalCbzEntry>>(emptyList())
    var localChapter     by mutableStateOf<LocalChapterResponse?>(null)
    var localFolder      by mutableStateOf<String?>(null)

    // ── Settings ──────────────────────────────────────────────────────────────
    var prefetchEnabled by mutableStateOf(true)
    var nsfwFilter      by mutableStateOf(true)
    var showPageNumbers by mutableStateOf(true)

    // ── Settings: Reader ────────────────────────────────────────────────────────
    // Global reader defaults. Persisted via savePrefs(); the per-manga reader
    // prefs (loadMangaPrefs) still override readerMode per title.
    var defaultReaderMode     by mutableStateOf(ReaderMode.PAGED)
    var autoDetectReaderMode  by mutableStateOf(false)
    private var _readerBackground by mutableStateOf("auto") // "auto"|"dark"|"light"
    val readerBackground: String get() = _readerBackground
    var showZoomButtons       by mutableStateOf(true)
    var twoPageLandscape      by mutableStateOf(false)
    var autoHideControls      by mutableStateOf(true)
    var keepScreenOn          by mutableStateOf(false)
    var descriptionCollapse   by mutableStateOf(true)
    var gridSize              by mutableStateOf(160)

    // ── Settings: Library / History ──────────────────────────────────────────────
    var historyRetentionDays  by mutableStateOf(0)   // 0 = keep forever
    var groupHistoryByDate    by mutableStateOf(true)
    var historySortOrder      by mutableStateOf("recent") // "recent"|"alpha"|"added"
    var hideNsfwSources       by mutableStateOf(true)

    // ── Settings: Translation ─────────────────────────────────────────────────────
    var instantTranslate      by mutableStateOf(false)

    // ── Settings: Privacy ──────────────────────────────────────────────────────────
    // incognito has a side-effect on history recording (see openChapter /
    // recordReaderPage) so it is funneled through setIncognito().
    private var _incognito by mutableStateOf(false)
    val incognito: Boolean get() = _incognito
    var confirmBeforeQuit     by mutableStateOf(false)

    // ── Settings: Advanced ──────────────────────────────────────────────────────────
    var appLocale             by mutableStateOf("auto")

    // ── Appearance / accent (persisted to a small JSON file) ────────────────────
    // Backed by private state; mutate via setAppearance()/setAccent() so changes
    // persist. The public getters let composables observe the values.
    private var _appearance by mutableStateOf(AppearanceMode.DARK)
    private var _accent     by mutableStateOf(Accent.SYSTEM)
    val appearance: AppearanceMode get() = _appearance
    val accent: Accent get() = _accent

    // ── Stats / Suggestions / Alternatives ──────────────────────────────────────
    var stats                by mutableStateOf<StatsResponse?>(null)
    var statsLoading         by mutableStateOf(false)
    var suggestions          by mutableStateOf<List<Manga>>(emptyList())
    var suggestionsLoading   by mutableStateOf(false)
    var alternatives         by mutableStateOf<List<Manga>>(emptyList())
    var alternativesLoading  by mutableStateOf(false)

    // ── Network / download settings ─────────────────────────────────────────────
    var networkSettings   by mutableStateOf<NetworkSettingsDto?>(null)
    var downloadSettings  by mutableStateOf<DownloadSettingsDto?>(null)

    // ── Trackers / sync (tokens persisted alongside appearance prefs) ───────────
    // Token backed by private state; mutate via setAnilistToken() to persist.
    private var _anilistToken by mutableStateOf("")
    val anilistToken: String get() = _anilistToken
    var anilistResult   by mutableStateOf<AniListSearchResponse?>(null)
    var anilistLoading  by mutableStateOf(false)
    var syncToken       by mutableStateOf("")
    var syncServerUrl   by mutableStateOf("")
    var cloudSyncStatus by mutableStateOf<SupabaseStatusResponse?>(null)
    var cloudSyncBusy   by mutableStateOf(false)

    /** First-run start page gate — true until the user finishes onboarding (signs in
     *  or taps "Continue as guest"), tracked by a marker file under the config dir. */
    var showWelcome by mutableStateOf(!File(configDir(), ".onboarded").exists())

    // Native window placement — restored on launch, persisted on change so the app
    // remembers its size + maximized/snapped state like a native Windows app.
    private val winProps = readWindowProps()
    var windowWidth     by mutableStateOf(winProps.getProperty("width")?.toIntOrNull() ?: 1280)
    var windowHeight    by mutableStateOf(winProps.getProperty("height")?.toIntOrNull() ?: 800)
    var windowMaximized by mutableStateOf(winProps.getProperty("maximized")?.toBoolean() ?: false)
    var otaStatus       by mutableStateOf<OtaStatusResponse?>(null)
    var otaBusy         by mutableStateOf(false)

    // ── "For You" feed (AniList trending, DIRECT external API) ───────────────────
    var anilistFeed        by mutableStateOf<List<AniListFeedMedia>>(emptyList())
    var anilistFeedLoading by mutableStateOf(false)

    // ── Status banner ─────────────────────────────────────────────────────────
    var statusMessage by mutableStateOf<String?>(null)

    // Debounce job for saveMangaPrefs()
    private var prefsSaveJob: Job? = null

    init {
        loadPrefs()
        loadSources()
        refreshLibrary()
    }

    // ── Source management ─────────────────────────────────────────────────────

    fun loadSources() {
        scope.launch {
            sources = withContext(Dispatchers.IO) { facade.listSources() }
        }
    }

    fun loadCatalog() {
        catalogLoading = true
        scope.launch {
            runCatching {
                val body = http.get("/sources/catalog")
                catalogEntries = http.parse<CatalogResponse>(body).entries
            }.onFailure { showStatus("Catalog load failed: ${it.message}") }
            catalogLoading = false
        }
    }

    fun installSource(id: String) {
        scope.launch {
            runCatching {
                http.post("/sources/install?id=${URLEncoder.encode(id, "UTF-8")}")
                loadSources()
                catalogEntries = catalogEntries.map {
                    if (it.id == id) it.copy(isInstalled = true) else it
                }
                showStatus("Installed.")
            }.onFailure { showStatus("Install failed: ${it.message}") }
        }
    }

    fun uninstallSource(id: String) {
        scope.launch {
            runCatching {
                http.delete("/sources/uninstall?id=${URLEncoder.encode(id, "UTF-8")}")
                loadSources()
                if (activeSource?.id == id) { activeSource = null; exploreManga = emptyList() }
                catalogEntries = catalogEntries.map {
                    if (it.id == id) it.copy(isInstalled = false) else it
                }
                showStatus("Uninstalled.")
            }.onFailure { showStatus("Uninstall failed: ${it.message}") }
        }
    }

    fun togglePin(id: String) {
        scope.launch {
            runCatching {
                http.post("/sources/pin?id=${URLEncoder.encode(id, "UTF-8")}")
                loadSources()
            }
        }
    }

    fun refreshSources() {
        scope.launch {
            runCatching {
                http.post("/sources/refresh")
                loadSources()
                showStatus("Sources refreshed.")
            }.onFailure { showStatus("Refresh failed: ${it.message}") }
        }
    }

    // ── Browse ────────────────────────────────────────────────────────────────

    // ── Cloudflare (manual clearance) ────────────────────────────────────────────
    // The in-process parser engine throws "Cloudflare challenge: <host>" when a site
    // returns a JS challenge we can't run. We ask the user to paste a cf_clearance
    // cookie from their browser, inject it into the shared OkHttp jar, and re-run the
    // failed operation.

    /** Host of a pending Cloudflare challenge; non-null shows the paste dialog. */
    var cloudflareHost by mutableStateOf<String?>(null)
        private set
    private var cloudflareRetry: (() -> Unit)? = null

    private fun cloudflareHostFromError(t: Throwable): String? =
        generateSequence(t as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.startsWith("Cloudflare challenge: ") }
            ?.removePrefix("Cloudflare challenge: ")
            ?.trim()
            ?.ifBlank { null }

    /** If [t] is a Cloudflare challenge, open the paste dialog (with a [retry]) and return true. */
    private fun handleCloudflare(t: Throwable, retry: () -> Unit): Boolean {
        val host = cloudflareHostFromError(t) ?: return false
        cloudflareHost = host
        cloudflareRetry = retry
        showStatus("Cloudflare challenge on $host — paste cf_clearance to continue")
        return true
    }

    /** Inject the pasted cookie (`cf_clearance=…` or a full Cookie header) and re-run the op. */
    fun applyCloudflareCookie(cookieHeader: String) {
        val host = cloudflareHost ?: return
        val retry = cloudflareRetry
        val cookie = cookieHeader.trim()
        if (cookie.isNotEmpty()) {
            // Allow pasting just the bare value — prepend the cookie name if missing.
            val normalized = if (cookie.contains("=")) cookie else "cf_clearance=$cookie"
            com.nyora.hasan72341.shared.net.injectClearanceCookies(host, normalized)
            showStatus("Applied clearance for $host — retrying")
        }
        dismissCloudflare()
        retry?.invoke()
    }

    fun dismissCloudflare() {
        cloudflareHost = null
        cloudflareRetry = null
    }

    fun browseManga(source: MangaSource, page: Int = 1) {
        activeSource = source
        explorePage = page
        exploreError = null
        exploreLoading = true
        scope.launch {
            runCatching {
                val svc = withContext(Dispatchers.IO) { facade.openExtension(source) }
                val result = withContext(Dispatchers.IO) {
                    when (exploreMode) {
                        ExploreMode.LATEST -> svc.getLatest(page)
                        ExploreMode.SEARCH ->
                            if (searchQuery.isBlank()) svc.getPopular(page) else svc.search(searchQuery, page)
                        ExploreMode.POPULAR -> svc.getPopular(page)
                    }
                }
                exploreManga = if (page == 1) result.entries else exploreManga + result.entries
                exploreHasNext = result.hasNextPage
            }.onFailure { t ->
                if (!handleCloudflare(t) { browseManga(source, page) }) exploreError = t.message
            }
            exploreLoading = false
        }
    }

    // ── Global search ─────────────────────────────────────────────────────────

    // In-flight global-search coroutine; cancelled when a new query starts so a
    // slow source from a previous query cannot append stale groups.
    private var globalSearchJob: Job? = null

    fun globalSearch(query: String) {
        if (query.isBlank()) return
        // Cancel any previous in-flight search so its slow sources cannot append
        // stale groups after this query has reset the list.
        globalSearchJob?.cancel()
        globalQuery = query
        globalResults = emptyList()
        globalSearching = true
        globalSearchError = null

        // STREAMING global search: instead of one /search/global REST call that
        // does awaitAll() server-side (all-or-nothing — the UI showed a single
        // spinner until EVERY source finished), fan out across installed sources
        // here and append each source's GlobalSearchGroup the moment it returns.
        // The GlobalSearchScreen list is keyed by sourceId, so each group renders
        // as it arrives; per-source covers then load incrementally via the /image
        // proxy + Coil (already concurrent, not gated behind the JS lock).
        //
        // Prerequisite: the GraalVM single-thread-confinement fix in
        // JavaScriptExtensionService — different sources run their JS in parallel
        // on their own owning threads, so this concurrent fan-out is now safe.
        val targets = sources.filter { it.isInstalled }
        if (targets.isEmpty()) {
            globalSearching = false
            return
        }
        // Bound parallelism (mirrors the proxy's gate pattern at a desktop-suited
        // bound) so we don't open a connection per source all at once.
        val gate = Semaphore(8)
        val perSourceLimit = 5

        globalSearchJob = scope.launch {
            coroutineScope {
                targets.forEach { src ->
                    launch {
                        val group = gate.withPermit {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    // Per-source timeout (mirrors the server's 15s cap)
                                    // so one hung source can't stall the rest.
                                    withTimeoutOrNull(15_000L) {
                                        val svc = facade.openExtension(src)
                                        val page = svc.search(query, 1)
                                        GlobalSearchGroup(
                                            sourceId = src.id,
                                            sourceName = src.name,
                                            entries = page.entries.take(perSourceLimit),
                                            error = null,
                                        )
                                    }
                                }.getOrNull()
                            }
                        }
                        // Append on the Main dispatcher (Compose snapshot state).
                        // Only surface sources that returned hits, matching the old
                        // server behaviour. Reassign the list (not in-place mutate)
                        // so Compose observes the change.
                        if (group != null && group.entries.isNotEmpty()) {
                            globalResults = globalResults + group
                        }
                        // Drop the all-sources spinner as soon as the first source
                        // settles, so results stream in rather than blocking on all.
                        globalSearching = false
                    }
                }
            }
            // All sources settled. Ensure the spinner is cleared even if every
            // source returned empty/failed; GlobalSearchScreen shows its "no
            // results" empty state when the list is empty and the query non-blank.
            globalSearching = false
        }
    }

    // ── Details ───────────────────────────────────────────────────────────────

    fun resumeManga(manga: Manga, source: MangaSource? = null, chapterId: String? = null) {
        // BUGFIX: opening from History/Updates/Bookmarks may pass a null/stale
        // source; resolve from the installed sources by the manga's source ref
        // so resume never silently no-ops.
        val src = source ?: sourceFor(manga) ?: run {
            showStatus("Source not installed"); return
        }
        detailsLoading = true
        scope.launch {
            runCatching {
                val svc = withContext(Dispatchers.IO) { facade.openExtension(src) }
                val details = withContext(Dispatchers.IO) { svc.getDetails(manga.url) }
                val full = details.manga.copy(chapters = details.chapters)
                withContext(Dispatchers.IO) { facade.upsertManga(full) }

                val chapter = if (chapterId != null) {
                    full.chapters.firstOrNull { it.id == chapterId }
                } else {
                    full.chapters.firstOrNull() // Latest
                }

                if (chapter != null) {
                    openChapter(full, chapter, src)
                } else {
                    // Fallback to details if chapter not found
                    selectedManga = full
                    showDetails = true
                }
            }.onFailure { t ->
                if (!handleCloudflare(t) { resumeManga(manga, src, chapterId) }) {
                    showStatus("Failed to resume: ${t.message}")
                    openDetails(manga, src)
                }
            }
            detailsLoading = false
        }
    }

    fun openDetails(manga: Manga, source: MangaSource? = null) {
        // BUGFIX: resolve a fallback source so opening details from
        // History/Updates/Bookmarks (stale/null activeSource) still works.
        val src = source ?: sourceFor(manga) ?: run {
            showStatus("Source not installed"); return
        }
        activeSource = src
        selectedManga = manga
        showDetails = true
        detailsLoading = true
        detailsError = null
        scope.launch {
            isFavourited = withContext(Dispatchers.IO) { facade.isFavourited(manga.id) }
            mangaCategories = loadCategoriesForManga(manga.id)
            runCatching {
                val svc = withContext(Dispatchers.IO) { facade.openExtension(src) }
                val details: MangaDetails = withContext(Dispatchers.IO) { svc.getDetails(manga.url) }
                // Asura (and some parsers) return no cover from getDetails — keep the
                // list/grid cover the user clicked so the details hero doesn't go blank.
                val full = details.manga.copy(
                    chapters = details.chapters,
                    coverUrl = details.manga.coverUrl.ifBlank { manga.coverUrl },
                )
                withContext(Dispatchers.IO) { facade.upsertManga(full) }
                selectedManga = full
            }.onFailure { t ->
                if (!handleCloudflare(t) { openDetails(manga, src) }) detailsError = t.message
            }
            detailsLoading = false
        }
    }

    // ── Reader ────────────────────────────────────────────────────────────────

    fun openChapter(manga: Manga, chapter: MangaChapter, source: MangaSource? = null) {
        val src = source ?: sourceFor(manga) ?: run {
            showStatus("Source not installed"); return
        }
        activeSource = src
        readerManga = manga
        readerChapter = chapter
        readerPages = emptyList()
        readerCurrentPage = 0
        readerLoading = true
        showReader = true
        // Translations are per-chapter/page-index — drop the old ones, but keep
        // [translateEnabled] so the user's toggle persists across chapters.
        pageTranslations = emptyMap()
        // Apply any persisted per-manga reader prefs (mode + colour correction)
        // before the pages load so the reader paints with the right look.
        loadMangaPrefs(manga.id)
        scope.launch {
            // Restore last position
            val saved = withContext(Dispatchers.IO) {
                facade.history(200).firstOrNull { it.manga.id == manga.id && it.chapterId == chapter.id }
            }
            readerCurrentPage = saved?.page ?: 0
            runCatching {
                val cached = withContext(Dispatchers.IO) { facade.cachedPages(chapter.url) }
                val pages = if (cached != null) cached else {
                    val svc = withContext(Dispatchers.IO) { facade.openExtension(src) }
                    val loaded = withContext(Dispatchers.IO) { svc.getPageList(chapter) }
                    facade.cachePages(chapter.url, manga.id, loaded)
                    loaded
                }
                readerPages = pages
                readerLoading = false
                checkPageBookmark()
                // Best-effort prefetch of the following chapter.
                prefetchNextChapter()
            }.onFailure { t ->
                readerLoading = false; checkPageBookmark()
                handleCloudflare(t) { openChapter(manga, chapter, src) }
            }
        }
        if (!incognito) {
            scope.launch(Dispatchers.IO) {
                facade.recordHistory(manga.id, src.id, chapter.id, chapter.title, 0, 0f)
            }
        }
    }

    /**
     * Loads the per-manga reader prefs from the backend and applies [readerMode]
     * plus the colour-correction filter values. Falls back to global defaults
     * when no prefs row exists (present = false) or on any failure.
     */
    private fun loadMangaPrefs(mangaId: String) {
        scope.launch {
            runCatching {
                val body = http.get("/manga/prefs?mangaId=${URLEncoder.encode(mangaId, "UTF-8")}")
                val dto = http.parse<MangaPrefsDto>(body)
                if (dto.present) {
                    readerBrightness = dto.brightness
                    readerContrast   = dto.contrast
                    readerSaturation = dto.saturation
                    readerHue        = dto.hue
                    readerPalette    = dto.palette
                    // Apply per-manga reader mode if it was set; otherwise keep
                    // whatever the global default already is.
                    parseReaderMode(dto.readerMode)?.let { readerMode = it }
                } else {
                    // No saved prefs: reset colour correction to neutral. Leave
                    // readerMode at the current global default.
                    readerBrightness = 0.0
                    readerContrast = 1.0
                    readerSaturation = 1.0
                    readerHue = 0.0
                    readerPalette = ""
                }
            }
        }
    }

    private fun parseReaderMode(raw: String): ReaderMode? = when (raw.uppercase()) {
        "PAGED" -> ReaderMode.PAGED
        "WEBTOON" -> ReaderMode.WEBTOON
        "VERTICAL" -> ReaderMode.VERTICAL
        else -> null
    }

    /**
     * Persists the current reader manga's prefs (reader mode + colour correction).
     * Debounced ~600ms so dragging sliders does not spam the backend.
     */
    fun saveMangaPrefs() {
        val mangaId = readerManga?.id ?: return
        prefsSaveJob?.cancel()
        prefsSaveJob = scope.launch {
            delay(600)
            runCatching {
                val params = buildString {
                    append("mangaId=${URLEncoder.encode(mangaId, "UTF-8")}")
                    append("&readerMode=${URLEncoder.encode(readerMode.name, "UTF-8")}")
                    append("&brightness=$readerBrightness")
                    append("&contrast=$readerContrast")
                    append("&saturation=$readerSaturation")
                    append("&hue=$readerHue")
                    append("&palette=${URLEncoder.encode(readerPalette, "UTF-8")}")
                }
                http.post("/manga/prefs/save?$params")
            }.onFailure { showStatus("Failed to save reader prefs: ${it.message}") }
        }
    }

    /**
     * Best-effort warm of the chapter that follows [readerChapter] in
     * [readerManga].chapters. Runs on IO and swallows all errors. No-op unless
     * [prefetchEnabled].
     */
    fun prefetchNextChapter() {
        if (!prefetchEnabled) return
        val manga = readerManga ?: return
        val current = readerChapter ?: return
        val src = activeSource ?: sourceFor(manga) ?: return
        val chapters = manga.chapters
        val idx = chapters.indexOfFirst { it.id == current.id }
        if (idx < 0 || idx + 1 >= chapters.size) return
        val next = chapters[idx + 1]
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (facade.cachedPages(next.url) == null) {
                    val svc = facade.openExtension(src)
                    val loaded = svc.getPageList(next)
                    facade.cachePages(next.url, manga.id, loaded)
                }
            }
        }
    }

    fun recordReaderPage(page: Int) {
        readerCurrentPage = page
        checkPageBookmark()
        // Kick off translation of the new page on demand when enabled.
        if (translateEnabled) translatePage(page)
        if (incognito) return
        val m = readerManga ?: return
        val c = readerChapter ?: return
        val percent = if (readerPages.isEmpty()) 0f else page.toFloat() / readerPages.size
        scope.launch(Dispatchers.IO) {
            facade.recordHistory(m.id, activeSource?.id ?: "", c.id, c.title, page, percent)
        }
    }

    fun togglePageBookmark() {
        val m = readerManga ?: return
        val c = readerChapter ?: return
        scope.launch(Dispatchers.IO) {
            if (facade.isPageBookmarked(m.id, c.id, readerCurrentPage)) {
                facade.removeBookmarkForPage(m.id, c.id, readerCurrentPage)
            } else {
                facade.addBookmark(m.id, c.id, c.title, readerCurrentPage, "")
            }
            withContext(Dispatchers.Main) {
                checkPageBookmark()
                bookmarks = facade.bookmarks()
            }
        }
    }

    private fun checkPageBookmark() {
        val m = readerManga ?: return
        val c = readerChapter ?: return
        scope.launch(Dispatchers.IO) {
            val isMarked = facade.isPageBookmarked(m.id, c.id, readerCurrentPage)
            withContext(Dispatchers.Main) { currentPageBookmarked = isMarked }
        }
    }

    // ── In-image translation ────────────────────────────────────────────────────
    //
    // OCR + machine translation of a single reader page. Results are cached by
    // page index for the current chapter (see [pageTranslations]) so re-visiting
    // a page is instant. Everything fails soft: any error is swallowed and the
    // reader keeps working untranslated.

    /**
     * OCR-translate the page at [index] (a position within [readerPages]) and
     * store the result in [pageTranslations]. No-op when the index is out of
     * range or already translated. If the Windows OCR engine can't be reached it
     * flips [translateUnavailable] and surfaces a hint instead of crashing.
     */
    fun translatePage(index: Int) {
        if (index !in readerPages.indices) return
        if (pageTranslations.containsKey(index)) return
        translateBusy = true
        scope.launch {
            runCatching {
                val res = withContext(Dispatchers.IO) {
                    val refiner = AiRefinement.resolve(aiMode, byokBaseUrl, byokApiKey, byokModel)
                    translator.translatePageImage(
                        proxyUrl(readerPages[index]), translateLangs, translateTarget, refiner,
                    )
                }
                if (!res.ocrAvailable) {
                    translateUnavailable = true
                    showStatus("Windows OCR unavailable — add a Windows OCR language pack (Settings ▸ Time & language)")
                } else {
                    pageTranslations = pageTranslations + (index to res)
                }
            }
            translateBusy = false
        }
    }

    /** Flips translation on/off; translates the current page when turning on. */
    fun toggleTranslate() {
        translateEnabled = !translateEnabled
        if (translateEnabled) translatePage(readerCurrentPage)
    }

    /**
     * Switches the target language. Clears cached translations (they were in the
     * old language) and, when translation is active, re-translates the current
     * page in the new language.
     */
    fun changeTranslateTarget(t: String) {
        if (t == translateTarget) return
        translateTarget = t
        pageTranslations = emptyMap()
        if (translateEnabled) translatePage(readerCurrentPage)
    }

    /**
     * Switches the OCR *source* language (BCP-47 tag for Windows.Media.Ocr, e.g.
     * "ja", "zh-Hans", "ko", "ar"). The page's text language must match (and the
     * matching Windows OCR language pack must be installed) or OCR produces
     * garbage. Clears cached translations and re-OCRs the current page.
     */
    fun changeTranslateLangs(langs: String) {
        if (langs == translateLangs) return
        translateLangs = langs
        pageTranslations = emptyMap()
        if (translateEnabled) translatePage(readerCurrentPage)
    }

    // ── AI refinement settings ──────────────────────────────────────────────────

    /** Probe whether on-device Windows AI (Phi Silica) is usable; updates
     *  [windowsAiAvailable] for the Settings UI. Cheap after the first call. */
    fun refreshWindowsAi() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { WindowsAiRefiner.isAvailable() }.getOrDefault(false) }
            windowsAiAvailable = ok
        }
    }

    fun changeAiMode(mode: AiMode) {
        aiMode = mode
        if (mode == AiMode.WINDOWS && windowsAiAvailable == null) refreshWindowsAi()
        savePrefs()
    }

    fun setByok(baseUrl: String, apiKey: String, model: String) {
        byokBaseUrl = baseUrl
        byokApiKey = apiKey
        byokModel = model
        savePrefs()
    }

    fun downloadChapter(manga: Manga, chapter: MangaChapter, source: MangaSource) {
        scope.launch {
            runCatching {
                val params = buildString {
                    append("sourceId=${URLEncoder.encode(source.id, "UTF-8")}")
                    append("&mangaUrl=${URLEncoder.encode(manga.url, "UTF-8")}")
                    append("&chapterUrl=${URLEncoder.encode(chapter.url, "UTF-8")}")
                    append("&mangaTitle=${URLEncoder.encode(manga.title, "UTF-8")}")
                    append("&chapterTitle=${URLEncoder.encode(chapter.title, "UTF-8")}")
                }
                val body = http.post("/downloads/start?$params")
                val resp = http.parse<DownloadResponse>(body)
                downloads = listOf(resp.entry) + downloads
                destination = NavDest.DOWNLOADS
                startPollingDownloads()
                showStatus("Download started.")
            }.onFailure { showStatus("Download failed: ${it.message}") }
        }
    }

    fun downloadChapters(source: MangaSource, manga: Manga, chapters: List<MangaChapter>) {
        if (chapters.isEmpty()) return
        scope.launch {
            runCatching {
                val params = buildString {
                    append("sourceId=${URLEncoder.encode(source.id, "UTF-8")}")
                    append("&mangaUrl=${URLEncoder.encode(manga.url, "UTF-8")}")
                    append("&mangaTitle=${URLEncoder.encode(manga.title, "UTF-8")}")
                }
                val chapterData = chapters.map { mapOf("url" to it.url, "title" to it.title) }
                val jsonBody = Json.encodeToString(chapterData)
                http.post("/downloads/enqueue?$params", body = jsonBody)
                startPollingDownloads()
                destination = NavDest.DOWNLOADS
                showStatus("Enqueued ${chapters.size} chapters.")
            }.onFailure { showStatus("Enqueue failed: ${it.message}") }
        }
    }

    // ── Favourites & categories ───────────────────────────────────────────────

    fun toggleFavourite() {
        val manga = selectedManga ?: return
        scope.launch(Dispatchers.IO) {
            isFavourited = facade.toggleFavourite(manga.id)
            favourites = facade.favourites()
        }
    }

    fun loadCategories() {
        scope.launch(Dispatchers.IO) {
            categories = loadCategoryList()
        }
    }

    private suspend fun loadCategoryList(): List<CategoryDto> {
        return runCatching {
            val body = http.get("/library/categories")
            http.parse<CategoriesResponse>(body).categories
        }.getOrDefault(emptyList())
    }

    private suspend fun loadCategoriesForManga(mangaId: String): List<CategoryDto> {
        return runCatching {
            val body = http.get("/library/categories/manga?mangaId=${URLEncoder.encode(mangaId, "UTF-8")}")
            http.parse<CategoriesResponse>(body).categories
        }.getOrDefault(emptyList())
    }

    fun createCategory(title: String) {
        scope.launch {
            runCatching {
                http.post("/library/categories/create?title=${URLEncoder.encode(title, "UTF-8")}")
                categories = loadCategoryList()
            }.onFailure { showStatus("Failed: ${it.message}") }
        }
    }

    fun renameCategory(id: Long, title: String) {
        scope.launch {
            runCatching {
                http.post("/library/categories/rename?id=$id&title=${URLEncoder.encode(title, "UTF-8")}")
                categories = loadCategoryList()
            }.onFailure { showStatus("Rename failed: ${it.message}") }
        }
    }

    fun deleteCategory(id: Long) {
        scope.launch {
            runCatching {
                http.post("/library/categories/delete?id=$id")
                if (activeCategoryId == id) activeCategoryId = null
                categories = loadCategoryList()
            }
        }
    }

    fun addMangaToCategory(mangaId: String, categoryId: Long) {
        scope.launch {
            runCatching {
                http.post("/library/categories/add?mangaId=${URLEncoder.encode(mangaId, "UTF-8")}&categoryId=$categoryId")
                mangaCategories = loadCategoriesForManga(mangaId)
            }
        }
    }

    fun removeMangaFromCategory(mangaId: String, categoryId: Long) {
        scope.launch {
            runCatching {
                http.post("/library/categories/remove?mangaId=${URLEncoder.encode(mangaId, "UTF-8")}&categoryId=$categoryId")
                mangaCategories = loadCategoriesForManga(mangaId)
                if (activeCategoryId == categoryId) favourites = facade.favourites()
            }
        }
    }

    // ── Library refresh ───────────────────────────────────────────────────────

    fun refreshLibrary() {
        scope.launch(Dispatchers.IO) {
            favourites = facade.favourites()
            history    = facade.history(100)
            bookmarks  = facade.bookmarks()
            updates    = facade.updates()
            withContext(Dispatchers.Main) { loadCategories() }
        }
    }

    fun refreshUpdates() {
        scope.launch {
            // Check both favourites AND recently-read history (deduped) so updates
            // surface for titles the user reads without explicitly favouriting.
            // Each title is wrapped in runCatching so one dead source/extension
            // can't abort the whole sweep.
            val candidates = withContext(Dispatchers.IO) {
                (facade.favourites() + facade.history(200).map { it.manga })
                    .distinctBy { it.id }
                    .take(300)
            }
            candidates.forEach { manga ->
                runCatching {
                    val src = sourceFor(manga) ?: return@forEach
                    val svc = withContext(Dispatchers.IO) { facade.openExtension(src) }
                    val details = withContext(Dispatchers.IO) { svc.getDetails(manga.url) }
                    facade.recordUpdateSync(manga.id, src.id, details.chapters.size, details.chapters.firstOrNull()?.title ?: "")
                }
            }
            updates = withContext(Dispatchers.IO) { facade.updates() }
            showStatus("Updates checked.")
        }
    }

    /** Resolves a Manga by id from the in-memory library (favourites first,
     *  then history). Used by screens that only hold an id (e.g. updates). */
    fun mangaById(id: String): Manga? =
        favourites.firstOrNull { it.id == id } ?: history.firstOrNull { it.manga.id == id }?.manga

    fun markUpdateSeen(mangaId: String) {
        scope.launch(Dispatchers.IO) {
            facade.markUpdatesSeen(mangaId)
            updates = facade.updates()
        }
    }

    fun markAllUpdatesSeen() {
        scope.launch(Dispatchers.IO) {
            facade.markAllUpdatesSeen()
            updates = facade.updates()
        }
    }

    fun removeBookmark(id: Long) {
        scope.launch(Dispatchers.IO) {
            facade.removeBookmark(id)
            bookmarks = facade.bookmarks()
        }
    }

    // ── Downloads ─────────────────────────────────────────────────────────────

    fun loadDownloads() {
        scope.launch {
            runCatching {
                val body = http.get("/downloads")
                downloads = http.parse<DownloadsResponse>(body).entries
            }
        }
    }

    fun cancelDownload(id: String) {
        scope.launch {
            runCatching {
                http.post("/downloads/cancel?id=${URLEncoder.encode(id, "UTF-8")}")
                loadDownloads()
            }
        }
    }

    private fun startPollingDownloads() {
        if (downloadsPolling) return
        downloadsPolling = true
        scope.launch {
            while (downloadsPolling) {
                loadDownloads()
                val active = downloads.any { it.status == "QUEUED" || it.status == "RUNNING" }
                if (!active) { downloadsPolling = false; break }
                delay(1500)
            }
        }
    }

    // ── Local files ───────────────────────────────────────────────────────────

    fun scanLocalFolder(folder: String) {
        localFolder = folder
        scope.launch {
            runCatching {
                val encoded = URLEncoder.encode(folder, "UTF-8")
                val body = http.get("/local/scan?folder=$encoded")
                localFiles = http.parse<LocalScanResponse>(body).entries
            }.onFailure { showStatus("Scan failed: ${it.message}") }
        }
    }

    fun openLocalCbz(path: String) {
        scope.launch {
            runCatching {
                val encoded = URLEncoder.encode(path, "UTF-8")
                val body = http.get("/local/chapter?cbz=$encoded")
                val info = http.parse<LocalChapterResponse>(body)
                localChapter = info
                // Open as reader with synthetic pages
                val pages = info.pageUrls.map { url ->
                    com.nyora.hasan72341.shared.model.MangaPage(url = url)
                }
                readerManga = com.nyora.hasan72341.shared.model.Manga(
                    id = "local:${path.hashCode()}",
                    title = info.name,
                    url = path,
                    source = com.nyora.hasan72341.shared.model.MangaSourceRef.Local,
                )
                readerChapter = com.nyora.hasan72341.shared.model.MangaChapter(
                    id = path,
                    title = info.name,
                    url = path,
                )
                readerPages = pages
                readerCurrentPage = 0
                readerLoading = false
                showReader = true
            }.onFailure { showStatus("Open failed: ${it.message}") }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun sourceFor(manga: Manga): MangaSource? {
        val refName = manga.source.name
        // Browsed/synced manga carry a canonical "JS_<ID>" ref while installed sources
        // use "parser:<ID>" — so match on the BARE id too (strip JS_/parser:). Without
        // this, opening any chapter fails to resolve its source ("Source not installed").
        val bare = refName.removePrefix("JS_").substringAfterLast(':')
        if (bare.isBlank() || bare == "Unknown" || bare == "Local") {
            return sources.firstOrNull { it.isInstalled && (it.id == refName || it.name == refName) }
        }
        return sources.firstOrNull { src ->
            src.isInstalled && (
                src.id == refName || src.name == refName ||
                src.id == "parser:$bare" || src.id.endsWith(":$bare") ||
                src.id.removePrefix("parser:") == bare
            )
        }
    }

    fun proxyUrl(page: MangaPage): String {
        val encoded = URLEncoder.encode(page.url, "UTF-8")
        if (page.headers.isEmpty()) return "$imageBaseUrl/image?u=$encoded"
        val headers = page.headers.entries.joinToString("&") { (k, v) ->
            "h=${URLEncoder.encode("$k:$v", "UTF-8")}"
        }
        return "$imageBaseUrl/image?u=$encoded&$headers"
    }

    fun coverProxyUrl(coverUrl: String, source: MangaSource? = null): String {
        if (coverUrl.isBlank()) return ""
        if (coverUrl.startsWith(imageBaseUrl)) return coverUrl  // already proxied (e.g. browse results)
        val encoded = URLEncoder.encode(coverUrl, "UTF-8")
        val refParam = source?.baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?.let { "&h=" + URLEncoder.encode("Referer:$it/", "UTF-8") } ?: ""
        return "$imageBaseUrl/image?u=$encoded$refParam"
    }

    // ── Appearance / accent persistence ─────────────────────────────────────────

    fun setAppearance(mode: AppearanceMode) {
        _appearance = mode
        savePrefs()
    }

    fun setAccent(value: Accent) {
        _accent = value
        savePrefs()
    }

    // ── Global settings setters ──────────────────────────────────────────────────
    //
    // Plain `var ... by mutableStateOf` fields above are persisted by calling
    // [persistSettings] from the UI after a change (or via the dedicated setters
    // below where a side-effect/value-guard is required). [persistSettings] is
    // just a public alias for the private [savePrefs] so screens have one call.

    /** Persists all global settings to disk. Call after mutating any plain
     *  settings var (defaultReaderMode, gridSize, historySortOrder, …). */
    fun persistSettings() = savePrefs()

    /** Reader background: "auto" | "dark" | "light". Persisted. */
    fun setReaderBackground(value: String) {
        _readerBackground = value
        savePrefs()
    }

    /** Incognito: when on, reading is NOT recorded to history. Persisted. */
    fun setIncognito(value: Boolean) {
        _incognito = value
        savePrefs()
    }

    /**
     * The Nyora config directory, mirroring the app convention:
     *   - Windows: %APPDATA%\Nyora  (fallback ~\AppData\Roaming\Nyora)
     *   - macOS:   ~/Library/Application Support/Nyora
     *   - Linux:   $XDG_CONFIG_HOME/nyora  (fallback ~/.config/nyora)
     *
     * The Windows location matches the shared engine (HelperMain /
     * SqlDelightLibraryRepository write the DB + port file under %APPDATA%\Nyora)
     * so the UI prefs sit alongside the library data.
     */
    private fun configDir(): File {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        val home = System.getProperty("user.home").orEmpty()
        val dir = when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: File(home, "AppData/Roaming").path
                File(appData, "Nyora")
            }
            os.contains("mac") || os.contains("darwin") -> {
                File(home, "Library/Application Support/Nyora")
            }
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) File(xdg, "nyora") else File(home, ".config/nyora")
            }
        }
        return dir
    }

    private fun prefsFile(): File = File(configDir(), "appPrefs.json")

    // ── First-run onboarding marker ───────────────────────────────────────────
    private fun onboardedMarker(): File = File(configDir(), ".onboarded")

    /** Dismiss the welcome/start page and remember the choice so it never shows again. */
    fun finishOnboarding() {
        showWelcome = false
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = configDir(); if (!dir.exists()) dir.mkdirs()
                onboardedMarker().writeText("1")
            }
        }
    }

    // ── Native window placement persistence ───────────────────────────────────
    private fun windowPropsFile(): File = File(configDir(), "window.properties")

    private fun readWindowProps(): java.util.Properties {
        val p = java.util.Properties()
        runCatching { windowPropsFile().takeIf { it.exists() }?.inputStream()?.use { p.load(it) } }
        return p
    }

    /** Persist the window's size + maximized state (the caller debounces the calls). */
    fun saveWindowPlacement(width: Int, height: Int, maximized: Boolean) {
        windowWidth = width; windowHeight = height; windowMaximized = maximized
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = configDir(); if (!dir.exists()) dir.mkdirs()
                val p = java.util.Properties()
                p["width"] = width.toString()
                p["height"] = height.toString()
                p["maximized"] = maximized.toString()
                windowPropsFile().outputStream().use { p.store(it, "Nyora window placement") }
            }
        }
    }

    private fun loadPrefs() {
        runCatching {
            val f = prefsFile()
            if (!f.exists()) {
                // First run — follow the Windows system theme + accent colour. The
                // user can still override both from Settings ▸ Appearance afterwards.
                WindowsNative.systemLight?.let {
                    _appearance = if (it) AppearanceMode.LIGHT else AppearanceMode.DARK
                }
                if (WindowsNative.isWindows) _accent = Accent.SYSTEM
                return
            }
            val dto = prefsJson.decodeFromString<AppPrefs>(f.readText())
            _appearance = runCatching { AppearanceMode.valueOf(dto.appearance) }.getOrDefault(AppearanceMode.DARK)
            _accent     = runCatching { Accent.valueOf(dto.accent) }.getOrDefault(Accent.SYSTEM)
            _anilistToken = dto.anilistToken
            syncToken     = dto.syncToken
            syncServerUrl = dto.syncServerUrl
            // Reader
            defaultReaderMode    = runCatching { ReaderMode.valueOf(dto.defaultReaderMode) }.getOrDefault(ReaderMode.PAGED)
            autoDetectReaderMode = dto.autoDetectReaderMode
            _readerBackground    = dto.readerBackground
            showZoomButtons      = dto.showZoomButtons
            twoPageLandscape     = dto.twoPageLandscape
            autoHideControls     = dto.autoHideControls
            keepScreenOn         = dto.keepScreenOn
            descriptionCollapse  = dto.descriptionCollapse
            gridSize             = dto.gridSize
            // Library / History
            historyRetentionDays = dto.historyRetentionDays
            groupHistoryByDate   = dto.groupHistoryByDate
            historySortOrder     = dto.historySortOrder
            hideNsfwSources      = dto.hideNsfwSources
            // Translation
            instantTranslate     = dto.instantTranslate
            translateTarget      = dto.translateTarget
            translateLangs       = dto.translateLangs
            // AI refinement
            aiMode               = runCatching { AiMode.valueOf(dto.aiMode) }.getOrDefault(AiMode.OFF)
            byokBaseUrl          = dto.byokBaseUrl
            byokApiKey           = dto.byokApiKey
            byokModel            = dto.byokModel
            // Privacy
            _incognito           = dto.incognito
            confirmBeforeQuit    = dto.confirmBeforeQuit
            // Advanced
            appLocale            = dto.appLocale
        }
    }

    private fun savePrefs() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = configDir()
                if (!dir.exists()) dir.mkdirs()
                val dto = AppPrefs(
                    appearance = appearance.name,
                    accent = accent.name,
                    anilistToken = anilistToken,
                    syncToken = syncToken,
                    syncServerUrl = syncServerUrl,
                    // Reader
                    defaultReaderMode = defaultReaderMode.name,
                    autoDetectReaderMode = autoDetectReaderMode,
                    readerBackground = readerBackground,
                    showZoomButtons = showZoomButtons,
                    twoPageLandscape = twoPageLandscape,
                    autoHideControls = autoHideControls,
                    keepScreenOn = keepScreenOn,
                    descriptionCollapse = descriptionCollapse,
                    gridSize = gridSize,
                    // Library / History
                    historyRetentionDays = historyRetentionDays,
                    groupHistoryByDate = groupHistoryByDate,
                    historySortOrder = historySortOrder,
                    hideNsfwSources = hideNsfwSources,
                    // Translation
                    instantTranslate = instantTranslate,
                    translateTarget = translateTarget,
                    translateLangs = translateLangs,
                    // AI refinement
                    aiMode = aiMode.name,
                    byokBaseUrl = byokBaseUrl,
                    byokApiKey = byokApiKey,
                    byokModel = byokModel,
                    // Privacy
                    incognito = incognito,
                    confirmBeforeQuit = confirmBeforeQuit,
                    // Advanced
                    appLocale = appLocale,
                )
                prefsFile().writeText(prefsJson.encodeToString(AppPrefs.serializer(), dto))
            }
        }
    }

    // ── Stats ───────────────────────────────────────────────────────────────────

    fun loadStats() {
        statsLoading = true
        scope.launch {
            runCatching {
                val body = http.get("/stats")
                stats = http.parse<StatsResponse>(body)
            }.onFailure { showStatus("Stats load failed: ${it.message}") }
            statsLoading = false
        }
    }

    // ── Suggestions ──────────────────────────────────────────────────────────────

    fun loadSuggestions() {
        suggestionsLoading = true
        scope.launch {
            runCatching {
                val body = http.get("/suggestions")
                suggestions = http.parse<SuggestionsResponse>(body).entries
            }.onFailure { showStatus("Suggestions failed: ${it.message}") }
            suggestionsLoading = false
        }
    }

    // ── Alternative sources ───────────────────────────────────────────────────────

    fun loadAlternatives(title: String) {
        if (title.isBlank()) return
        alternativesLoading = true
        alternatives = emptyList()
        scope.launch {
            runCatching {
                val encoded = URLEncoder.encode(title, "UTF-8")
                val body = http.get("/manga/alternatives?title=$encoded")
                alternatives = http.parse<AlternativesResponse>(body).entries.map { it.manga }
            }.onFailure { showStatus("Alternatives failed: ${it.message}") }
            alternativesLoading = false
        }
    }

    // ── Network settings ──────────────────────────────────────────────────────────

    fun loadNetworkSettings() {
        scope.launch {
            runCatching {
                val body = http.get("/settings/network")
                networkSettings = http.parse<NetworkSettingsResponse>(body).settings
            }.onFailure { showStatus("Network settings failed: ${it.message}") }
        }
    }

    fun saveNetworkSettings(dto: NetworkSettingsDto) {
        scope.launch {
            runCatching {
                val params = buildString {
                    append("proxyType=${URLEncoder.encode(dto.proxyType, "UTF-8")}")
                    append("&proxyAddress=${URLEncoder.encode(dto.proxyAddress, "UTF-8")}")
                    append("&proxyPort=${dto.proxyPort}")
                    append("&dnsOverHttps=${URLEncoder.encode(dto.dnsOverHttps, "UTF-8")}")
                    append("&githubMirror=${URLEncoder.encode(dto.githubMirror, "UTF-8")}")
                    append("&imagesProxy=${URLEncoder.encode(dto.imagesProxy, "UTF-8")}")
                    append("&sslBypass=${dto.sslBypass}")
                    append("&disableConnectivityCheck=${dto.disableConnectivityCheck}")
                    append("&adTrackerBlocking=${dto.adTrackerBlocking}")
                    append("&ignoreSSLErrors=${dto.ignoreSSLErrors}")
                }
                val body = http.post("/settings/network?$params")
                networkSettings = http.parse<NetworkSettingsResponse>(body).settings
                showStatus("Network settings saved.")
            }.onFailure { showStatus("Save failed: ${it.message}") }
        }
    }

    // ── Download settings ─────────────────────────────────────────────────────────

    fun loadDownloadSettings() {
        scope.launch {
            runCatching {
                val body = http.get("/downloads/settings")
                downloadSettings = http.parse<DownloadSettingsResponse>(body).settings
            }.onFailure { showStatus("Download settings failed: ${it.message}") }
        }
    }

    fun saveDownloadSettings(dto: DownloadSettingsDto) {
        scope.launch {
            runCatching {
                // NOTE: the POST param is "maxConcurrent" (the response key is
                // "maxConcurrentDownloads" — they intentionally differ).
                val params = "maxConcurrent=${dto.maxConcurrentDownloads}&format=${URLEncoder.encode(dto.format, "UTF-8")}"
                val body = http.post("/downloads/settings?$params")
                downloadSettings = http.parse<DownloadSettingsResponse>(body).settings
                showStatus("Download settings saved.")
            }.onFailure { showStatus("Save failed: ${it.message}") }
        }
    }

    // ── Backup ────────────────────────────────────────────────────────────────────

    fun exportBackup(path: String) {
        scope.launch {
            runCatching {
                val body = http.get("/backup/export")
                withContext(Dispatchers.IO) { File(path).writeText(body) }
                showStatus("Backup exported.")
            }.onFailure { showStatus("Export failed: ${it.message}") }
        }
    }

    fun importBackup(path: String) {
        scope.launch {
            runCatching {
                val content = withContext(Dispatchers.IO) { File(path).readText() }
                http.post("/backup/import", content)
                refreshLibrary()
                showStatus("Backup imported.")
            }.onFailure { showStatus("Import failed: ${it.message}") }
        }
    }

    // ── Trackers: AniList ──────────────────────────────────────────────────────────
    //
    // The AniList endpoints require an "Authorization: Bearer <token>" header,
    // which NyoraHttpClient does not expose, so these use a small dedicated
    // OkHttp client that injects the header.

    fun setAnilistToken(token: String) {
        _anilistToken = token
        savePrefs()
    }

    fun anilistSearch(query: String) {
        if (query.isBlank()) return
        if (anilistToken.isBlank()) { showStatus("Set an AniList token first."); return }
        anilistLoading = true
        scope.launch {
            runCatching {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val raw = authGet("/tracker/anilist/search?title=$encoded", anilistToken)
                anilistResult = prefsJson.decodeFromString<AniListSearchResponse>(raw)
            }.onFailure { showStatus("AniList search failed: ${it.message}") }
            anilistLoading = false
        }
    }

    fun anilistScrobble(mediaId: Int, progress: Int) {
        if (anilistToken.isBlank()) { showStatus("Set an AniList token first."); return }
        scope.launch {
            runCatching {
                authPost("/tracker/anilist/scrobble?mediaId=$mediaId&progress=$progress", anilistToken)
                showStatus("Progress synced to AniList.")
            }.onFailure { showStatus("Scrobble failed: ${it.message}") }
        }
    }

    // ── "For You" feed (AniList trending) ────────────────────────────────────────
    //
    // Mirrors nyora-android's AnilistRepository: a DIRECT POST to the public
    // AniList GraphQL API (https://graphql.anilist.co) — NOT the Nyora proxy and
    // NOT imageBaseUrl. No Authorization header (this endpoint needs none). We
    // reuse the existing `authHttp` OkHttp client for its timeouts but build a
    // plain request without a bearer token.

    fun loadAnilistFeed() {
        if (anilistFeed.isNotEmpty()) return // already loaded; avoid refetch
        anilistFeedLoading = true
        scope.launch {
            runCatching {
                val query = """
                    query { Page(page: 1, perPage: 30) { media(type: MANGA, sort: TRENDING_DESC) { id title { romaji english native } coverImage { extraLarge large } description averageScore genres } } }
                """.trimIndent()
                val payload = prefsJson.encodeToString(
                    AniListQueryBody.serializer(), AniListQueryBody(query = query),
                )
                val raw = withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url("https://graphql.anilist.co")
                        .header("Content-Type", "application/json")
                        .post(payload.toRequestBody("application/json".toMediaType()))
                        .build()
                    authHttp.newCall(req).execute().use { it.body!!.string() }
                }
                val resp = prefsJson.decodeFromString<AniListFeedResponse>(raw)
                withContext(Dispatchers.Main) { anilistFeed = resp.data.Page.media }
            }.onFailure { showStatus("Discover feed failed: ${it.message}") }
            anilistFeedLoading = false
        }
    }

    /** Best display title for an AniList feed media item. */
    fun anilistFeedTitle(m: AniListFeedMedia): String =
        m.title.english ?: m.title.romaji ?: m.title.native ?: "Untitled"

    // ── Sync ───────────────────────────────────────────────────────────────────────

    fun refreshCloudSyncStatus() {
        scope.launch {
            runCatching {
                cloudSyncStatus = fetchCloudSyncStatus()
            }.onFailure { showStatus("Cloud sync status failed: ${it.message}") }
        }
    }

    fun cloudSignInWithGoogle() {
        if (cloudSyncBusy) return
        scope.launch {
            cloudSyncBusy = true
            runCatching {
                val status = fetchCloudSyncStatus()
                cloudSyncStatus = status
                if (!status.isConfigured) error("Supabase is not configured.")

                val desktopClientId = status.googleDesktopClientId.ifBlank { DESKTOP_GOOGLE_CLIENT_ID }
                showStatus("Opening Google sign-in...")
                val idToken = requestGoogleIdToken(desktopClientId)
                val signInRaw = http.post("/supabase/signin?idToken=${URLEncoder.encode(idToken, "UTF-8")}")
                requireSupabaseOk(signInRaw, "Supabase sign-in failed")

                val hasLocalData = prefsJson.decodeFromString<SupabaseLocalDataResponse>(
                    http.get("/supabase/has-local-data"),
                ).hasLocalData
                if (hasLocalData) {
                    showStatus("Syncing library...")
                    requireSupabaseOk(http.post("/supabase/sync"), "Cloud sync failed")
                } else {
                    showStatus("Restoring cloud library...")
                    requireSupabaseOk(http.post("/supabase/restore-from-cloud"), "Cloud restore failed")
                }
                refreshLibrary()
                cloudSyncStatus = fetchCloudSyncStatus()
                showStatus("Cloud sync ready.")
            }.onFailure { showStatus("Google sign-in failed: ${it.message}") }
            cloudSyncBusy = false
        }
    }

    fun cloudSyncNow() {
        if (cloudSyncBusy) return
        scope.launch {
            cloudSyncBusy = true
            runCatching {
                requireSupabaseOk(http.post("/supabase/sync"), "Cloud sync failed")
                refreshLibrary()
                cloudSyncStatus = fetchCloudSyncStatus()
                showStatus("Cloud sync complete.")
            }.onFailure { showStatus("Cloud sync failed: ${it.message}") }
            cloudSyncBusy = false
        }
    }

    fun cloudRestoreFromCloud() {
        if (cloudSyncBusy) return
        scope.launch {
            cloudSyncBusy = true
            runCatching {
                requireSupabaseOk(http.post("/supabase/restore-from-cloud"), "Cloud restore failed")
                refreshLibrary()
                cloudSyncStatus = fetchCloudSyncStatus()
                showStatus("Cloud library restored.")
            }.onFailure { showStatus("Cloud restore failed: ${it.message}") }
            cloudSyncBusy = false
        }
    }

    fun cloudSignOut() {
        if (cloudSyncBusy) return
        scope.launch {
            cloudSyncBusy = true
            runCatching {
                requireSupabaseOk(http.post("/supabase/signout"), "Cloud sign-out failed")
                cloudSyncStatus = fetchCloudSyncStatus()
                showStatus("Signed out of cloud sync.")
            }.onFailure { showStatus("Cloud sign-out failed: ${it.message}") }
            cloudSyncBusy = false
        }
    }

    private suspend fun fetchCloudSyncStatus(): SupabaseStatusResponse =
        prefsJson.decodeFromString(http.get("/supabase/status"))

    fun refreshOtaStatus() {
        scope.launch {
            runCatching {
                otaStatus = prefsJson.decodeFromString<OtaStatusResponse>(http.get("/ota/status"))
            }.onFailure { showStatus("Parser status check failed: ${it.message}") }
        }
    }

    fun otaCheckNow() {
        if (otaBusy) return
        scope.launch {
            otaBusy = true
            runCatching {
                otaStatus = prefsJson.decodeFromString<OtaStatusResponse>(http.post("/ota/check"))
                val st = otaStatus
                if (st != null && st.isActive && st.otaVersion > st.bundledVersion)
                    showStatus("Parsers updated to v${st.otaVersion}. Restart to apply.")
                else
                    showStatus("Parsers are up to date.")
            }.onFailure { showStatus("Parser update check failed: ${it.message}") }
            otaBusy = false
        }
    }

    private fun requireSupabaseOk(raw: String, fallback: String) {
        val resp = prefsJson.decodeFromString<SupabaseOkResponse>(raw)
        if (!resp.ok) error(resp.error.ifBlank { fallback })
    }

    private suspend fun requestGoogleIdToken(clientId: String): String = withContext(Dispatchers.IO) {
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(24)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val callbackPath = "/oauth/google/callback"
        val redirectUri = "http://127.0.0.1:${server.address.port}$callbackPath"
        val result = CompletableFuture<Result<String>>()

        server.createContext(callbackPath) { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery)
            val body = if (params["state"] != state) {
                result.complete(Result.failure(IllegalStateException("OAuth state mismatch.")))
                "Nyora sign-in failed. Return to the app."
            } else if (!params["error"].isNullOrBlank()) {
                result.complete(Result.failure(IllegalStateException(params["error_description"] ?: params["error"] ?: "Google rejected sign-in.")))
                "Nyora sign-in failed. Return to the app."
            } else {
                val code = params["code"].orEmpty()
                if (code.isBlank()) {
                    result.complete(Result.failure(IllegalStateException("Google returned no auth code.")))
                    "Nyora sign-in failed. Return to the app."
                } else {
                    result.complete(Result.success(code))
                    "Nyora sign-in complete. You can return to the app."
                }
            }
            val bytes = "<html><body><p>$body</p></body></html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
                "?client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
                "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
                "&response_type=code" +
                "&scope=${URLEncoder.encode("openid email profile", "UTF-8")}" +
                "&code_challenge=${URLEncoder.encode(pkceChallenge(verifier), "UTF-8")}" +
                "&code_challenge_method=S256" +
                "&state=${URLEncoder.encode(state, "UTF-8")}" +
                "&prompt=select_account"
            openExternalUrl(authUrl)
            val code = result.get(120, TimeUnit.SECONDS).getOrThrow()
            val tokenFormBuilder = FormBody.Builder()
                .add("client_id", clientId)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", redirectUri)
            val clientSecret = SupabaseConfig.googleClientSecret
            if (clientSecret.isNotBlank()) tokenFormBuilder.add("client_secret", clientSecret)
            val tokenBody = tokenFormBuilder.build()
            val req = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(tokenBody)
                .build()
            authHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val token = runCatching { prefsJson.decodeFromString<GoogleOAuthTokenResponse>(text) }
                    .getOrDefault(GoogleOAuthTokenResponse())
                if (!resp.isSuccessful) {
                    error(token.error_description.ifBlank { token.error.ifBlank { "Google token exchange failed ${resp.code}" } })
                }
                token.id_token.ifBlank { error("Google did not return an ID token.") }
            }
        } finally {
            server.stop(0)
        }
    }

    fun openExternalUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("win") -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
            os.contains("mac") || os.contains("darwin") -> ProcessBuilder("open", url).start()
            else -> ProcessBuilder("xdg-open", url).start()
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split("&")
            .filter { it.isNotBlank() }
            .associate { part ->
                val idx = part.indexOf("=")
                val key = if (idx >= 0) part.substring(0, idx) else part
                val value = if (idx >= 0) part.substring(idx + 1) else ""
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun syncSignIn(email: String, password: String, server: String = syncServerUrl) {
        if (server.isBlank()) { showStatus("Set a sync server URL first."); return }
        scope.launch {
            runCatching {
                val payload = prefsJson.encodeToString(
                    SignInBody.serializer(), SignInBody(email = email, password = password),
                )
                val encServer = URLEncoder.encode(server, "UTF-8")
                val raw = http.post("/sync/signin?server=$encServer", payload)
                val resp = prefsJson.decodeFromString<SyncSignInResponse>(raw)
                val token = resp.token ?: resp.accessToken
                if (!token.isNullOrBlank()) {
                    syncToken = token
                    syncServerUrl = server
                    savePrefs()
                    showStatus("Signed in to sync.")
                } else {
                    showStatus("Sign-in failed: ${resp.error ?: resp.message ?: "no token"}")
                }
            }.onFailure { showStatus("Sign-in failed: ${it.message}") }
        }
    }

    fun syncPush(server: String = syncServerUrl) {
        if (syncToken.isBlank()) { showStatus("Sign in to sync first."); return }
        if (server.isBlank()) { showStatus("Set a sync server URL first."); return }
        scope.launch {
            runCatching {
                val encServer = URLEncoder.encode(server, "UTF-8")
                authPost("/sync/push?server=$encServer", syncToken)
                showStatus("Library pushed to sync.")
            }.onFailure { showStatus("Push failed: ${it.message}") }
        }
    }

    // ── Authenticated HTTP helpers (Bearer header) ──────────────────────────────────
    // NyoraHttpClient cannot attach headers; these mirror its baseUrl + IO usage.

    private val authHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private suspend fun authGet(path: String, bearer: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$imageBaseUrl$path")
            .header("Authorization", "Bearer $bearer")
            .get().build()
        authHttp.newCall(req).execute().use { it.body!!.string() }
    }

    private suspend fun authPost(path: String, bearer: String, body: String = "{}"): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$imageBaseUrl$path")
                .header("Authorization", "Bearer $bearer")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            authHttp.newCall(req).execute().use { it.body!!.string() }
        }

    private val prefsJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Serializable
    private data class AppPrefs(
        val appearance: String = AppearanceMode.DARK.name,
        val accent: String = Accent.SYSTEM.name,
        val anilistToken: String = "",
        val syncToken: String = "",
        val syncServerUrl: String = "",
        // Reader
        val defaultReaderMode: String = ReaderMode.PAGED.name,
        val autoDetectReaderMode: Boolean = false,
        val readerBackground: String = "auto",
        val showZoomButtons: Boolean = true,
        val twoPageLandscape: Boolean = false,
        val autoHideControls: Boolean = true,
        val keepScreenOn: Boolean = false,
        val descriptionCollapse: Boolean = true,
        val gridSize: Int = 160,
        // Library / History
        val historyRetentionDays: Int = 0,
        val groupHistoryByDate: Boolean = true,
        val historySortOrder: String = "recent",
        val hideNsfwSources: Boolean = true,
        // Translation
        val instantTranslate: Boolean = false,
        val translateTarget: String = "en",
        val translateLangs: String = "ja",
        // AI refinement
        val aiMode: String = AiMode.OFF.name,
        val byokBaseUrl: String = "https://api.openai.com/v1",
        val byokApiKey: String = "",
        val byokModel: String = "gpt-4o-mini",
        // Privacy
        val incognito: Boolean = false,
        val confirmBeforeQuit: Boolean = false,
        // Advanced
        val appLocale: String = "auto",
    )

    @Serializable
    private data class SignInBody(val email: String = "", val password: String = "")

    @Serializable
    private data class GoogleOAuthTokenResponse(
        val id_token: String = "",
        val error: String = "",
        val error_description: String = "",
    )

    @Serializable
    private data class AniListQueryBody(val query: String = "")

    private companion object {
        private const val DESKTOP_GOOGLE_CLIENT_ID = "181067068545-5r2ob1jv4mc0v8gd52fgk2jt28pk3370.apps.googleusercontent.com"
    }

    private fun showStatus(message: String) {
        statusMessage = message
        scope.launch {
            delay(3000)
            if (statusMessage == message) statusMessage = null
        }
    }
}
