package com.nyora.windows.bridge

import com.nyora.hasan72341.shared.model.Manga
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// DTOs for the "extra" backend endpoints the Linux app calls. Field names are
// mirrored EXACTLY from the REST server handlers in
//   nyora-mac/shared/.../proxy/NyoraRestServer.kt
// Most of these payloads are hand-built with buildJsonObject, so a mismatched
// key would silently deserialize to its default. ignoreUnknownKeys is enabled
// in NyoraHttpClient, so extra server-side fields are harmless.
// ---------------------------------------------------------------------------

// ----- /stats (GET) -----
// handleStats builds: totalChapters, distinctManga, favouritesCount,
// longestStreakDays, topSources[]. Each topSources element has keys
// { sourceId, sourceName, count } (NOT id/name).
@Serializable
data class TopSourceDto(
    val sourceId: String = "",
    val sourceName: String = "",
    val count: Int = 0,
)

@Serializable
data class StatsResponse(
    val totalChapters: Int = 0,
    val distinctManga: Int = 0,
    val favouritesCount: Int = 0,
    val longestStreakDays: Int = 0,
    val topSources: List<TopSourceDto> = emptyList(),
)

// ----- /suggestions (GET) -----
// handleSuggestions returns { entries: [...] } where each entry is the COMPACT
// projection from mangaToJson: { id, title, coverUrl, sourceId, publicUrl,
// description }. It is NOT a full Manga (no chapters/genres/source ref). We
// still deserialize into the shared Manga model (ignoreUnknownKeys is on); only
// id/title/coverUrl/description/publicUrl will be populated and the "sourceId"
// string field is ignored by Manga (Manga.source is a sealed MangaSourceRef).
// If the app needs the sourceId, use SuggestionEntryDto instead of Manga.
@Serializable
data class SuggestionsResponse(
    val entries: List<Manga> = emptyList(),
)

// Lossless view of a single /suggestions entry, matching mangaToJson keys.
@Serializable
data class SuggestionEntryDto(
    val id: String = "",
    val title: String = "",
    val coverUrl: String = "",
    val sourceId: String = "",
    val publicUrl: String = "",
    val description: String = "",
)

// ----- /manga/alternatives (GET) -----
// handleAlternatives returns { entries: [...] } where each entry is
// { sourceId, sourceName, manga: <compact manga> } — NOT a flat List<Manga>.
// The manga value is the same compact projection as suggestions.
@Serializable
data class AlternativeEntryDto(
    val sourceId: String = "",
    val sourceName: String = "",
    val manga: Manga = Manga(id = "", title = ""),
)

@Serializable
data class AlternativesResponse(
    val entries: List<AlternativeEntryDto> = emptyList(),
)

// ----- /settings/network (GET + POST) -----
// handleNetworkSettings serializes NetworkSettingsResponse(val settings:
// HelperNetworkSettings) — the payload is { settings: { ... } }. The POST
// request takes the same fields as flat query params (proxyType, proxyAddress,
// proxyPort, dnsOverHttps, githubMirror, imagesProxy, sslBypass,
// disableConnectivityCheck); the response is again wrapped in { settings }.
@Serializable
data class NetworkSettingsDto(
    val proxyType: String = "direct",
    val proxyAddress: String = "127.0.0.1",
    val proxyPort: Int = 8080,
    val dnsOverHttps: String = "none",
    val githubMirror: String = "KEIYOUSHI",
    val imagesProxy: String = "none",
    val sslBypass: Boolean = false,
    val disableConnectivityCheck: Boolean = false,
    val adTrackerBlocking: Boolean = false,
    val ignoreSSLErrors: Boolean = false,
)

@Serializable
data class NetworkSettingsResponse(
    val settings: NetworkSettingsDto = NetworkSettingsDto(),
)

// ----- /downloads/settings (GET + POST) -----
// handleDownloadSettings serializes DownloadSettingsResponse(val settings:
// DownloadSettings) — payload is { settings: { maxConcurrentDownloads, format } }.
// NOTE: the key is "maxConcurrentDownloads", not "maxConcurrent". The POST
// query params, however, are "maxConcurrent" and "format" (see handler) — the
// response keys differ from the request param names.
@Serializable
data class DownloadSettingsDto(
    val maxConcurrentDownloads: Int = 3,
    val format: String = "AUTO", // AUTO | FOLDER | CBZ | ZIP
)

@Serializable
data class DownloadSettingsResponse(
    val settings: DownloadSettingsDto = DownloadSettingsDto(),
)

// ----- /tracker/anilist/search (GET) -----
// handleAniListSearch passes the raw AniList GraphQL response straight through:
//   { "data": { "Page": { "media": [ { id, title{romaji,english,native},
//     coverImage{large}, averageScore, chapters } ] } } }
// There is no Nyora wrapper. Decode AniListSearchResponse against that shape.
@Serializable
data class AniListTitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class AniListCoverImageDto(
    val large: String? = null,
)

