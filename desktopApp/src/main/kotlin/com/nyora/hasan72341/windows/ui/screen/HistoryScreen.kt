package com.nyora.windows.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.nyora.hasan72341.shared.repository.HistoryRow
import java.text.SimpleDateFormat
import java.util.*

// -------------------------------------------------------------------------------------
// Sealed model for the flat-grouped list items emitted into the LazyVerticalGrid.
// A Header spans all columns; a Card is a regular grid cell.
// -------------------------------------------------------------------------------------

private sealed interface HistoryListItem {
    data class Header(val label: String) : HistoryListItem
    data class Card(val row: HistoryRow) : HistoryListItem
}

// -------------------------------------------------------------------------------------
// Date-bucket helpers
// -------------------------------------------------------------------------------------

private enum class DateBucket(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    OLDER("Older"),
}

private fun dateBucketOf(epochMs: Long): DateBucket {
    val now = Calendar.getInstance()
    val item = Calendar.getInstance().also { it.timeInMillis = epochMs }

    // Strip to midnight for clean day-boundary comparisons.
    now.set(Calendar.HOUR_OF_DAY, 0)
    now.set(Calendar.MINUTE, 0)
    now.set(Calendar.SECOND, 0)
    now.set(Calendar.MILLISECOND, 0)

    val todayStart = now.timeInMillis
    val yesterdayStart = todayStart - 86_400_000L
    val weekStart = todayStart - 6 * 86_400_000L   // today + 6 previous days = 7-day window

    return when {
        item.timeInMillis >= todayStart   -> DateBucket.TODAY
        item.timeInMillis >= yesterdayStart -> DateBucket.YESTERDAY
        item.timeInMillis >= weekStart    -> DateBucket.THIS_WEEK
        else                              -> DateBucket.OLDER
    }
}

// -------------------------------------------------------------------------------------
// List preparation: sort + optionally group
// -------------------------------------------------------------------------------------

private fun buildListItems(
    history: List<HistoryRow>,
    sortOrder: String,
    groupByDate: Boolean,
): List<HistoryListItem> {
    val sorted = when (sortOrder) {
        "alpha" -> history.sortedBy { it.manga.title.lowercase() }
        "added" -> history                           // preserve DB / insertion order
        else    -> history.sortedByDescending { it.updatedAt }   // "recent" (default)
    }

    if (!groupByDate) {
        return sorted.map { HistoryListItem.Card(it) }
    }

    // Group under Today / Yesterday / This Week / Older in bucket order.
    val groups: Map<DateBucket, List<HistoryRow>> = sorted.groupBy { dateBucketOf(it.updatedAt) }

    return buildList {
        for (bucket in DateBucket.entries) {
            val rows = groups[bucket] ?: continue
            add(HistoryListItem.Header(bucket.label))
            rows.forEach { add(HistoryListItem.Card(it)) }
        }
    }
}

// -------------------------------------------------------------------------------------
// Screen
// -------------------------------------------------------------------------------------

@Composable
fun HistoryScreen(state: AppState) {
    if (state.history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No reading history yet.",
                color = NyoraTokens.onSurfaceMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    // Recompute whenever history list, sort order, or grouping flag changes.
    val listItems = remember(state.history, state.historySortOrder, state.groupHistoryByDate) {
        buildListItems(state.history, state.historySortOrder, state.groupHistoryByDate)
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        SectionHeader(
            title = "History",
            subtitle = "Continue reading",
            modifier = Modifier.padding(bottom = 32.dp),
        )

        val gridState = rememberLazyGridState()
        NyoraScrollContainer(
            adapter = rememberScrollbarAdapter(gridState),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(end = 10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = listItems,
                    // Headers span all columns; cards take one cell each.
                    span = { item ->
                        when (item) {
                            is HistoryListItem.Header -> GridItemSpan(maxLineSpan)
                            is HistoryListItem.Card   -> GridItemSpan(1)
                        }
                    },
                ) { item ->
                    when (item) {
                        is HistoryListItem.Header -> HistoryGroupHeader(item.label)
                        is HistoryListItem.Card   -> HistoryBentoCard(item.row, state)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// Group header row
// -------------------------------------------------------------------------------------

@Composable
private fun HistoryGroupHeader(label: String) {
    // Eyebrow treatment: all-caps SystemTag with a subtle hairline underline below.
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        SystemTag(
            text = label,
            color = NyoraTokens.onSurfaceMuted,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NyoraTokens.hairlineFaint),
        )
    }
}

// -------------------------------------------------------------------------------------
// Card — unchanged from original except the outer function is now private
// -------------------------------------------------------------------------------------

@Composable
private fun HistoryBentoCard(row: HistoryRow, state: AppState) {
    val cardShape = RoundedCornerShape(24.dp)
    val accent = LocalNyoraAccent.current.color

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .hoverLift(shape = cardShape, glowColor = accent)
            .glassCard(shape = cardShape, fill = NyoraTokens.surface1)
            .clickable { state.openDetails(row.manga) },
    ) {
        Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Cover thumbnail — AnimeAsyncImage with shimmer + glow-on-hover
            AnimeAsyncImage(
                model = state.coverProxyUrl(row.manga.coverUrl, state.sourceFor(row.manga)),
                contentDescription = row.manga.title,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(95.dp),
                shape = RoundedCornerShape(16.dp),
            )

            Spacer(Modifier.width(20.dp))

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Title
                Text(
                    text = row.manga.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = NyoraTokens.onSurfaceHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = (-0.5).sp,
                )

                Spacer(Modifier.height(4.dp))

                // Chapter label via SystemTag (ALL-CAPS micro accent)
                SystemTag(
                    text = row.chapterTitle,
                    color = accent,
                )

                Spacer(Modifier.weight(1f))

                // Timestamp row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = NyoraTokens.onSurfaceFaint,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = formatRelativeTime(row.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = NyoraTokens.onSurfaceFaint,
                        fontWeight = FontWeight.Medium,
                    )

                    Spacer(Modifier.width(12.dp))

                    // Progress percent tag
                    val pct = (row.percent * 100).toInt().coerceIn(0, 100)
                    SystemTag(
                        text = "$pct%",
                        color = NyoraTokens.onSurfaceMuted,
                    )
                }
            }

            // Glassmorphic Resume button
            Box(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(44.dp)
                    .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1)
                    .clickable { state.resumeManga(row.manga, null, row.chapterId) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Resume",
                    tint = NyoraTokens.onSurfaceHigh,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Glowing progress bar at the bottom of the card
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(row.percent.coerceIn(0.01f, 1f))
                .height(3.dp)
                .background(accent),
        )
    }
}

private fun formatRelativeTime(epochMs: Long): String {
    val diffMs = System.currentTimeMillis() - epochMs
    return when {
        diffMs < 60_000L           -> "Just now"
        diffMs < 3_600_000L       -> "${diffMs / 60_000}m ago"
        diffMs < 86_400_000L      -> "${diffMs / 3_600_000}h ago"
        diffMs < 7 * 86_400_000L  -> "${diffMs / 86_400_000}d ago"
        else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(epochMs))
    }
}
