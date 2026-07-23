package com.nyora.windows.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.bridge.GlobalSearchGroup
import com.nyora.windows.ui.theme.AnimeAsyncImage
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraScrollContainer
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.glassOverlay
import com.nyora.windows.ui.theme.hoverLift
import com.nyora.hasan72341.shared.model.Manga

@Composable
fun GlobalSearchScreen(state: AppState) {
    val accent = LocalNyoraAccent.current.color

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NyoraTokens.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header area: back + SectionHeader + glass search field ──────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(top = 40.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                // Back row + title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .hoverLift(shape = RoundedCornerShape(12.dp), scaleTo = 1.06f)
                            .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1)
                            .clickable { state.showGlobalSearch = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = NyoraTokens.onSurfaceBody,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    SectionHeader(
                        title = "Global Search",
                        subtitle = "All Sources",
                    )
                }

                // Glass search pill — centered, max 640dp wide.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .fillMaxWidth()
                            .height(56.dp)
                            .glassCard(shape = RoundedCornerShape(28.dp), fill = NyoraTokens.surface1),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            BasicTextField(
                                value = state.globalQuery,
                                onValueChange = { state.globalQuery = it },
                                modifier = Modifier.weight(1f).onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                        state.globalSearch(state.globalQuery); true
                                    } else false
                                },
                                textStyle = LocalTextStyle.current.copy(
                                    color = NyoraTokens.onSurfaceHigh,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                cursorBrush = SolidColor(accent),
                                decorationBox = { innerTextField ->
                                    if (state.globalQuery.isEmpty()) {
                                        Text(
                                            "Search across all sources...",
                                            color = NyoraTokens.onSurfaceFaint,
                                            fontSize = 16.sp,
                                        )
                                    }
                                    innerTextField()
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .height(32.dp)
                                    .widthIn(min = 72.dp)
                                    .hoverLift(shape = RoundedCornerShape(16.dp), scaleTo = 1.04f)
                                    .background(accent, RoundedCornerShape(16.dp))
                                    .clickable { state.globalSearch(state.globalQuery) }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Search",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.4.sp,
                                )
                            }
                        }
                    }
                }
            }

            // ── Results area ──────────────────────────────────────────────────────────────
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.globalSearching -> GlobalSearchingIndicator(accent)

                    state.globalSearchError != null -> GlobalSearchErrorState(state.globalSearchError!!)

                    state.globalResults.isEmpty() && state.globalQuery.isNotBlank() ->
                        GlobalEmptyState(query = state.globalQuery)

                    state.globalResults.isEmpty() ->
                        GlobalIdleState()

                    else -> {
                        val listState = rememberLazyListState()
                        NyoraScrollContainer(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(32.dp),
                            contentPadding = PaddingValues(
                                start = 32.dp,
                                end = 32.dp,
                                top = 4.dp,
                                bottom = 72.dp,
                            ),
                        ) {
                            items(state.globalResults, key = { it.sourceId }) { group ->
                                GlobalSearchSourceGroup(
                                    group = group,
                                    onMangaClick = { manga ->
                                        val src = state.sources.firstOrNull { it.id == group.sourceId }
                                        state.openDetails(manga, src)
                                        state.showGlobalSearch = false
                                    },
                                    coverUrlFor = { manga -> state.coverProxyUrl(manga.coverUrl) },
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}

// ── Searching progress indicator ─────────────────────────────────────────────────────────

@Composable
private fun GlobalSearchingIndicator(accent: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CircularProgressIndicator(
                color = accent,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(40.dp),
            )
            SystemTag(text = "Querying Sources", color = NyoraTokens.onSurfaceMuted)
        }
    }
}

// ── Error state ───────────────────────────────────────────────────────────────────────────

@Composable
private fun GlobalSearchErrorState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .glassCard(shape = RoundedCornerShape(20.dp), fill = NyoraTokens.surface1)
                .padding(32.dp),
        ) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                modifier = Modifier.size(36.dp),
            )
            SystemTag(text = "Search Failed", color = MaterialTheme.colorScheme.error)
            Text(
                text = message,
                color = NyoraTokens.onSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ── Empty result state ────────────────────────────────────────────────────────────────────

@Composable
private fun GlobalEmptyState(query: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "No results",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                color = NyoraTokens.onSurfaceFaint,
            )
            Text(
                text = "Nothing matched \"$query\" across any source",
                color = NyoraTokens.onSurfaceFaint.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ── Idle / pre-search state ────────────────────────────────────────────────────────────────

@Composable
private fun GlobalIdleState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Search Everything",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                color = NyoraTokens.onSurfaceFaint,
            )
            Text(
                text = "Type a title and hit Enter to search all sources at once",
                color = NyoraTokens.onSurfaceFaint.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ── Per-source group ──────────────────────────────────────────────────────────────────────

@Composable
private fun GlobalSearchSourceGroup(
    group: GlobalSearchGroup,
    onMangaClick: (Manga) -> Unit,
    coverUrlFor: (Manga) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // Source header row: SystemTag label on the left, match count or error pill on the right.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SystemTag(text = group.sourceName)

            if (group.error != null) {
                // Tasteful error inline chip.
                Row(
                    modifier = Modifier
                        .glassOverlay(
                            shape = RoundedCornerShape(50),
                            fill = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.70f),
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        text = "Source error",
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.80f),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                    )
                }
            } else if (group.entries.isNotEmpty()) {
                Text(
                    text = "${group.entries.size} result${if (group.entries.size != 1) "s" else ""}",
                    color = NyoraTokens.onSurfaceFaint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (group.entries.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(group.entries, key = { it.id }) { manga ->
                    GlobalSearchCoverCard(
                        manga = manga,
                        coverUrl = coverUrlFor(manga),
                        onClick = { onMangaClick(manga) },
                    )
                }
            }
        } else if (group.error == null) {
            // No results, no error — show a muted placeholder.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "No results from this source",
                    color = NyoraTokens.onSurfaceFaint,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ── Cover card ────────────────────────────────────────────────────────────────────────────

@Composable
private fun GlobalSearchCoverCard(manga: Manga, coverUrl: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable { onClick() },
    ) {
        AnimeAsyncImage(
            model = coverUrl.ifBlank { null },
            contentDescription = manga.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .hoverLift(shape = RoundedCornerShape(18.dp), scaleTo = 1.05f),
            shape = RoundedCornerShape(18.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = manga.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = NyoraTokens.onSurfaceBody,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
