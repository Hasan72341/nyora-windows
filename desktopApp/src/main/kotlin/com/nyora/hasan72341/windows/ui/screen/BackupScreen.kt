package com.nyora.windows.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.ui.theme.*
import com.nyora.hasan72341.shared.repository.SqlDelightLibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

// -------------------------------------------------------------------------------------
// BackupScreen — Backup & Restore
// -------------------------------------------------------------------------------------

/**
 * Backup & Restore screen.
 *
 * Two big glass-card action tiles (EXPORT / IMPORT) plus an ADVANCED group that exposes:
 *  - App Locale dropdown (auto + common locale codes) bound to [AppState.appLocale].
 *  - Clear Image Cache — deletes the Coil3 disk cache directory best-effort and shows
 *    a status snack.
 *  - Reveal Database in Folder — opens the parent directory of the SQLDelight database
 *    file in the system file manager via [Desktop.getDesktop().open()].
 *
 * Status feedback is provided by [AppState.statusMessage] which the parent scaffold
 * surfaces as a snack-bar.
 */
@Composable
fun BackupScreen(state: AppState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val accentColor = LocalNyoraAccent.current.color

    // State for the import confirmation dialog
    var pendingImportPath by remember { mutableStateOf<String?>(null) }

    // Status message to display below the tiles (mirrors state.statusMessage)
    val statusMsg = state.statusMessage

    // ── Root container ──────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        // ── Top bar with back button ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(44.dp)
                    .glassOverlay(shape = RoundedCornerShape(14.dp)),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = NyoraTokens.onSurfaceHigh,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(16.dp))

            SectionHeader(
                title = "Backup & Restore",
                subtitle = "Export or import your library",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── Action tiles ─────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {

            // EXPORT tile
            BackupActionTile(
                title = "Export Backup",
                description = "Save your full library — favourites, history, bookmarks and categories — to a JSON file.",
                icon = Icons.Outlined.Upload,
                actionLabel = "Export",
                accentColor = accentColor,
                onClick = {
                    scope.launch {
                        val path = pickSavePath()
                        if (path != null) {
                            state.exportBackup(path)
                        }
                    }
                },
            )

            // IMPORT tile
            BackupActionTile(
                title = "Import Backup",
                description = "Restore a previously exported JSON backup. This will overwrite your current library data.",
                icon = Icons.Outlined.Download,
                actionLabel = "Import",
                accentColor = accentColor,
                onClick = {
                    scope.launch {
                        val path = pickOpenPath()
                        if (path != null) {
                            pendingImportPath = path
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── ADVANCED group ────────────────────────────────────────────────────────
        AdvancedGroup(state = state, scope = scope, accentColor = accentColor)

        // ── Live status hint ──────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = statusMsg != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            if (statusMsg != null) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = statusMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = accentColor.copy(alpha = 0.85f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(48.dp))
    }

    // ── Import confirmation dialog ────────────────────────────────────────────────
    if (pendingImportPath != null) {
        ImportConfirmDialog(
            onConfirm = {
                val path = pendingImportPath!!
                pendingImportPath = null
                state.importBackup(path)
            },
            onDismiss = { pendingImportPath = null },
        )
    }
}

// -------------------------------------------------------------------------------------
// AdvancedGroup — App Locale + Clear Image Cache + Reveal Database
// -------------------------------------------------------------------------------------

/** Locale options surfaced in the App Locale dropdown. */
private val LOCALE_OPTIONS = listOf(
    "auto" to "System default",
    "en"   to "English",
    "ja"   to "Japanese",
    "zh"   to "Chinese (Simplified)",
    "ko"   to "Korean",
    "fr"   to "French",
    "de"   to "German",
    "es"   to "Spanish",
    "pt"   to "Portuguese",
    "ar"   to "Arabic",
)

@Composable
private fun AdvancedGroup(
    state: AppState,
    scope: kotlinx.coroutines.CoroutineScope,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    val tileShape = RoundedCornerShape(24.dp)

    // ── Section eyebrow ───────────────────────────────────────────────────────────
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            SystemTag(text = "Configuration")
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Advanced",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                color = NyoraTokens.onSurfaceHigh,
            )
        }
    }

    Spacer(Modifier.height(14.dp))

    // ── Advanced card ─────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(shape = tileShape, fill = NyoraTokens.surface1)
            .padding(vertical = 4.dp),
    ) {
        // ── App Locale ──────────────────────────────────────────────────────────
        AppLocaleRow(
            currentLocale = state.appLocale,
            accentColor = accentColor,
            onLocaleSelected = { locale ->
                state.appLocale = locale
                state.persistSettings()
            },
        )

        AdvancedDivider()

        // ── Clear Image Cache ───────────────────────────────────────────────────
        AdvancedActionRow(
            icon = Icons.Outlined.Delete,
            title = "Clear Image Cache",
            subtitle = "Remove cached cover and page images",
            accentColor = accentColor,
            onClick = {
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) { clearCoilDiskCache() }
                    state.showAdvancedStatus(
                        if (deleted) "Image cache cleared." else "Image cache already empty.",
                    )
                }
            },
        )

        AdvancedDivider()

        // ── Reveal Database in Folder ───────────────────────────────────────────
        AdvancedActionRow(
            icon = Icons.Outlined.FolderOpen,
            title = "Reveal Database",
            subtitle = "Open the database folder in Files",
            accentColor = accentColor,
            onClick = {
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        val dbPath = SqlDelightLibraryRepository.defaultDatabasePath()
                        val parent = dbPath.toFile().parentFile
                        if (parent != null && parent.exists()) {
                            withContext(Dispatchers.Main) {
                                runCatching { Desktop.getDesktop().open(parent) }
                                    .onFailure { state.showAdvancedStatus("Cannot open folder: ${it.message}") }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                state.showAdvancedStatus("Database folder not found.")
                            }
                        }
                    }.onFailure {
                        withContext(Dispatchers.Main) {
                            state.showAdvancedStatus("Error: ${it.message}")
                        }
                    }
                }
            },
        )
    }
}

