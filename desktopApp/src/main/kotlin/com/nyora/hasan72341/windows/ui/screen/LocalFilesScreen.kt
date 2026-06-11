package com.nyora.windows.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nyora.windows.AppState
import com.nyora.windows.bridge.LocalCbzEntry
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.hoverLift
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser

// ---------------------------------------------------------------------------
// LocalFilesScreen — browse and open local CBZ / archive files.
//
// Layout (top-to-bottom):
//   1. SectionHeader("Local Files", "CBZ & archives") + accent "Choose Folder" button
//   2. Active folder path hint (glass pill)
//   3. LazyColumn of glassCard file rows (icon + name + SystemTag size)
//   4. Polished empty state when no archives are found
// ---------------------------------------------------------------------------

@Composable
fun LocalFilesScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    val accent = LocalNyoraAccent.current.color

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {

        // ── Header ─────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = "Local Files",
                subtitle = "CBZ & archives",
                modifier = Modifier.weight(1f),
            )

            // Accent-styled "Choose Folder" button — launches Swing directory picker.
            Button(
                onClick = {
                    scope.launch {
                        val dir = withContext(Dispatchers.IO) { pickDirectory() }
                        if (dir != null) state.scanLocalFolder(dir)
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent.copy(alpha = 0.14f),
                    contentColor = accent,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Choose Folder",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // ── Active folder hint ────────────────────────────────────────────────
        state.localFolder?.let { folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NyoraTokens.surface1)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.70f),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = folder,
                    style = MaterialTheme.typography.labelSmall,
                    color = NyoraTokens.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ── File list / empty state ───────────────────────────────────────────
        if (state.localFiles.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LocalFilesEmptyState(accent = accent)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(state.localFiles, key = { it.path }) { entry ->
                    LocalFileRow(
                        entry = entry,
                        accent = accent,
                        onClick = { state.openLocalCbz(entry.path) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// File row — glassCard with book icon, file name, and SystemTag size chip
// ---------------------------------------------------------------------------

@Composable
private fun LocalFileRow(
    entry: LocalCbzEntry,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverLift(shape = shape, glowColor = accent)
            .glassCard(shape = shape, fill = NyoraTokens.surface1)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Book icon badge — accent-tinted frosted circle
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MenuBook,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(16.dp))

        // File name + file size SystemTag
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = NyoraTokens.onSurfaceHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            SystemTag(
                text = formatBytes(entry.sizeBytes),
                color = NyoraTokens.onSurfaceMuted,
            )
        }

        // Chevron hint
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = Icons.Rounded.Archive,
            contentDescription = null,
            tint = NyoraTokens.onSurfaceFaint,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Polished empty state — centered icon + prose + ghost cue
// ---------------------------------------------------------------------------

@Composable
private fun LocalFilesEmptyState(accent: Color) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Glowing icon container
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.55f),
                    modifier = Modifier.size(36.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "No archives found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = NyoraTokens.onSurfaceBody,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Choose a folder to scan for CBZ and archive files.",
                style = MaterialTheme.typography.bodyMedium,
                color = NyoraTokens.onSurfaceFaint,
            )

            Spacer(Modifier.height(24.dp))

            // Ghost cue — micro hint to use the button above
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(NyoraTokens.surface1)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.60f),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "Use \"Choose Folder\" in the top-right to get started",
                    style = MaterialTheme.typography.labelSmall,
                    color = NyoraTokens.onSurfaceMuted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L          -> "$bytes B"
    bytes < 1024L * 1024   -> "${bytes / 1024} KB"
    else                   -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
}

private fun pickDirectory(): String? {
    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
        chooser.selectedFile.absolutePath
    else null
}
