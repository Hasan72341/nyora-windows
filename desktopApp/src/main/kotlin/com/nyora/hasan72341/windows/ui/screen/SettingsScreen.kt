package com.nyora.windows.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.ai.AiMode
import com.nyora.windows.NavDest
import com.nyora.windows.ReaderMode
import com.nyora.windows.ui.theme.Accent
import com.nyora.windows.ui.theme.AppearanceMode
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.accentGradientSubtle
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.glowBorder
import com.nyora.hasan72341.shared.repository.SqlDelightLibraryRepository

/** App version surfaced in the About section; no build system constant is wired yet. */
const val VERSION: String = "1.0.0"

// ── Dropdown option lists ──────────────────────────────────────────────────────────────

private val READER_MODE_OPTIONS = listOf(
    ReaderMode.PAGED    to "Paged",
    ReaderMode.WEBTOON  to "Webtoon",
    ReaderMode.VERTICAL to "Vertical",
)

private val HISTORY_SORT_OPTIONS = listOf(
    "recent" to "Recently Read",
    "alpha"  to "Alphabetical",
    "added"  to "Date Added",
)

private val TARGET_LANG_OPTIONS = listOf(
    "en" to "English",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "pt" to "Portuguese",
    "it" to "Italian",
    "ru" to "Russian",
    "zh" to "Chinese (Simplified)",
    "ko" to "Korean",
    "ar" to "Arabic",
)

// BCP-47 tags for the Windows OCR engine (Windows.Media.Ocr). The matching
// Windows OCR language pack must be installed for recognition to work.
private val OCR_LANG_OPTIONS = listOf(
    "ja"      to "Japanese",
    "en"      to "English",
    "ko"      to "Korean",
    "zh-Hans" to "Chinese (Simplified)",
    "zh-Hant" to "Chinese (Traditional)",
)

private val AI_MODE_OPTIONS = listOf(
    AiMode.OFF     to "Off (machine translation only)",
    AiMode.WINDOWS to "Windows AI (Phi Silica, on-device)",
    AiMode.BYOK    to "Custom key (OpenAI-compatible)",
)

/**
 * "Midnight Sakura" Settings — full mac-parity.
 *
 * A flat-surface settings screen. Most of the time it renders the scrolling settings
 * list; tapping a navigation row swaps the whole surface for a full-screen sub-panel
 * (Network / Backup / AniList) which calls back to dismiss. Statistics is a top-level
 * nav destination rather than a panel, so it routes through [AppState.destination].
 */
@Composable
fun SettingsScreen(state: AppState) {
    // Which full-screen sub-panel (if any) is currently overlaid on the settings list.
    var panel by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (panel) {
            "network" -> NetworkSettingsScreen(state) { panel = null }
            "backup"  -> BackupScreen(state) { panel = null }
            "tracker" -> TrackerScreen(state) { panel = null }
            else      -> SettingsList(state) { panel = it }
        }
    }
}

// ── Settings list ───────────────────────────────────────────────────────────────────

