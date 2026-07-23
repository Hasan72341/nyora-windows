package com.nyora.windows.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nyora.windows.AppState
import com.nyora.windows.bridge.AniListFeedMedia
import com.nyora.windows.ui.theme.AnimeAsyncImage
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraScrollContainer
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.hoverLift
import com.nyora.windows.ui.theme.shimmerPlaceholder

private val CardShape = RoundedCornerShape(22.dp)

/**
 * "For You" — a discovery feed powered by AniList's trending manga.
 *
 * Loads [AppState.anilistFeed] (AniList's public trending query) and renders the entries as an
 * asymmetric bento grid of cover cards in the Midnight Sakura language: every cover is an
 * [AnimeAsyncImage] with [hoverLift], a bottom noir gradient mask carrying the title, and a
 * [SystemTag] row showing the AniList score and the lead genre. Tapping a card kicks off a
 * "smart match": it runs a [AppState.globalSearch] for the title and opens the global-search
 * overlay so the user can find that title on whichever sources they have installed. Loading
 * shows a shimmer grid; an empty result lands on a tasteful empty state.
 */
@Composable
fun SuggestionsScreen(state: AppState) {
    LaunchedEffect(Unit) { state.loadAnilistFeed() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {

        // Flat dark header band for the "For You" header.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(NyoraTokens.bg)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            SectionHeader(
                title = "Discover",
                subtitle = "Popular on MangaBaka",
                modifier = Modifier.padding(bottom = 0.dp),
            )
        }

        Spacer(Modifier.height(32.dp))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                // Loading: a shimmer bento so the layout never collapses.
                state.anilistFeedLoading && state.anilistFeed.isEmpty() -> {
                    ShimmerSuggestionGrid()
                }

                // Empty after load: friendly nudge that something went sideways.
                state.anilistFeed.isEmpty() -> {
                    EmptySuggestions()
                }

                else -> {
                    SuggestionsBento(state = state, items = state.anilistFeed)
                }
            }
        }
    }
}

@Composable
private fun SuggestionsBento(state: AppState, items: List<AniListFeedMedia>) {
    val gridState = rememberLazyGridState()
    NyoraScrollContainer(
        adapter = rememberScrollbarAdapter(gridState),
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 168.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(end = 10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items.forEachIndexed { index, media ->
            // "Smart match": resolve the AniList title against the user's installed sources
            // by routing through the global-search overlay.
            val onClick: () -> Unit = {
                state.globalSearch(state.anilistFeedTitle(media))
                state.showGlobalSearch = true
            }
            // Asymmetric rhythm: a tall hero card leads every block of ~7 entries,
            // spanning the full row so the grid breathes instead of marching uniformly.
            val isHero = index % 7 == 0
            if (isHero) {
                item(
                    key = "hero-${media.id}-$index",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    SuggestionCard(
                        media = media,
                        title = state.anilistFeedTitle(media),
                        heavy = true,
                        onClick = onClick,
                    )
                }
            } else {
                item(key = "card-${media.id}-$index") {
                    SuggestionCard(
                        media = media,
                        title = state.anilistFeedTitle(media),
                        heavy = false,
                        onClick = onClick,
                    )
                }
            }
            }
        }
    }
}

/**
 * A cover card with a bottom noir mask + title overlay + a score/genre tag row.
 *
 * [heavy] = true renders the full-row cinematic hero variant; false renders a standard portrait.
 */
@Composable
private fun SuggestionCard(
    media: AniListFeedMedia,
    title: String,
    heavy: Boolean,
    onClick: () -> Unit,
) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .let { if (heavy) it.height(320.dp) else it.aspectRatio(0.7f) }
        .hoverLift(shape = CardShape)
        .clip(CardShape)
        .clickable { onClick() }

    Box(modifier = cardModifier) {
        AnimeAsyncImage(
            model = media.coverImage.extraLarge ?: media.coverImage.large,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            shape = CardShape,
            modifier = Modifier.fillMaxSize(),
        )
        CoverCaption(
            title = title,
            score = media.averageScore,
            genre = media.genres.firstOrNull(),
            heavy = heavy,
        )
    }
}

/** The shared bottom-anchored caption: flat semi-opaque scrim, score/genre tag row, title. */
@Composable
private fun BoxScope.CoverCaption(
    title: String,
    score: Int?,
    genre: String?,
    heavy: Boolean,
) {
    // Flat bottom scrim so text stays legible over any cover art.
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .fillMaxHeight(0.42f)
            .background(NyoraTokens.bg.copy(alpha = 0.72f)),
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(if (heavy) 20.dp else 14.dp),
    ) {
        // Score + genre micro-tag row. Falls back to a generic eyebrow when both are absent.
        Row(verticalAlignment = Alignment.CenterVertically) {
            var emitted = false
            if (score != null) {
                SystemTag(text = "$score%")
                emitted = true
            }
            if (!genre.isNullOrBlank()) {
                if (emitted) Spacer(Modifier.width(10.dp))
                SystemTag(text = genre, color = NyoraTokens.onSurfaceMuted)
                emitted = true
            }
            if (!emitted) {
                SystemTag(text = "Trending")
            }
        }
        Spacer(Modifier.height(if (heavy) 8.dp else 5.dp))
        Text(
            text = title,
            style = if (heavy) MaterialTheme.typography.headlineSmall
            else MaterialTheme.typography.titleSmall,
            fontWeight = if (heavy) FontWeight.Black else FontWeight.Bold,
            color = NyoraTokens.onSurfaceHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shimmer placeholder grid shown while the AniList feed is loading. */
@Composable
private fun ShimmerSuggestionGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .shimmerPlaceholder(CardShape),
            )
        }
        items(List(10) { it }, key = { "shimmer-$it" }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .shimmerPlaceholder(CardShape),
            )
        }
    }
}

/** Tasteful empty state shown when the AniList feed returns nothing. */
@Composable
private fun EmptySuggestions() {
    val accent = LocalNyoraAccent.current.color
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            // Flat accent medallion echoing the "For You" theme.
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(color = accent.copy(alpha = 0.18f), shape = RoundedCornerShape(48.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .glassCard(shape = RoundedCornerShape(36.dp), fill = NyoraTokens.surface1),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.TrendingUp,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "No trending titles right now",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = NyoraTokens.onSurfaceHigh,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.TrendingUp,
                    contentDescription = null,
                    tint = NyoraTokens.onSurfaceMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "We couldn't reach MangaBaka just now — check your connection and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NyoraTokens.onSurfaceMuted,
                )
            }
        }
    }
}
