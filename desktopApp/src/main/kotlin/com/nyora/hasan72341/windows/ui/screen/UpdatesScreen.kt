package com.nyora.windows.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.ui.theme.AnimeAsyncImage
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraScrollContainer
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.glassOverlay
import com.nyora.windows.ui.theme.hoverLift
import com.nyora.hasan72341.shared.repository.UpdateRow

// ── Screen ────────────────────────────────────────────────────────────────────────────────

@Composable
fun UpdatesScreen(state: AppState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        // Header row: SectionHeader + action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = "Updates",
                subtitle = "History & favourites",
                modifier = Modifier.weight(1f),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Refresh icon button
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .hoverLift(shape = RoundedCornerShape(14.dp), scaleTo = 1.06f)
                        .glassOverlay(shape = RoundedCornerShape(14.dp), fill = NyoraTokens.surface1)
                        .clickable { state.refreshUpdates() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh updates",
                        tint = NyoraTokens.onSurfaceBody,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Mark All Seen button — visible only when there are unseen updates
                if (state.updates.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .hoverLift(shape = RoundedCornerShape(14.dp), scaleTo = 1.04f)
                            .glassOverlay(shape = RoundedCornerShape(14.dp), fill = NyoraTokens.surface1)
                            .clickable { state.markAllUpdatesSeen() }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DoneAll,
                                contentDescription = null,
                                tint = LocalNyoraAccent.current.color,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Mark all seen",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = NyoraTokens.onSurfaceBody,
                            )
                        }
                    }
                }
            }
        }

        // Info note: scope now covers history + favourites
        Text(
            text = "Showing new chapters across your history and favourites.",
            style = MaterialTheme.typography.labelMedium,
            color = NyoraTokens.onSurfaceFaint,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // Body
        if (state.updates.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SystemTag(text = "All caught up", color = NyoraTokens.onSurfaceMuted)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "You're up to date — no new chapters.",
                        color = NyoraTokens.onSurfaceFaint,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        } else {
            val listState = rememberLazyListState()
            NyoraScrollContainer(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp, end = 10.dp),
                ) {
                    items(state.updates, key = { it.mangaId }) { row ->
                        UpdateBentoCard(row = row, state = state)
                    }
                }
            }
        }
    }
}

// ── Card ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun UpdateBentoCard(row: UpdateRow, state: AppState) {
    val accent = LocalNyoraAccent.current.color
    val cardShape = RoundedCornerShape(20.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverLift(shape = cardShape, scaleTo = 1.02f, glowColor = accent)
            .glassCard(shape = cardShape, fill = NyoraTokens.surface1)
            .clickable {
                val manga = state.mangaById(row.mangaId)
                if (manga != null) state.openDetails(manga)
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cover thumbnail
            AnimeAsyncImage(
                model = state.coverProxyUrl(row.mangaCoverUrl, null).ifBlank { null },
                contentDescription = row.mangaTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .height(88.dp)
                    .width(62.dp),
                shape = RoundedCornerShape(14.dp),
            )

            Spacer(Modifier.width(16.dp))

            // Text block
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = row.mangaTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = NyoraTokens.onSurfaceHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = (-0.3).sp,
                )

                // New-chapter count badge via SystemTag (accent-tinted ALL-CAPS micro-label)
                SystemTag(
                    text = "${row.newChapters} new chapter${if (row.newChapters != 1) "s" else ""}",
                    color = accent,
                )

                if (row.latestChapterTitle.isNotBlank()) {
                    Text(
                        text = "Latest: ${row.latestChapterTitle}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NyoraTokens.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Read / Resume button
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .hoverLift(shape = RoundedCornerShape(12.dp), scaleTo = 1.06f)
                        .background(
                            color = accent,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable {
                            val manga = state.mangaById(row.mangaId)
                            if (manga != null) state.resumeManga(manga)
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "Read",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                        )
                    }
                }

                // Mark seen icon button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .hoverLift(shape = RoundedCornerShape(12.dp), scaleTo = 1.06f)
                        .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1)
                        .clickable { state.markUpdateSeen(row.mangaId) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Mark seen",
                        tint = NyoraTokens.onSurfaceMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Subtle accent glow strip at bottom-left (mirrors HistoryScreen progress bar idiom)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(
                    (row.newChapters.toFloat() / row.totalChapters.coerceAtLeast(1))
                        .coerceIn(0.04f, 1f),
                )
                .height(2.dp)
                .background(accent.copy(alpha = 0.55f)),
        )
    }
}