@Composable
private fun SettingsList(state: AppState, openPanel: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        SectionHeader(title = "Settings", subtitle = "Tune Nyora")

        // (1) APPEARANCE ──────────────────────────────────────────────────────────────────
        SettingsSection(eyebrow = "Look & Feel", title = "Appearance", icon = Icons.Rounded.Palette) {
            // Theme — Amoled / Light segmented control with accentGradientSubtle on selected
            SettingsRow("Theme") {
                AppearanceSegmented(
                    selected  = state.appearance,
                    onSelect  = { state.setAppearance(it) },
                )
            }
            HairlineDivider()
            // Accent swatches
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text(
                    "Accent",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NyoraTokens.onSurfaceHigh,
                )
                Spacer(Modifier.height(14.dp))
                AccentSwatchRow(
                    selected = state.accent,
                    onSelect = { state.setAccent(it) },
                )
            }
        }

        // (2) READER ──────────────────────────────────────────────────────────────────────
        SettingsSection(eyebrow = "Reading", title = "Reader", icon = Icons.Rounded.AutoStories) {
            // Default reader mode dropdown
            SettingsRow("Default Reader Mode") {
                NyoraDropdown(
                    selected = state.defaultReaderMode,
                    options  = READER_MODE_OPTIONS,
                    onSelect = {
                        state.defaultReaderMode = it
                        state.persistSettings()
                    },
                )
            }
            HairlineDivider()
            // Auto-detect reader mode
            SettingsToggle("Auto-Detect Reader Mode", state.autoDetectReaderMode) {
                state.autoDetectReaderMode = it
                state.persistSettings()
            }
            HairlineDivider()
            // Reader background segmented
            SettingsRow("Reader Background") {
                ThreeSegmented(
                    options  = listOf("auto" to "Auto", "dark" to "Dark", "light" to "Light"),
                    selected = state.readerBackground,
                    onSelect = { state.setReaderBackground(it) },
                )
            }
            HairlineDivider()
            // Show zoom buttons
            SettingsToggle("Show Zoom Buttons", state.showZoomButtons) {
                state.showZoomButtons = it
                state.persistSettings()
            }
            HairlineDivider()
            // Two-page in landscape
            SettingsToggle("Two Pages in Landscape", state.twoPageLandscape) {
                state.twoPageLandscape = it
                state.persistSettings()
            }
            HairlineDivider()
            // Auto-hide controls
            SettingsToggle("Auto-Hide Controls", state.autoHideControls) {
                state.autoHideControls = it
                state.persistSettings()
            }
            HairlineDivider()
            // Keep screen on
            SettingsToggle("Keep Screen On", state.keepScreenOn) {
                state.keepScreenOn = it
                state.persistSettings()
            }
            HairlineDivider()
            // Prefetch next pages (existing)
            SettingsToggle("Prefetch Next Pages", state.prefetchEnabled) { state.prefetchEnabled = it }
            HairlineDivider()
            // Show page numbers (existing)
            SettingsToggle("Show Page Numbers", state.showPageNumbers) { state.showPageNumbers = it }
            HairlineDivider()
            // Description collapse
            SettingsToggle("Collapse Description by Default", state.descriptionCollapse) {
                state.descriptionCollapse = it
                state.persistSettings()
            }
            HairlineDivider()
            // Grid size slider 120-220
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Cover Grid Size",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NyoraTokens.onSurfaceHigh,
                    )
                    Text(
                        "${state.gridSize} dp",
                        style = MaterialTheme.typography.labelLarge,
                        color = LocalNyoraAccent.current.color,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value       = state.gridSize.toFloat(),
                    onValueChange = { state.gridSize = it.toInt() },
                    onValueChangeFinished = { state.persistSettings() },
                    valueRange  = 120f..220f,
                    steps       = 9, // 10 dp increments → 10 steps
                    colors      = SliderDefaults.colors(
                        thumbColor          = LocalNyoraAccent.current.color,
                        activeTrackColor    = LocalNyoraAccent.current.color,
                        inactiveTrackColor  = NyoraTokens.surface2,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("120", style = MaterialTheme.typography.labelSmall, color = NyoraTokens.onSurfaceFaint)
                    Text("220", style = MaterialTheme.typography.labelSmall, color = NyoraTokens.onSurfaceFaint)
                }
            }
            HairlineDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Color correction lives in the reader — open any chapter and tap the palette.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                )
            }
        }

        // (3) LIBRARY & HISTORY ───────────────────────────────────────────────────────────
        SettingsSection(eyebrow = "Library", title = "Library & History", icon = Icons.Rounded.History) {
            // Hide NSFW content (existing)
            SettingsToggle("Hide NSFW Content", state.nsfwFilter) { state.nsfwFilter = it }
            HairlineDivider()
            // Hide NSFW sources (new)
            SettingsToggle("Hide NSFW Sources", state.hideNsfwSources) {
                state.hideNsfwSources = it
                state.persistSettings()
            }
            HairlineDivider()
            // History retention slider 0-365 step 30
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "History Retention",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NyoraTokens.onSurfaceHigh,
                    )
                    Text(
                        if (state.historyRetentionDays == 0) "Forever"
                        else "${state.historyRetentionDays} days",
                        style = MaterialTheme.typography.labelLarge,
                        color = LocalNyoraAccent.current.color,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // steps value: 0, 30, 60 … 360 → 13 stops, steps param = 11 (between first & last)
                Slider(
                    value       = state.historyRetentionDays.toFloat(),
                    onValueChange = {
                        // Snap to nearest 30-day multiple (0 = Forever)
                        val snapped = (Math.round(it / 30f) * 30).coerceIn(0, 360)
                        state.historyRetentionDays = snapped
                    },
                    onValueChangeFinished = { state.persistSettings() },
                    valueRange  = 0f..360f,
                    steps       = 11,
                    colors      = SliderDefaults.colors(
                        thumbColor          = LocalNyoraAccent.current.color,
                        activeTrackColor    = LocalNyoraAccent.current.color,
                        inactiveTrackColor  = NyoraTokens.surface2,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Forever", style = MaterialTheme.typography.labelSmall, color = NyoraTokens.onSurfaceFaint)
                    Text("360 days", style = MaterialTheme.typography.labelSmall, color = NyoraTokens.onSurfaceFaint)
                }
            }
            HairlineDivider()
            // Group history by date
            SettingsToggle("Group History by Date", state.groupHistoryByDate) {
                state.groupHistoryByDate = it
                state.persistSettings()
            }
            HairlineDivider()
            // History sort order dropdown
            SettingsRow("History Sort Order") {
                NyoraDropdown(
                    selected = state.historySortOrder,
                    options  = HISTORY_SORT_OPTIONS,
                    onSelect = {
                        state.historySortOrder = it
                        state.persistSettings()
                    },
                )
            }
        }

        // (4) TRANSLATION ──────────────────────────────────────────────────────────────────
        SettingsSection(eyebrow = "OCR / MT", title = "Translation", icon = Icons.Rounded.Translate) {
            // Translation enabled
            SettingsToggle("Enable In-Reader Translation", state.translateEnabled) {
                state.translateEnabled = it
            }
            HairlineDivider()
            // Instant translate on chapter open
            SettingsToggle("Instant Translate on Chapter Open", state.instantTranslate) {
                state.instantTranslate = it
                state.persistSettings()
            }
            HairlineDivider()
            // Target language dropdown
            SettingsRow("Target Language") {
                NyoraDropdown(
                    selected = state.translateTarget,
                    options  = TARGET_LANG_OPTIONS,
                    onSelect = { state.changeTranslateTarget(it) },
                )
            }
            HairlineDivider()
            // OCR source language
            SettingsRow("OCR Source Language") {
                NyoraDropdown(
                    selected = state.translateLangs,
                    options  = OCR_LANG_OPTIONS,
                    onSelect = { state.changeTranslateLangs(it) },
                )
            }
            InfoNote(
                "On-device OCR uses the built-in Windows OCR engine. Add the source " +
                    "language under Windows Settings ▸ Time & language ▸ Language & region " +
                    "to install its OCR support.",
            )
            HairlineDivider()
            // AI refinement — polish the machine-translation draft.
            SettingsRow("AI Refinement") {
                NyoraDropdown(
                    selected = state.aiMode,
                    options  = AI_MODE_OPTIONS,
                    onSelect = { state.changeAiMode(it) },
                )
            }
            when (state.aiMode) {
                AiMode.WINDOWS -> {
                    LaunchedEffect(Unit) { state.refreshWindowsAi() }
                    InfoNote(
                        when (state.windowsAiAvailable) {
                            true -> "Windows AI (Phi Silica) is available — polishing translations on-device, free and private."
                            false -> "Windows AI isn't available on this PC (needs a Copilot+ PC with the Windows App SDK runtime). Falling back to machine translation — or choose Custom key below."
                            null -> "Checking Windows AI availability…"
                        },
                    )
                }
                AiMode.BYOK -> ByokFields(state)
                AiMode.OFF -> {}
            }
        }

        // (5) PRIVACY ──────────────────────────────────────────────────────────────────────
        SettingsSection(eyebrow = "Security", title = "Privacy", icon = Icons.Rounded.Lock) {
            // Incognito (side-effect setter)
            SettingsToggle("Incognito Mode", state.incognito) {
                state.setIncognito(it)
            }
            HairlineDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            ) {
                Text(
                    "Reading is not recorded to history while incognito.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                )
            }
            HairlineDivider()
            // Confirm before quitting
            SettingsToggle("Confirm Before Quitting", state.confirmBeforeQuit) {
                state.confirmBeforeQuit = it
                state.persistSettings()
            }
        }

        // (6) CLOUD SYNC ───────────────────────────────────────────────────────────────────
        CloudSyncSection(state)

        // (6b) PARSER UPDATES ──────────────────────────────────────────────────────────────
        ParserUpdatesSection(state)

        // (7) ADVANCED / NAVIGATION ────────────────────────────────────────────────────────
        SettingsSection(eyebrow = "Advanced", title = "More", icon = Icons.Rounded.SyncAlt) {
            NavRow("Network", "Proxy, DoH, mirrors", Icons.Rounded.Cloud) { openPanel("network") }
            HairlineDivider()
            NavRow("Downloads", "Concurrent downloads, format", Icons.Rounded.Download) {
                state.destination = NavDest.DOWNLOADS
            }
            HairlineDivider()
            NavRow("Backup & Restore", "Export or import your library", Icons.Rounded.Storage) { openPanel("backup") }
            HairlineDivider()
            NavRow("AniList Tracker", "Sync reading progress", Icons.Rounded.SyncAlt) { openPanel("tracker") }
            HairlineDivider()
            NavRow("Statistics", "Streaks, top sources & more", Icons.Rounded.BarChart) {
                state.destination = NavDest.STATS
            }
        }

        // (8) ABOUT ────────────────────────────────────────────────────────────────────────
        SettingsSection(eyebrow = "Info", title = "About", icon = Icons.Rounded.AutoStories) {
            SettingsRow("Build") {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Nyora • Windows",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = NyoraTokens.onSurfaceHigh,
                    )
                    Text(
                        "v$VERSION",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalNyoraAccent.current.color,
                    )
                }
            }
            HairlineDivider()
            SettingsRow("Platform") {
                Text(
                    "${System.getProperty("os.name")} (${System.getProperty("os.arch")})",
                    style = MaterialTheme.typography.labelSmall,
                    color = NyoraTokens.onSurfaceMuted,
                )
            }
            HairlineDivider()
            SettingsRow("Database") {
                Text(
                    text = SqlDelightLibraryRepository.defaultDatabasePath().toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceFaint,
                )
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun CloudSyncSection(state: AppState) {
    LaunchedEffect(Unit) { state.refreshCloudSyncStatus() }
    val status = state.cloudSyncStatus
    val busy = state.cloudSyncBusy
    val statusText = when {
        status == null -> "Checking..."
        !status.isConfigured -> "Not configured"
        status.isAuthenticated -> "Signed in"
        else -> "Signed out"
    }
    SettingsSection(eyebrow = "Cloud", title = "Nyora Sync", icon = Icons.Rounded.Cloud) {
        SettingsRow("Status") {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (status?.isAuthenticated == true) LocalNyoraAccent.current.color else NyoraTokens.onSurfaceMuted,
                )
                val subtitle = status?.email?.takeIf { it.isNotBlank() }
                    ?: status?.userId?.takeIf { it.isNotBlank() }?.take(8)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = NyoraTokens.onSurfaceFaint,
                    )
                }
            }
        }
        HairlineDivider()
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (status?.isAuthenticated == true) {
                Button(onClick = { state.cloudSyncNow() }, enabled = !busy) {
                    Text(if (busy) "Working..." else "Sync Now")
                }
                OutlinedButton(onClick = { state.cloudRestoreFromCloud() }, enabled = !busy) {
                    Text("Restore From Cloud")
                }
                TextButton(onClick = { state.cloudSignOut() }, enabled = !busy) {
                    Text("Sign Out")
                }
            } else {
                Button(onClick = { state.cloudSignInWithGoogle() }, enabled = !busy && status?.isConfigured != false) {
                    Text(if (busy) "Opening..." else "Sign in with Google")
                }
            }
        }
    }
}