// ── App Locale dropdown row ────────────────────────────────────────────────────────

@Composable
private fun AppLocaleRow(
    currentLocale: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onLocaleSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = LOCALE_OPTIONS.firstOrNull { it.first == currentLocale }?.second
        ?: currentLocale.ifEmpty { "System default" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading icon + label
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = "App Locale",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = NyoraTokens.onSurfaceHigh,
                )
                Text(
                    text = "Language used across the app",
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // Dropdown trigger button
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .glassOverlay(
                        shape = RoundedCornerShape(12.dp),
                        fill = accentColor.copy(alpha = 0.10f),
                    )
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                )
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = "Select locale",
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .glassCard(
                        shape = RoundedCornerShape(16.dp),
                        fill = NyoraTokens.surface2,
                    ),
            ) {
                LOCALE_OPTIONS.forEach { (code, displayName) ->
                    val isSelected = code == currentLocale
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) accentColor else NyoraTokens.onSurfaceBody,
                                )
                                if (code != "auto") {
                                    Text(
                                        text = code,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NyoraTokens.onSurfaceFaint,
                                    )
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onLocaleSelected(code)
                        },
                        modifier = Modifier.then(
                            if (isSelected) Modifier.glassOverlay(
                                shape = RoundedCornerShape(10.dp),
                                fill = accentColor.copy(alpha = 0.10f),
                            ) else Modifier
                        ),
                    )
                }
            }
        }
    }
}

// ── Generic advanced action row ────────────────────────────────────────────────────

@Composable
private fun AdvancedActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = NyoraTokens.onSurfaceHigh,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NyoraTokens.onSurfaceMuted,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = NyoraTokens.onSurfaceFaint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun AdvancedDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 18.dp),
        color = NyoraTokens.hairlineFaint,
    )
}

// ── Coil disk-cache clear helper ───────────────────────────────────────────────────

/**
 * Deletes all files inside the Coil3 disk-cache directory best-effort.
 *
 * Coil3 stores its JVM disk cache under `<java.io.tmpdir>/coil3_disk_cache` by default
 * (see `coil3.disk.DiskCacheKt.singletonDiskCache`). We locate that directory and
 * recursively delete its contents without touching the directory itself so the OS
 * temp path remains intact.
 *
 * Returns `true` when at least one file was removed, `false` when the directory was
 * already absent or empty.
 */
