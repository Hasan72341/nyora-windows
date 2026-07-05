package com.nyora.windows.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.ExploreMode
import com.nyora.windows.ui.theme.AnimeAsyncImage
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.glassOverlay
import com.nyora.windows.ui.theme.hoverLift
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaSource

/**
 * Explore — a two-pane browser modelled on the macOS app: a curated, scrollable
 * source pane (Pinned / Installed, with a filter + pin toggles + "Add sources") on
 * the left, and a browse pane (source header → Popular/Latest/Search modes → hero →
 * catalogue grid) on the right. Recommendations live on their own "For You" screen.
 */
@Composable
fun ExploreScreen(state: AppState) {
    // No sources are exposed until a source repository is added. Until then,
    // Explore shows the repository prompt.
    if (!state.repositoryActive) {
        NoRepositoryView(state)
        return
    }
    Row(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SourcePane(
            state = state,
            modifier = Modifier.widthIn(min = 220.dp, max = 360.dp).weight(0.3f).fillMaxHeight(),
        )
        BrowsePane(
            state = state,
            modifier = Modifier.weight(0.7f).fillMaxHeight(),
        )
    }
}

// ── Source repository prompt ────────────────────────────────────────────────────

@Composable
private fun NoRepositoryView(state: AppState) {
    val accent = MaterialTheme.colorScheme.primary
    var link by remember { mutableStateOf(state.repositoryUrl) }
    fun submit() { if (!state.repoLoading) state.addSourceRepository(link) }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = accent, modifier = Modifier.size(38.dp))
            Text(
                "Add a source repository",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Paste a source repository link to load its sources.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth()
                    .glassOverlay(shape = RoundedCornerShape(12.dp))
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = link,
                    onValueChange = { link = it; if (state.repoError != null) state.repoError = null },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(accent),
                    modifier = Modifier.weight(1f).padding(vertical = 13.dp),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    decorationBox = { inner ->
                        if (link.isEmpty()) {
                            Text("https://…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    },
                )
                if (state.repoLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp).padding(end = 8.dp), strokeWidth = 2.dp, color = accent)
                } else {
                    TextButton(onClick = { submit() }) { Text("Add") }
                }
            }
            state.repoError?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ── Source pane ───────────────────────────────────────────────────────────────────

@Composable
private fun SourcePane(state: AppState, modifier: Modifier = Modifier) {
    var filter by remember { mutableStateOf("") }

    // Source visibility is gated by the SOURCE 18+ toggle (hideNsfwSources), NOT by
    // the "Hide NSFW Content" (manga) flag — so NSFW *sources* only appear once the
    // user opts into 18+ sources.
    val installed = state.sources.filter { it.isInstalled && (!state.hideNsfwSources || !it.isNsfw) }
    val q = filter.trim().lowercase()
    val matched = if (q.isEmpty()) installed
    else installed.filter { it.name.lowercase().contains(q) || it.lang.lowercase().contains(q) }
    val pinned = matched.filter { it.isPinned }.sortedBy { it.name.lowercase() }
    // Remaining (non-pinned) sources, grouped by language like the web onboarding /
    // Explore and the app's own CatalogSheet. Each group = a language section with a
    // header + count; groups are ordered by size (desc) then language label (asc),
    // with English kept first, and each group's rows sorted by name.
    val rest = matched.filter { !it.isPinned }
    val langGroups = rest.groupBy { it.lang.lowercase().ifBlank { "??" } }
        .map { (lang, srcs) -> lang to srcs.sortedBy { it.name.lowercase() } }
        .sortedWith(
            compareByDescending<Pair<String, List<MangaSource>>> { it.first == "en" }
                .thenByDescending { it.second.size }
                .thenBy { langLabel(it.first) },
        )

    Column(
        modifier = modifier
            .glassCard(shape = RoundedCornerShape(26.dp), fill = NyoraTokens.surface1)
            .padding(20.dp),
    ) {
        SectionHeader(title = "Sources", subtitle = "Browse engines")

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatPill(value = installed.size.toString(), label = "Installed", modifier = Modifier.weight(1f))
            AddSourcesButton(
                modifier = Modifier.weight(1f),
                onClick = { state.loadCatalog(); state.showCatalog = true },
            )
        }

        Spacer(Modifier.height(14.dp))

        FilterField(value = filter, onValueChange = { filter = it })

        Spacer(Modifier.height(14.dp))

        if (installed.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No sources installed",
                        color = NyoraTokens.onSurfaceBody,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { state.loadCatalog(); state.showCatalog = true }) {
                        Text("Open catalog", color = LocalNyoraAccent.current.color)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pinned.isNotEmpty()) {
                    item(key = "h-pinned") { GroupLabel("Pinned") }
                    items(pinned, key = { "p-${it.id}" }) { src ->
                        SourceRow(
                            source = src,
                            selected = state.activeSource?.id == src.id,
                            onClick = { selectSource(state, src) },
                            onTogglePin = { state.togglePin(src.id) },
                        )
                    }
                }
                langGroups.forEach { (lang, srcs) ->
                    item(key = "h-lang-$lang") {
                        GroupLabel("${langLabel(lang)} ${srcs.size}")
                    }
                    items(srcs, key = { "i-${it.id}" }) { src ->
                        SourceRow(
                            source = src,
                            selected = state.activeSource?.id == src.id,
                            onClick = { selectSource(state, src) },
                            onTogglePin = { state.togglePin(src.id) },
                        )
                    }
                }
                if (matched.isEmpty()) {
                    item(key = "no-match") {
                        Text(
                            "No sources match \"$filter\"",
                            color = NyoraTokens.onSurfaceFaint,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun selectSource(state: AppState, source: MangaSource) {
    state.searchQuery = ""
    state.exploreMode = ExploreMode.POPULAR
    state.browseManga(source, 1)
}

@Composable
private fun StatPill(value: String, label: String, modifier: Modifier = Modifier) {
    val accent = LocalNyoraAccent.current.color
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(accent)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AddSourcesButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .hoverLift(shape = RoundedCornerShape(14.dp), scaleTo = 1.04f)
            .glassOverlay(shape = RoundedCornerShape(14.dp), fill = NyoraTokens.surface1)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = LocalNyoraAccent.current.color, modifier = Modifier.size(18.dp))
        Column {
            Text("Add", color = NyoraTokens.onSurfaceHigh, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Catalog", color = NyoraTokens.onSurfaceFaint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FilterField(value: String, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .glassOverlay(shape = RoundedCornerShape(20.dp), fill = NyoraTokens.surface1),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = NyoraTokens.onSurfaceFaint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = NyoraTokens.onSurfaceHigh, fontSize = 13.sp),
                cursorBrush = SolidColor(LocalNyoraAccent.current.color),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text("Filter sources", color = NyoraTokens.onSurfaceFaint, fontSize = 13.sp)
                    }
                    inner()
                },
            )
        }
    }
}