@Composable
private fun ParserUpdatesSection(state: AppState) {
    LaunchedEffect(Unit) { state.refreshOtaStatus() }
    val status = state.otaStatus
    val busy = state.otaBusy
    val statusText = when {
        status == null -> "Checking..."
        status.isActive -> "OTA v${status.otaVersion} active"
        else -> "Bundled v${status.bundledVersion}"
    }
    SettingsSection(eyebrow = "Parsers", title = "Parser Updates", icon = Icons.Rounded.Update) {
        SettingsRow("Version") {
            Text(
                statusText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (status?.isActive == true) LocalNyoraAccent.current.color else NyoraTokens.onSurfaceMuted,
            )
        }
        HairlineDivider()
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { state.otaCheckNow() }, enabled = !busy) {
                Text(if (busy) "Checking..." else "Check for Updates")
            }
            if (status?.isActive == true) {
                Text(
                    "Restart the app to apply newly downloaded parsers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                )
            }
        }
    }
}

// ── Section shell ─────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(
    eyebrow: String,
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = LocalNyoraAccent.current.color,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                SystemTag(text = eyebrow)
                Spacer(Modifier.height(3.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    color = NyoraTokens.onSurfaceHigh,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(shape = RoundedCornerShape(24.dp), fill = NyoraTokens.surface1)
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

// ── Generic rows ──────────────────────────────────────────────────────────────────

@Composable
private fun SettingsRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = NyoraTokens.onSurfaceHigh,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    SettingsRow(label) {
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor   = Color.White,
                checkedTrackColor   = LocalNyoraAccent.current.color,
                uncheckedThumbColor = NyoraTokens.onSurfaceMuted,
                uncheckedTrackColor = NyoraTokens.surface1,
                uncheckedBorderColor = NyoraTokens.hairlineStrong,
            ),
        )
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
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
                .background(NyoraTokens.surface1),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = LocalNyoraAccent.current.color,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = NyoraTokens.onSurfaceHigh,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NyoraTokens.onSurfaceMuted,
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = NyoraTokens.onSurfaceFaint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun HairlineDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 18.dp),
        color = NyoraTokens.hairlineFaint,
    )
}

