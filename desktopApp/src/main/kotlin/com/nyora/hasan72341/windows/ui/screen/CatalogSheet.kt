package com.nyora.windows.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraSmooth
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.accentGradient
import com.nyora.windows.ui.theme.accentGradientSubtle
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.glassOverlay
import com.nyora.windows.ui.theme.hoverLift

private val LANG_OPTIONS = listOf("all", "en", "ja", "zh", "ko", "es", "fr", "de", "pt", "ru", "ar", "it")

// Content-type values (from SourceContentType enum) that are adult/NSFW.
// Compared case-insensitively against CatalogEntry.contentType.
private val NSFW_CONTENT_TYPES = setOf("hentai", "doujinshi", "imageset", "artistcg", "gamecg")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogSheet(state: AppState) {
    LaunchedEffect(Unit) {
        if (state.catalogEntries.isEmpty()) state.loadCatalog()
    }

    val accent = LocalNyoraAccent.current.color

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NyoraTokens.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp),
        ) {

            // ── Header ────────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back button — glass icon button
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .hoverLift(shape = RoundedCornerShape(14.dp), scaleTo = 1.06f)
                        .glassOverlay(shape = RoundedCornerShape(14.dp), fill = NyoraTokens.surface1)
                        .clickable { state.showCatalog = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = NyoraTokens.onSurfaceBody,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.width(16.dp))

                SectionHeader(
                    title = "Sources",
                    subtitle = "Install catalog",
                    modifier = Modifier.weight(1f),
                )

                if (state.catalogLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = accent,
                        strokeWidth = 2.dp,
                    )
                }
            }

            // ── Frosted glass search bar ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .glassOverlay(shape = RoundedCornerShape(26.dp), fill = NyoraTokens.surface1),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = NyoraTokens.onSurfaceFaint,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = state.catalogSearch,
                        onValueChange = { state.catalogSearch = it },
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(
                            color = NyoraTokens.onSurfaceHigh,
                            fontSize = 15.sp,
                        ),
                        cursorBrush = SolidColor(accent),
                        decorationBox = { innerTextField ->
                            if (state.catalogSearch.isEmpty()) {
                                Text(
                                    "Search extensions...",
                                    color = NyoraTokens.onSurfaceFaint,
                                    fontSize = 15.sp,
                                )
                            }
                            innerTextField()
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Language filter pills ─────────────────────────────────────────────
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(LANG_OPTIONS) { lang ->
                    val selected = state.catalogLang == lang
                    LangFilterPill(
                        label = if (lang == "all") "All Languages" else lang.uppercase(),
                        selected = selected,
                        onClick = { state.catalogLang = lang },
                    )
                }
            }

            // ── Filtered entry list ───────────────────────────────────────────────
            val filtered = state.catalogEntries.filter { entry ->
                val langOk = state.catalogLang == "all" || entry.lang == state.catalogLang
                val searchOk = state.catalogSearch.isBlank() ||
                    entry.name.contains(state.catalogSearch, ignoreCase = true) ||
                    entry.lang.contains(state.catalogSearch, ignoreCase = true)
                val nsfwOk = !state.hideNsfwSources ||
                    entry.contentType.lowercase() !in NSFW_CONTENT_TYPES
                langOk && searchOk && nsfwOk
            }

            if (filtered.isEmpty() && !state.catalogLoading) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No extensions found",
                        color = NyoraTokens.onSurfaceFaint,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        CatalogBentoRow(
                            entry = entry,
                            onInstall = { state.installSource(entry.id) },
                            onUninstall = { state.uninstallSource(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

// ── Language filter pill ──────────────────────────────────────────────────────────

@Composable
private fun LangFilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalNyoraAccent.current.color
    val pillShape = RoundedCornerShape(50)
    val subtleBrush = accentGradientSubtle()

    Box(
        modifier = Modifier
            .hoverLift(shape = pillShape, scaleTo = 1.05f)
            .then(
                if (selected) {
                    Modifier
                        .clip(pillShape)
                        .background(subtleBrush)
                        .border(width = 1.dp, color = accent.copy(alpha = 0.45f), shape = pillShape)
                } else {
                    Modifier.glassOverlay(shape = pillShape, fill = NyoraTokens.surface1)
                },
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) NyoraTokens.onSurfaceHigh else NyoraTokens.onSurfaceBody,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

// ── Catalog entry bento row ───────────────────────────────────────────────────────

@Composable
private fun CatalogBentoRow(
    entry: com.nyora.windows.bridge.CatalogEntry,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val accent = LocalNyoraAccent.current.color
    val rowShape = RoundedCornerShape(20.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverLift(shape = rowShape)
            .glassCard(shape = rowShape, fill = NyoraTokens.surface1)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: name + tags
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            // Name row with optional broken indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NyoraTokens.onSurfaceHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.isBroken) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Broken",
                        tint = androidx.compose.ui.graphics.Color(0xFFFFB703),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // Badge row: lang + engine + contentType via SystemTag
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Language badge — accent-tinted glass pill
                Box(
                    modifier = Modifier
                        .glassOverlay(shape = RoundedCornerShape(6.dp), fill = NyoraTokens.surface1)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    SystemTag(text = entry.lang, color = accent)
                }

                // Engine badge — muted glass pill
                if (entry.engine.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .glassOverlay(shape = RoundedCornerShape(6.dp), fill = NyoraTokens.surface1)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        SystemTag(text = entry.engine, color = NyoraTokens.onSurfaceMuted)
                    }
                }

                // Content-type badge — muted
                if (entry.contentType.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .glassOverlay(shape = RoundedCornerShape(6.dp), fill = NyoraTokens.surface1)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        SystemTag(text = entry.contentType, color = NyoraTokens.onSurfaceFaint)
                    }
                }
            }
        }

        // Right: install / installed / uninstall action
        if (entry.isInstalled) {
            InstalledActions(
                accent = accent,
                onUninstall = onUninstall,
            )
        } else {
            InstallButton(accent = accent, onClick = onInstall)
        }
    }
}