@Serializable
data class AniListMediaDto(
    val id: Int = 0,
    val title: AniListTitleDto = AniListTitleDto(),
    val coverImage: AniListCoverImageDto = AniListCoverImageDto(),
    val averageScore: Int? = null,
    val chapters: Int? = null,
)

@Serializable
data class AniListPageDto(
    val media: List<AniListMediaDto> = emptyList(),
)

@Serializable
data class AniListDataDto(
    val Page: AniListPageDto = AniListPageDto(),
)

@Serializable
data class AniListSearchResponse(
    val data: AniListDataDto = AniListDataDto(),
)

// ----- /sync/push (POST) -----
// handleSyncPush returns { favouritesPushed: Bool, historyPushed: Bool }.
@Serializable
data class SyncPushResultDto(
    val favouritesPushed: Boolean = false,
    val historyPushed: Boolean = false,
)

// ----- /sync/signin (POST) -----
// handleSyncSignIn proxies the remote Nyora Sync server's /auth/sign-in
// response RAW (no Nyora wrapper). The exact shape is defined by that server;
// it typically carries a token/message. SyncSignInResponse is a best-effort
// decode of common keys — treat all as optional. Read the body as raw JSON if
// the precise schema matters.
@Serializable
data class SyncSignInResponse(
    val token: String? = null,
    val accessToken: String? = null,
    val ok: Boolean? = null,
    val message: String? = null,
    val error: String? = null,
)

// ----- /supabase/* (GET + POST) -----
// These endpoints are implemented by the shared helper and sync through the
// Supabase nyora-sync edge function. Google OAuth happens in the desktop app,
// then /supabase/signin exchanges the resulting ID token for a Supabase session.
@Serializable
data class SupabaseStatusResponse(
    val isConfigured: Boolean = false,
    val isAuthenticated: Boolean = false,
    val userId: String = "",
    val email: String = "",
    val lastSyncTimestamp: String = "",
    val googleDesktopClientId: String = "",
    val googleServerClientId: String = "",
)

@Serializable
data class SupabaseOkResponse(
    val ok: Boolean = false,
    val error: String = "",
)

@Serializable
data class SupabaseLocalDataResponse(
    val hasLocalData: Boolean = false,
)

// ----- /manga/prefs (GET) + /manga/prefs/save (POST) -----
// handleMangaPrefs returns either { present: false } (no row) or the full
// MangaPrefsDto below (mirrors MangaPrefsRow + a "present" flag). There is no
// "rtl" field — orientation/RTL is encoded in readerMode (empty = inherit app
// default). brightness/contrast/saturation/hue/palette are the colour-correction
// values; palette empty = none. /manga/prefs/save returns { ok: true } only.
@Serializable
data class MangaPrefsDto(
    val mangaId: String = "",
    val readerMode: String = "",
    val brightness: Double = 0.0,
    val contrast: Double = 1.0,
    val saturation: Double = 1.0,
    val hue: Double = 0.0,
    val palette: String = "",
    val present: Boolean = false,
)

// ----- /backup/import (POST) -----
// handleBackupImport returns { ok, importedFavourites, importedHistory }.
// (See note on /backup/export below — export returns raw JSON, no DTO.)
@Serializable
data class BackupImportResultDto(
    val ok: Boolean = false,
    val importedFavourites: Int = 0,
    val importedHistory: Int = 0,
)

// ----- /ota/status (GET) + /ota/check (POST) -----
// Both return { bundledVersion, otaVersion, isActive }.
@Serializable
data class OtaStatusResponse(
    val bundledVersion: Int = 0,
    val otaVersion: Int = 0,
    val isActive: Boolean = false,
)

// ----- Generic { ok: true } ack -----
// Many mutating endpoints (manga/prefs/save, history/*, favourites toggle,
// downloads/cancel, etc.) reply with a simple { ok: true }.
@Serializable
data class OkResponse(
    val ok: Boolean = false,
)

// ----- AniList "For You" trending feed (DIRECT to https://graphql.anilist.co) -----
// Mirrors nyora-android's AnilistRepository: POSTs a GraphQL query for
//   Page(page:1, perPage:30) { media(type:MANGA, sort:TRENDING_DESC) { ... } }
// and gets back { "data": { "Page": { "media": [ ... ] } } }. This is the public
// AniList API (no auth needed) — NOT a Nyora REST endpoint. These DTOs are
// separate from the AniList*Dto search models above because the feed needs the
// richer media fields (extraLarge cover, description, genres).
@Serializable
data class AniListFeedTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class AniListFeedCover(
    val extraLarge: String? = null,
    val large: String? = null,
)

@Serializable
data class AniListFeedMedia(
    val id: Int = 0,
    val title: AniListFeedTitle = AniListFeedTitle(),
    val coverImage: AniListFeedCover = AniListFeedCover(),
    val description: String? = null,
    val averageScore: Int? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
data class AniListFeedPage(
    val media: List<AniListFeedMedia> = emptyList(),
)

@Serializable
data class AniListFeedData(
    val Page: AniListFeedPage = AniListFeedPage(),
)

@Serializable
data class AniListFeedResponse(
    val data: AniListFeedData = AniListFeedData(),
)