// ── Appearance segmented control (Amoled / Light) ──────────────────────────────────
//
// Uses accentGradientSubtle() as the fill of the selected segment, matching the
// design-language rule: "accentGradientSubtle on selected chips/segmented fills".

@Composable
private fun AppearanceSegmented(
    selected: AppearanceMode,
    onSelect: (AppearanceMode) -> Unit,
) {
    val subtleBrush = accentGradientSubtle()
    val accent      = LocalNyoraAccent.current.color
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(NyoraTokens.surface1)
            .border(width = 1.dp, color = NyoraTokens.hairlineFaint, shape = RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppearanceMode.entries.forEach { mode ->
            val isSelected = mode == selected
            val label = when (mode) {
                AppearanceMode.AMOLED -> "Amoled"
                AppearanceMode.LIGHT  -> "Light"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .then(
                        if (isSelected) Modifier.background(subtleBrush)
                        else Modifier
                    )
                    .then(
                        if (isSelected) Modifier.border(
                            width = 1.dp,
                            color = accent.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(11.dp),
                        ) else Modifier
                    )
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color      = if (isSelected) accent else NyoraTokens.onSurfaceMuted,
                )
            }
        }
    }
}

// ── Three-option segmented control (e.g. reader background) ───────────────────────

@Composable
private fun ThreeSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val subtleBrush = accentGradientSubtle()
    val accent      = LocalNyoraAccent.current.color
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(NyoraTokens.surface1)
            .border(width = 1.dp, color = NyoraTokens.hairlineFaint, shape = RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .then(
                        if (isSelected) Modifier.background(subtleBrush)
                        else Modifier
                    )
                    .then(
                        if (isSelected) Modifier.border(
                            width = 1.dp,
                            color = accent.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(11.dp),
                        ) else Modifier
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color      = if (isSelected) accent else NyoraTokens.onSurfaceMuted,
                )
            }
        }
    }
}