// ── Uninstall icon button (hover-aware) ───────────────────────────────────────────

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun UninstallIconButton(onUninstall: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val iconAlpha by animateFloatAsState(
        targetValue = if (hovered) 0.80f else 0.35f,
        animationSpec = NyoraSmooth,
        label = "uninstallIconAlpha",
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .hoverLift(shape = RoundedCornerShape(50), scaleTo = 1.08f)
            .glassOverlay(shape = RoundedCornerShape(50), fill = NyoraTokens.surface1)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable { onUninstall() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Delete,
            contentDescription = "Uninstall",
            tint = NyoraTokens.onSurfaceBody.copy(alpha = iconAlpha),
            modifier = Modifier.size(15.dp),
        )
    }
}

// ── Install button ────────────────────────────────────────────────────────────────

@Composable
private fun InstallButton(accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val btnShape = RoundedCornerShape(12.dp)
    val gradientBrush = accentGradient()
    Box(
        modifier = Modifier
            .hoverLift(shape = btnShape, scaleTo = 1.06f, glowColor = accent)
            .clip(btnShape)
            .background(gradientBrush)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Install",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = androidx.compose.ui.graphics.Color.White,
        )
    }
}

// ── Installed state: check + uninstall icon ───────────────────────────────────────

@Composable
private fun InstalledActions(
    accent: androidx.compose.ui.graphics.Color,
    onUninstall: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Installed check badge
        Box(
            modifier = Modifier
                .size(32.dp)
                .glassOverlay(shape = RoundedCornerShape(50), fill = accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Installed",
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
        }

        // Uninstall icon button — subtle glass, tint fades in on hover
        UninstallIconButton(onUninstall = onUninstall)
    }
}