private fun clearCoilDiskCache(): Boolean {
    val tmpDir = System.getProperty("java.io.tmpdir") ?: return false
    val cacheDir = java.io.File(tmpDir, "coil3_disk_cache")
    if (!cacheDir.exists() || !cacheDir.isDirectory) return false
    var deletedAny = false
    cacheDir.walkTopDown().filter { it != cacheDir }.forEach { f ->
        runCatching { if (f.delete()) deletedAny = true }
    }
    return deletedAny
}

// ── AppState extension — status helper scoped to this screen ──────────────────────

/**
 * Shows a transient status message from the advanced panel. Delegates to the same
 * [AppState.statusMessage] mechanism used by export/import so feedback is consistent.
 */
private fun AppState.showAdvancedStatus(msg: String) {
    scope.launch {
        statusMessage = msg
        kotlinx.coroutines.delay(3000)
        if (statusMessage == msg) statusMessage = null
    }
}

// -------------------------------------------------------------------------------------
// BackupActionTile
// -------------------------------------------------------------------------------------

/**
 * A large frosted glass tile with an accent-tinted icon, title, description body and a
 * prominent action button. Uses [Modifier.hoverLift] for the desktop hover spring effect.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun BackupActionTile(
    title: String,
    description: String,
    icon: ImageVector,
    actionLabel: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val tileShape = RoundedCornerShape(24.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverLift(shape = tileShape, scaleTo = 1.025f, glowColor = accentColor)
            .glassCard(shape = tileShape, fill = NyoraTokens.surface1),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Icon beacon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .glassOverlay(
                        shape = RoundedCornerShape(18.dp),
                        fill = accentColor.copy(alpha = 0.12f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp),
                )
            }

            // Text column
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp,
                    color = NyoraTokens.onSurfaceHigh,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NyoraTokens.onSurfaceMuted,
                    lineHeight = 20.sp,
                )
            }

            // Action button
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor.copy(alpha = 0.18f),
                    contentColor = accentColor,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = actionLabel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// ImportConfirmDialog
// -------------------------------------------------------------------------------------

/**
 * Alert dialog that warns the user the import will overwrite their existing library.
 */
@Composable
private fun ImportConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NyoraTokens.surface1,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = null,
                tint = LocalNyoraAccent.current.color,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text(
                text = "Overwrite Library?",
                fontWeight = FontWeight.ExtraBold,
                color = NyoraTokens.onSurfaceHigh,
            )
        },
        text = {
            Text(
                text = "Importing a backup will replace your current favourites, history, bookmarks and categories. This action cannot be undone.",
                color = NyoraTokens.onSurfaceBody,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalNyoraAccent.current.color,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ),
            ) {
                Text("Import", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Cancel", color = NyoraTokens.onSurfaceMuted)
            }
        },
    )
}

// -------------------------------------------------------------------------------------
// File chooser helpers — must NOT run on the Compose/UI thread.
// -------------------------------------------------------------------------------------

/**
 * Opens a JFileChooser Save dialog on the AWT Event Dispatch Thread (via
 * [SwingUtilities.invokeAndWait]) from a background coroutine, so the Compose
 * thread is never blocked.
 *
 * Returns the chosen absolute path, or `null` if the user cancelled.
 */
private suspend fun pickSavePath(): String? = withContext(Dispatchers.IO) {
    var result: String? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export Nyora Backup"
            selectedFile = java.io.File("nyora-backup.json")
            fileFilter = FileNameExtensionFilter("JSON Backup (*.json)", "json")
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            // Ensure the .json extension is appended if the user omitted it.
            if (!file.name.endsWith(".json", ignoreCase = true)) {
                file = java.io.File(file.absolutePath + ".json")
            }
            result = file.absolutePath
        }
    }
    result
}

/**
 * Opens a JFileChooser Open dialog on the AWT EDT from a background coroutine.
 *
 * Returns the chosen absolute path, or `null` if the user cancelled.
 */
private suspend fun pickOpenPath(): String? = withContext(Dispatchers.IO) {
    var result: String? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply {
            dialogTitle = "Import Nyora Backup"
            fileFilter = FileNameExtensionFilter("JSON Backup (*.json)", "json")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            result = chooser.selectedFile.absolutePath
        }
    }
    result
}