// ── Nyora-styled dropdown ─────────────────────────────────────────────────────────
//
// A chip-style trigger that opens a DropdownMenu. The selected item label is shown
// in the chip; options are pairs of (value, displayLabel). Works for any <T> by
// using an index-matched approach so the generic stays clean.

/** A muted explanatory note rendered inside a settings section. */
@Composable
private fun InfoNote(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = NyoraTokens.onSurfaceMuted,
        )
    }
}

/**
 * Bring-your-own-key fields for the OpenAI-compatible refiner. Edits persist on
 * every change (the prefs file is tiny). The API key is masked and stored locally
 * in appPrefs.json.
 */
@Composable
private fun ByokFields(state: AppState) {
    var baseUrl by remember { mutableStateOf(state.byokBaseUrl) }
    var apiKey by remember { mutableStateOf(state.byokApiKey) }
    var model by remember { mutableStateOf(state.byokModel) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; state.setByok(it, apiKey, model) },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; state.setByok(baseUrl, it, model) },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; state.setByok(baseUrl, apiKey, it) },
            label = { Text("Model") },
            placeholder = { Text("gpt-4o-mini") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Any OpenAI-compatible Chat Completions endpoint — OpenAI, OpenRouter, Groq, " +
                "or a local LM Studio / Ollama server. The key is stored locally in appPrefs.json.",
            style = MaterialTheme.typography.bodySmall,
            color = NyoraTokens.onSurfaceMuted,
        )
    }
}