// Language-code → display label, matching the app's SettingsScreen/CatalogSheet
// conventions. Unknown codes fall back to the uppercased code (e.g. "PT-BR").
private val LANG_LABELS = mapOf(
    "en" to "English", "ja" to "Japanese", "zh" to "Chinese", "ko" to "Korean",
    "es" to "Spanish", "fr" to "French", "de" to "German", "pt" to "Portuguese",
    "ru" to "Russian", "ar" to "Arabic", "it" to "Italian", "id" to "Indonesian",
    "tr" to "Turkish", "vi" to "Vietnamese", "th" to "Thai", "pl" to "Polish",
    "??" to "Other",
)

private fun langLabel(lang: String): String =
    LANG_LABELS[lang.lowercase()] ?: lang.uppercase()

@Composable
private fun GroupLabel(text: String) {
    SystemTag(text = text, color = NyoraTokens.onSurfaceFaint, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp))
}

@Composable
private fun SourceRow(
    source: MangaSource,
    selected: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val accent = LocalNyoraAccent.current.color
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverLift(shape = shape, scaleTo = 1.02f)
            .clip(shape)
            .then(
                if (selected) Modifier.background(accent.copy(alpha = 0.16f))
                    .border(1.dp, accent.copy(alpha = 0.40f), shape)
                else Modifier.background(NyoraTokens.surface1),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Two-letter language medallion.
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (selected) 0.30f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = source.lang.uppercase().take(2).ifBlank { "??" },
                color = if (selected) Color.White else accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
            )
        }

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = source.name,
                    color = if (selected) Color.White else NyoraTokens.onSurfaceHigh,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (source.isNsfw) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFE53935))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "18+",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
            Text(
                text = "${source.engine} · ${source.lang.uppercase()}${if (source.isNsfw) " · NSFW" else ""}",
                color = if (selected) Color.White.copy(alpha = 0.7f) else if (source.isNsfw) Color(0xFFE53935) else NyoraTokens.onSurfaceFaint,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).clickable { onTogglePin() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (source.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (source.isPinned) "Unpin" else "Pin",
                tint = when {
                    source.isPinned -> accent
                    selected -> Color.White.copy(alpha = 0.6f)
                    else -> NyoraTokens.onSurfaceFaint
                },
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

// ── Browse pane ───────────────────────────────────────────────────────────────────

@Composable
private fun BrowsePane(state: AppState, modifier: Modifier = Modifier) {
    val source = state.activeSource
    Column(
        modifier = modifier
            .glassCard(shape = RoundedCornerShape(28.dp), fill = NyoraTokens.surface1)
            .padding(24.dp),
    ) {
        if (source == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SectionHeader(title = "Explore", subtitle = "Pick a source")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Choose a source on the left to browse its catalogue.",
                        color = NyoraTokens.onSurfaceFaint,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            return@Column
        }

        BrowseHeader(state, source)
        Spacer(Modifier.height(18.dp))
        ModeSwitcher(state, source)

        if (state.exploreMode == ExploreMode.SEARCH) {
            Spacer(Modifier.height(14.dp))
            SearchField(state, source)
        }

        Spacer(Modifier.height(18.dp))

        BrowseBody(state, source)
    }
}

@Composable
private fun BrowseHeader(state: AppState, source: MangaSource) {
    val accent = LocalNyoraAccent.current.color
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                color = NyoraTokens.onSurfaceHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${source.engine} · ${source.lang.uppercase()}",
                color = NyoraTokens.onSurfaceFaint,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Language badge.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(accent)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(source.lang.uppercase().ifBlank { "—" }, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .hoverLift(shape = RoundedCornerShape(13.dp), scaleTo = 1.06f)
                .glassOverlay(shape = RoundedCornerShape(13.dp), fill = NyoraTokens.surface1)
                .clickable { state.browseManga(source, 1) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Refresh, "Refresh", tint = NyoraTokens.onSurfaceBody, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ModeSwitcher(state: AppState, source: MangaSource) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(NyoraTokens.surface1)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExploreMode.values().forEach { mode ->
            val label = when (mode) {
                ExploreMode.POPULAR -> "Popular"
                ExploreMode.LATEST -> "Latest"
                ExploreMode.SEARCH -> "Search"
            }
            ModePill(
                label = label,
                selected = state.exploreMode == mode,
                onClick = {
                    state.exploreMode = mode
                    if (mode == ExploreMode.SEARCH) {
                        // Wait for the user to submit a query; clear stale results.
                        state.exploreManga = emptyList()
                        state.exploreError = null
                    } else {
                        state.searchQuery = ""
                        state.browseManga(source, 1)
                    }
                },
            )
        }
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalNyoraAccent.current.color
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .then(if (selected) Modifier.background(accent) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else NyoraTokens.onSurfaceBody,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun SearchField(state: AppState, source: MangaSource) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .glassOverlay(shape = RoundedCornerShape(24.dp), fill = NyoraTokens.surface1),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = NyoraTokens.onSurfaceFaint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = state.searchQuery,
                onValueChange = { state.searchQuery = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = NyoraTokens.onSurfaceHigh),
                cursorBrush = SolidColor(LocalNyoraAccent.current.color),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (state.searchQuery.isNotBlank()) state.browseManga(source, 1)
                }),
                decorationBox = { inner ->
                    if (state.searchQuery.isEmpty()) {
                        Text("Search ${source.name} — press Enter", color = NyoraTokens.onSurfaceFaint, fontSize = 14.sp)
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
private fun ColumnScope.BrowseBody(state: AppState, source: MangaSource) {
    val mangaList = state.exploreManga
    when {
        state.exploreLoading && mangaList.isEmpty() ->
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LocalNyoraAccent.current.color)
            }

        state.exploreError != null && mangaList.isEmpty() ->
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load this source", color = NyoraTokens.onSurfaceBody, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(state.exploreError ?: "", color = NyoraTokens.onSurfaceFaint, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { state.browseManga(source, 1) }) {
                        Text("Retry", color = LocalNyoraAccent.current.color)
                    }
                }
            }

        mangaList.isEmpty() ->
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (state.exploreMode == ExploreMode.SEARCH) "Search ${source.name} to see results"
                    else "No titles found in ${source.name}",
                    color = NyoraTokens.onSurfaceFaint,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

        else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                mangaList.firstOrNull()?.let { hero ->
                    item(span = { GridItemSpan(maxCurrentLineSpan) }) {
                        HeroBentoModule(hero, state)
                    }
                }
                items(mangaList.drop(1), key = { it.id }) { manga ->
                    MangaBentoCard(
                        manga = manga,
                        coverUrl = state.coverProxyUrl(manga.coverUrl),
                        onClick = { state.openDetails(manga, source) },
                    )
                }
                if (state.exploreHasNext) {
                    item(span = { GridItemSpan(maxCurrentLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { state.browseManga(source, state.explorePage + 1) }) {
                                if (state.exploreLoading) CircularProgressIndicator(Modifier.size(22.dp), color = LocalNyoraAccent.current.color)
                                else Text("Load more", color = LocalNyoraAccent.current.color)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Hero + cover cards ──────────────────────────────────────────────────────────────

@Composable
private fun HeroBentoModule(manga: Manga, state: AppState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .hoverLift(shape = RoundedCornerShape(24.dp), scaleTo = 1.01f)
            .glassCard(shape = RoundedCornerShape(24.dp), fill = NyoraTokens.surface1)
            .clickable { state.openDetails(manga, state.activeSource) },
    ) {
        AnimeAsyncImage(
            model = state.coverProxyUrl(manga.coverUrl).ifBlank { null },
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(24.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(NyoraTokens.bg.copy(alpha = 0.72f))
                .align(Alignment.BottomCenter),
        )
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(28.dp)) {
            SystemTag(text = "Featured")
            Spacer(Modifier.height(12.dp))
            Text(
                text = manga.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = NyoraTokens.onSurfaceHigh,
                letterSpacing = (-1).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MangaBentoCard(manga: Manga, coverUrl: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        AnimeAsyncImage(
            model = coverUrl.ifBlank { null },
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f).hoverLift(shape = RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = manga.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = NyoraTokens.onSurfaceHigh,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
