package com.nyora.windows.ui.screen

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.bridge.StatsResponse
import com.nyora.windows.bridge.TopSourceDto
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraScrollContainer
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.hoverLift
import com.nyora.windows.ui.theme.shimmerPlaceholder

/**
 * Statistics dashboard — "Midnight Sakura".
 *
 * A cinematic hero band (aurora + hero gradients) anchors a glowing longest-streak ring,
 * over a bento of animated counter cards and a Top Sources leaderboard with accent-glowed
 * progress bars. The 88dp sidebar clearance is provided by the caller (App.kt); this fills
 * its area with a 32dp outer rhythm.
 */
@Composable
fun StatsScreen(state: AppState) {
    LaunchedEffect(Unit) { state.loadStats() }

    val accent = LocalNyoraAccent.current.color
    val stats = state.stats
    val scrollState = rememberScrollState()

    NyoraScrollContainer(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            // ---- Hero band -------------------------------------------------------------
            StatsHero(stats = stats, loading = state.statsLoading)

            when {
                state.statsLoading && stats == null -> StatsLoadingState()
                stats == null -> StatsEmptyState()
                else -> {
                    StatCardsBento(stats)
                    TopSourcesSection(stats.topSources, accent)
                }
            }
        }
    }
}

// =====================================================================================
// Hero
// =====================================================================================

@Composable
private fun StatsHero(stats: StatsResponse?, loading: Boolean) {
    val accent = LocalNyoraAccent.current.color
    val streak = stats?.longestStreakDays ?: 0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .glassCard(shape = RoundedCornerShape(28.dp), fill = NyoraTokens.surface1),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionHeader(title = "Statistics", subtitle = "Your reading life")
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (loading && stats == null) "Crunching your numbers…"
                    else "A quiet record of every page you've turned.",
                    color = NyoraTokens.onSurfaceMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.width(24.dp))

            StreakRing(days = streak, accent = accent)
        }
    }
}

/** Glowing arc ring rendering the longest streak with a soft accent glow core. */
@Composable
private fun StreakRing(days: Int, accent: Color) {
    val target = days.coerceAtMost(30) / 30f
    val sweepFraction by animateFloatAsState(
        targetValue = if (days <= 0) 0.02f else target.coerceIn(0.04f, 1f),
        animationSpec = tween(durationMillis = 1100, easing = EaseOutCubic),
        label = "streakSweep",
    )
    val animatedDays by animateIntAsState(
        targetValue = days,
        animationSpec = tween(durationMillis = 1100, easing = EaseOutCubic),
        label = "streakDays",
    )
    Box(
        modifier = Modifier.size(150.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Flat accent tint behind the ring.
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(accent.copy(alpha = 0.12f)),
        )

        Canvas(modifier = Modifier.size(132.dp)) {
            val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            val arcSize = androidx.compose.ui.geometry.Size(
                width = size.width - stroke.width,
                height = size.height - stroke.width,
            )
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)

            // Track.
            drawArc(
                color = NyoraTokens.glass3,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            // Accent progress sweep — solid accent arc.
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * sweepFraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$animatedDays",
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
                color = NyoraTokens.onSurfaceHigh,
            )
            SystemTag(text = "Day Streak")
        }
    }
}

// =====================================================================================
// Stat cards bento
// =====================================================================================

@Composable
private fun StatCardsBento(stats: StatsResponse) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatCard(
                modifier = Modifier.weight(1f),
                value = stats.totalChapters,
                label = "Chapters Read",
                icon = Icons.Rounded.MenuBook,
            )
            StatCard(
                modifier = Modifier.weight(1f),
                value = stats.distinctManga,
                label = "Manga",
                icon = Icons.Rounded.AutoStories,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatCard(
                modifier = Modifier.weight(1f),
                value = stats.favouritesCount,
                label = "Favourites",
                icon = Icons.Rounded.Favorite,
            )
            StatCard(
                modifier = Modifier.weight(1f),
                value = stats.longestStreakDays,
                label = "Streak",
                suffix = "days",
                icon = Icons.Rounded.LocalFireDepartment,
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    value: Int,
    label: String,
    icon: ImageVector,
    suffix: String? = null,
) {
    val accent = LocalNyoraAccent.current.color
    val animated by animateIntAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 1000, easing = EaseOutCubic),
        label = "counter_$label",
    )
    val shape = RoundedCornerShape(24.dp)

    Column(
        modifier = modifier
            .hoverLift(shape = shape)
            .glassCard(shape = shape, fill = NyoraTokens.surface1)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$animated",
                fontSize = 52.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-2).sp,
                color = NyoraTokens.onSurfaceHigh,
            )
            if (suffix != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = suffix,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = NyoraTokens.onSurfaceMuted,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }

        SystemTag(text = label)
    }
}

// =====================================================================================
// Top sources
// =====================================================================================

@Composable
private fun TopSourcesSection(sources: List<TopSourceDto>, accent: Color) {
    if (sources.isEmpty()) return

    val maxCount = sources.maxOf { it.count }.coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeader(title = "Top Sources", subtitle = "Where you read most")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(shape = RoundedCornerShape(24.dp), fill = NyoraTokens.surface1)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            sources.forEach { source ->
                TopSourceRow(source = source, maxCount = maxCount, accent = accent)
            }
        }
    }
}

@Composable
private fun TopSourceRow(source: TopSourceDto, maxCount: Int, accent: Color) {
    val fractionTarget = (source.count.toFloat() / maxCount).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(
        targetValue = fractionTarget,
        animationSpec = tween(durationMillis = 900, easing = EaseOutCubic),
        label = "srcBar_${source.sourceId}",
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = source.sourceName.ifBlank { source.sourceId.ifBlank { "Unknown" } },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NyoraTokens.onSurfaceBody,
                modifier = Modifier.weight(1f),
            )
            SystemTag(text = "${source.count}")
        }

        // Progress bar.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(NyoraTokens.surface1),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent),
            )
        }
    }
}

// =====================================================================================
// Loading / empty states
// =====================================================================================

@Composable
private fun StatsLoadingState() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.6f)
                            .shimmerPlaceholder(RoundedCornerShape(24.dp)),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .shimmerPlaceholder(RoundedCornerShape(24.dp)),
        )
    }
}

@Composable
private fun StatsEmptyState() {
    val accent = LocalNyoraAccent.current.color
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .glassCard(shape = RoundedCornerShape(24.dp), fill = NyoraTokens.surface1),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(accent.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.QueryStats,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(34.dp),
                )
            }
            Text(
                text = "No statistics yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = NyoraTokens.onSurfaceHigh,
            )
            Text(
                text = "Start reading and your stats will gather here.",
                color = NyoraTokens.onSurfaceMuted,
                fontSize = 14.sp,
            )
        }
    }
}
