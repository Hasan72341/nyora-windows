package com.nyora.windows.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nyora.windows.AppState
import com.nyora.windows.bridge.DownloadDto
import com.nyora.windows.bridge.DownloadSettingsDto
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraSmooth
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.hoverLift

// ---------------------------------------------------------------------------
// DownloadsScreen — download queue, settings panel, and bulk actions.
//
// Layout (top-to-bottom):
//   1. SectionHeader + refresh button
//   2. Download settings glassCard (max concurrent stepper + format dropdown)
//   3. Bulk-actions row (cancel all active / clear finished)
//   4. LazyColumn of styled DownloadRowCard items
// ---------------------------------------------------------------------------

private val FORMAT_OPTIONS = listOf("AUTO", "FOLDER", "CBZ", "ZIP")

@Composable
fun DownloadsScreen(state: AppState) {
    // Load both downloads list and download settings on first composition.
    LaunchedEffect(Unit) {
        state.loadDownloads()
        state.loadDownloadSettings()
    }

    val accent = LocalNyoraAccent.current.color

    // Local draft mirrors state.downloadSettings; re-seeded whenever the loaded
    // settings change so the user can edit without an immediate round-trip.
    var draft by remember(state.downloadSettings) {
        mutableStateOf(state.downloadSettings ?: DownloadSettingsDto())
    }

    // "Clear finished" is a UI-only filter — no server endpoint exists to bulk-
    // delete completed/failed entries. We hide them from the list locally.
    var hideFinished by remember { mutableStateOf(false) }

    val visibleDownloads = remember(state.downloads, hideFinished) {
        if (hideFinished) state.downloads.filter { it.status == "QUEUED" || it.status == "RUNNING" }
        else state.downloads
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {

        // ── Header ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = "Downloads",
                subtitle = "Queue & settings",
                modifier = Modifier.weight(1f),
            )

            // Refresh button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassCard(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1)
                    .clickable { state.loadDownloads() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh downloads",
                    tint = NyoraTokens.onSurfaceBody,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // ── Download settings glassCard ───────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(shape = RoundedCornerShape(20.dp), fill = NyoraTokens.surface1)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Eyebrow
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accent,
                )
                SystemTag(text = "Settings")
            }

            Spacer(Modifier.height(8.dp))

            // Max concurrent downloads — stepper row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = "Max concurrent downloads",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NyoraTokens.onSurfaceBody,
                    )
                    Text(
                        text = "Parallel chapters downloaded at once",
                        style = MaterialTheme.typography.bodySmall,
                        color = NyoraTokens.onSurfaceMuted,
                    )
                }

                ConcurrentStepper(
                    value = draft.maxConcurrentDownloads,
                    onDecrement = {
                        if (draft.maxConcurrentDownloads > 1) {
                            draft = draft.copy(maxConcurrentDownloads = draft.maxConcurrentDownloads - 1)
                            state.saveDownloadSettings(draft)
                        }
                    },
                    onIncrement = {
                        if (draft.maxConcurrentDownloads < 8) {
                            draft = draft.copy(maxConcurrentDownloads = draft.maxConcurrentDownloads + 1)
                            state.saveDownloadSettings(draft)
                        }
                    },
                    accent = accent,
                )
            }

            HorizontalDivider(
                color = NyoraTokens.hairlineFaint,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // Download format — dropdown row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = "Download format",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NyoraTokens.onSurfaceBody,
                    )
                    Text(
                        text = "AUTO lets the app decide the best format",
                        style = MaterialTheme.typography.bodySmall,
                        color = NyoraTokens.onSurfaceMuted,
                    )
                }

                DownloadFormatDropdown(
                    selected = draft.format,
                    onSelect = { chosen ->
                        draft = draft.copy(format = chosen)
                        state.saveDownloadSettings(draft)
                    },
                    accent = accent,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Bulk actions row ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Cancel all active (QUEUED + RUNNING)
            val activeDownloads = remember(state.downloads) {
                state.downloads.filter { it.status == "QUEUED" || it.status == "RUNNING" }
            }

            OutlinedButton(
                onClick = {
                    activeDownloads.forEach { state.cancelDownload(it.id) }
                },
                enabled = activeDownloads.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = NyoraTokens.crimson.copy(alpha = 0.08f),
                    contentColor = NyoraTokens.crimson,
                    disabledContainerColor = NyoraTokens.surface1,
                    disabledContentColor = NyoraTokens.onSurfaceFaint,
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (activeDownloads.isNotEmpty()) NyoraTokens.crimson.copy(alpha = 0.35f)
                            else NyoraTokens.hairlineFaint,
                ),
                modifier = Modifier.weight(1f).height(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Cancel all active",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Clear finished — UI-only; hides COMPLETED/FAILED entries from the list
            val finishedDownloads = remember(state.downloads) {
                state.downloads.filter { it.status != "QUEUED" && it.status != "RUNNING" }
            }

            OutlinedButton(
                onClick = { hideFinished = true },
                enabled = finishedDownloads.isNotEmpty() && !hideFinished,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = NyoraTokens.surface1,
                    contentColor = NyoraTokens.onSurfaceBody,
                    disabledContainerColor = NyoraTokens.surface1,
                    disabledContentColor = NyoraTokens.onSurfaceFaint,
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (finishedDownloads.isNotEmpty() && !hideFinished) NyoraTokens.hairlineStrong
                            else NyoraTokens.hairlineFaint,
                ),
                modifier = Modifier.weight(1f).height(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DownloadDone,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (hideFinished) "Finished hidden" else "Clear finished",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // ── Download list ─────────────────────────────────────────────────────
        if (visibleDownloads.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = null,
                        tint = NyoraTokens.onSurfaceFaint,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = if (hideFinished) "All finished downloads hidden." else "No active downloads.",
                        color = NyoraTokens.onSurfaceMuted,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (hideFinished) {
                        TextButton(onClick = { hideFinished = false }) {
                            Text(
                                text = "Show all",
                                color = accent,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(visibleDownloads, key = { it.id }) { entry ->
                    DownloadRowCard(
                        entry = entry,
                        accent = accent,
                        onCancel = { state.cancelDownload(entry.id) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Download row — glassCard with semantic status chip + accent progress bar
// ---------------------------------------------------------------------------

@Composable
private fun DownloadRowCard(
    entry: DownloadDto,
    accent: Color,
    onCancel: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverLift(shape = shape, glowColor = statusGlowColor(entry.status, accent))
            .glassCard(shape = shape, fill = NyoraTokens.surface1)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Status icon column
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(statusIconBackground(entry.status, accent)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = statusIcon(entry.status),
                contentDescription = null,
                tint = statusIconTint(entry.status, accent),
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.width(14.dp))

        // Main content column
        Column(modifier = Modifier.weight(1f)) {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = entry.mangaTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = NyoraTokens.onSurfaceHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )

                // Semantic status chip via SystemTag
                StatusChip(status = entry.status, accent = accent)
            }

            Spacer(Modifier.height(2.dp))

            Text(
                text = entry.chapterTitle,
                style = MaterialTheme.typography.bodySmall,
                color = NyoraTokens.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(12.dp))

            // Progress bar (visible for QUEUED + RUNNING; completed shows filled bar)
            when (entry.status) {
                "RUNNING", "QUEUED" -> {
                    val progress = if (entry.totalPages > 0)
                        entry.completedPages.toFloat() / entry.totalPages
                    else 0f

                    val animatedProgress by animateFloatAsState(
                        targetValue = progress,
                        animationSpec = NyoraSmooth,
                        label = "downloadProgress_${entry.id}",
                    )

                    DownloadProgressBar(
                        progress = animatedProgress,
                        accent = accent,
                    )

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (entry.status == "RUNNING")
                                "${entry.completedPages} / ${entry.totalPages} pages"
                            else "Queued…",
                            style = MaterialTheme.typography.labelSmall,
                            color = NyoraTokens.onSurfaceFaint,
                        )

                        if (entry.status == "RUNNING" && entry.totalPages > 0) {
                            val pct = ((entry.completedPages.toFloat() / entry.totalPages) * 100).toInt()
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = accent,
                            )
                        }
                    }
                }

                "COMPLETED" -> {
                    // Show a full, dimmed progress bar so the layout height is consistent
                    DownloadProgressBar(progress = 1f, accent = NyoraTokens.mint)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Completed · ${entry.completedPages} pages",
                        style = MaterialTheme.typography.labelSmall,
                        color = NyoraTokens.onSurfaceFaint,
                    )
                }

                "FAILED" -> {
                    Text(
                        text = entry.error ?: "Download failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = NyoraTokens.crimson,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Cancel button — only for active entries
        if (entry.status == "QUEUED" || entry.status == "RUNNING") {
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NyoraTokens.crimson.copy(alpha = 0.10f))
                    .clickable { onCancel() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = "Cancel download",
                    tint = NyoraTokens.crimson,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Progress bar — flat solid accent fill
// ---------------------------------------------------------------------------

@Composable
private fun DownloadProgressBar(
    progress: Float,
    accent: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(50)),
    ) {
        // Track background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NyoraTokens.surface1),
        )
        // Filled portion — flat solid accent, no gradient
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(accent),
        )
    }
}

// ---------------------------------------------------------------------------
// Status chip — SystemTag with semantic color
// ---------------------------------------------------------------------------

@Composable
private fun StatusChip(status: String, accent: Color) {
    val (label, color) = when (status) {
        "RUNNING"   -> "Running"  to accent
        "COMPLETED" -> "Done"     to NyoraTokens.mint
        "FAILED"    -> "Failed"   to NyoraTokens.crimson
        "QUEUED"    -> "Queued"   to NyoraTokens.onSurfaceMuted
        else        -> status     to NyoraTokens.onSurfaceFaint
    }
    SystemTag(text = label, color = color)
}

// ---------------------------------------------------------------------------
// Status helpers (icon / tints)
// ---------------------------------------------------------------------------

@Composable
private fun statusIcon(status: String) = when (status) {
    "RUNNING"   -> Icons.Rounded.PlayCircle
    "QUEUED"    -> Icons.Rounded.HourglassTop
    "COMPLETED" -> Icons.Rounded.DownloadDone
    "FAILED"    -> Icons.Rounded.Warning
    else        -> Icons.Default.Download
}

private fun statusGlowColor(status: String, accent: Color): Color = when (status) {
    "RUNNING"   -> accent
    "COMPLETED" -> NyoraTokens.mint
    "FAILED"    -> NyoraTokens.crimson
    else        -> Color.Transparent
}

private fun statusIconBackground(status: String, accent: Color): Color = when (status) {
    "RUNNING"   -> accent.copy(alpha = 0.12f)
    "COMPLETED" -> NyoraTokens.mint.copy(alpha = 0.12f)
    "FAILED"    -> NyoraTokens.crimson.copy(alpha = 0.12f)
    "QUEUED"    -> NyoraTokens.surface1
    else        -> NyoraTokens.surface1
}

private fun statusIconTint(status: String, accent: Color): Color = when (status) {
    "RUNNING"   -> accent
    "COMPLETED" -> NyoraTokens.mint
    "FAILED"    -> NyoraTokens.crimson
    "QUEUED"    -> NyoraTokens.onSurfaceMuted
    else        -> NyoraTokens.onSurfaceFaint
}

// ---------------------------------------------------------------------------
// Concurrent stepper — −/+ control
// ---------------------------------------------------------------------------

@Composable
private fun ConcurrentStepper(
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    accent: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NyoraTokens.surface1),
    ) {
        // Decrement
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(enabled = value > 1, onClick = onDecrement),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Decrease",
                tint = if (value > 1) NyoraTokens.onSurfaceBody else NyoraTokens.onSurfaceFaint,
                modifier = Modifier.size(16.dp),
            )
        }

        // Value label
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black,
            color = accent,
            modifier = Modifier.widthIn(min = 32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // Increment
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(enabled = value < 8, onClick = onIncrement),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Increase",
                tint = if (value < 8) NyoraTokens.onSurfaceBody else NyoraTokens.onSurfaceFaint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Format dropdown — aligned with NetworkSettingsScreen's NyoraDropdown style
// ---------------------------------------------------------------------------

@Composable
private fun DownloadFormatDropdown(
    selected: String,
    onSelect: (String) -> Unit,
    accent: Color,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = NyoraTokens.surface1,
                contentColor = NyoraTokens.onSurfaceBody,
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = if (expanded) accent.copy(alpha = 0.45f) else NyoraTokens.hairlineFaint,
            ),
            modifier = Modifier.width(170.dp),
        ) {
            Text(
                text = selected,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = NyoraTokens.onSurfaceBody,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = NyoraTokens.onSurfaceMuted,
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            FORMAT_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (option == selected) accent else NyoraTokens.onSurfaceBody,
                            fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
