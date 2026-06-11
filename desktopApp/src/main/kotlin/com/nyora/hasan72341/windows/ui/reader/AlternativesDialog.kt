package com.nyora.windows.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nyora.windows.AppState
import com.nyora.windows.ui.theme.AnimeAsyncImage
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.glassOverlay
import com.nyora.windows.ui.theme.hoverLift
import com.nyora.windows.ui.theme.shimmerPlaceholder
import com.nyora.hasan72341.shared.model.Manga

/**
 * "Find on other sources" — a frosted glass dialog that searches every installed source
 * for the same [title] and lists each hit as a tappable glass row. Opened from
 * [com.nyora.windows.ui.screen.DetailsScreen] (W10).
 *
 * On show it kicks [AppState.loadAlternatives]; the list is driven by
 * [AppState.alternatives] / [AppState.alternativesLoading]. Tapping a row opens that
 * manga's details (on its own source) and dismisses the dialog.
 */
@Composable
fun AlternativesDialog(
    state: AppState,
    title: String,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(title) { state.loadAlternatives(title) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .padding(24.dp)
                .glassCard(shape = RoundedCornerShape(28.dp), fill = NyoraTokens.surface1)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionHeader(title = "Find on other sources", subtitle = title)

            when {
                state.alternativesLoading -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(List(5) { it }, key = { "shimmer-$it" }) { AlternativeShimmerRow() }
                    }
                }

                state.alternatives.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .glassOverlay(
                                shape = RoundedCornerShape(20.dp),
                                fill = NyoraTokens.surface1,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No alternatives found on installed sources",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NyoraTokens.onSurfaceMuted,
                        )
                    }
                }

                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.alternatives, key = { it.id + it.source.name }) { alt ->
                            AlternativeRow(
                                manga = alt,
                                coverUrl = state.coverProxyUrl(alt.coverUrl),
                                onClick = {
                                    state.openDetails(alt)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlternativeRow(
    manga: Manga,
    coverUrl: String,
    onClick: () -> Unit,
) {
    val rowShape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverLift(shape = rowShape, scaleTo = 1.015f)
            .glassCard(shape = rowShape, fill = NyoraTokens.surface1)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimeAsyncImage(
            model = coverUrl,
            contentDescription = manga.title,
            modifier = Modifier.size(width = 46.dp, height = 64.dp),
            shape = RoundedCornerShape(12.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = manga.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = NyoraTokens.onSurfaceHigh,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SystemTag(text = manga.source.name)
        }
    }
}

@Composable
private fun AlternativeShimmerRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(shape = RoundedCornerShape(18.dp), fill = NyoraTokens.surface1)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(width = 46.dp, height = 64.dp)
                .shimmerPlaceholder(RoundedCornerShape(12.dp)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .shimmerPlaceholder(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .width(80.dp)
                    .height(10.dp)
                    .shimmerPlaceholder(RoundedCornerShape(5.dp)),
            )
        }
    }
}