@Composable
private fun <T> NyoraDropdown(
    selected: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    val accent = LocalNyoraAccent.current.color

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(NyoraTokens.surface2)
                .border(width = 1.dp, color = NyoraTokens.hairlineStrong, shape = RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                currentLabel,
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color      = NyoraTokens.onSurfaceHigh,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
        }

        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color      = if (isSelected) accent else NyoraTokens.onSurfaceHigh,
                        )
                    },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ── Accent swatch row ───────────────────────────────────────────────────────────────

@Composable
private fun AccentSwatchRow(
    selected: Accent,
    onSelect: (Accent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Accent.entries.forEach { accent ->
            AccentSwatch(
                accent     = accent,
                isSelected = accent == selected,
                onClick    = { onSelect(accent) },
            )
        }
    }
}

@Composable
private fun AccentSwatch(
    accent: Accent,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val size by androidx.compose.animation.core.animateDpAsState(
        targetValue    = if (isSelected) 38.dp else 32.dp,
        animationSpec  = androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 300f),
        label          = "swatchSize",
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(accent.color)
                    .glowBorder(color = accent.color, shape = CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(accent.color)
                    .border(
                        width = 1.dp,
                        color = NyoraTokens.hairlineStrong,
                        shape = CircleShape,
                    ),
            )
        }
    }
}
