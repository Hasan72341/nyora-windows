package com.nyora.windows.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.bridge.CategoryDto
import com.nyora.windows.ui.theme.AnimeAsyncImage
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.glassOverlay
import com.nyora.windows.ui.theme.hoverLift
import com.nyora.hasan72341.shared.model.Manga

/**
 * Library / Favourites — the canonical Midnight Sakura showcase screen.
 *
 * Establishes the consistency-migration pattern other screens follow: every surface is a
 * [glassCard]/[glassOverlay], every cover is an [AnimeAsyncImage] with [hoverLift], every
 * accent comes from [LocalNyoraAccent]. Category filter pills are accent-aware frosted
 * chips (selected = accent fill) and each user category carries an inline management menu
 * (Rename / Delete); a "+ New" chip creates one.
 */
@Composable
fun FavouritesScreen(state: AppState) {
    LaunchedEffect(Unit) { state.loadCategories() }

    // Dialog plumbing: one "new" dialog + one optional "rename target".
    var showNewCategory by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CategoryDto?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {

        // Oversized header with an at-a-glance favourites count chip.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = "Library",
                subtitle = "Your favourites",
                modifier = Modifier.weight(1f),
            )
            FavouritesCountBadge(count = state.favourites.size)
        }

        // Accent-aware category filter rail: "All", every category, then a "+ New" chip.
        CategoryRail(
            categories = state.categories,
            activeCategoryId = state.activeCategoryId,
            onSelectAll = { state.activeCategoryId = null },
            onSelect = { state.activeCategoryId = it },
            onRename = { renameTarget = it },
            onDelete = { state.deleteCategory(it.id) },
            onNew = { showNewCategory = true },
        )

        Spacer(Modifier.height(28.dp))

        // Favourites bento grid (or an empty state).
        val displayList = state.favourites
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (displayList.isEmpty()) {
                EmptyLibraryState()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(displayList, key = { it.id }) { manga ->
                        val src = state.sourceFor(manga)
                        FavouriteBentoCard(
                            manga = manga,
                            coverUrl = state.coverProxyUrl(manga.coverUrl),
                            onClick = { state.openDetails(manga, src) },
                        )
                    }
                }
            }
        }
    }

    // ── New-category dialog ──────────────────────────────────────────────────────
    if (showNewCategory) {
        CategoryNameDialog(
            heading = "New Collection",
            eyebrow = "Create",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { showNewCategory = false },
            onConfirm = { name ->
                if (name.isNotBlank()) state.createCategory(name.trim())
                showNewCategory = false
            },
        )
    }

    // ── Rename-category dialog ───────────────────────────────────────────────────
    renameTarget?.let { target ->
        CategoryNameDialog(
            heading = "Rename Collection",
            eyebrow = "Edit",
            initial = target.title,
            confirmLabel = "Save",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                if (name.isNotBlank()) state.renameCategory(target.id, name.trim())
                renameTarget = null
            },
        )
    }
}

// ── Favourites count badge ──────────────────────────────────────────────────────────

@Composable
private fun FavouritesCountBadge(count: Int) {
    val accent = LocalNyoraAccent.current.color
    Row(
        modifier = Modifier
            .glassOverlay(shape = RoundedCornerShape(50), fill = NyoraTokens.surface1)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.FavoriteBorder,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = NyoraTokens.onSurfaceHigh,
        )
        Spacer(Modifier.width(6.dp))
        SystemTag(text = if (count == 1) "Title" else "Titles", color = NyoraTokens.onSurfaceFaint)
    }
}

// ── Category filter rail ────────────────────────────────────────────────────────────

@Composable
private fun CategoryRail(
    categories: List<CategoryDto>,
    activeCategoryId: Long?,
    onSelectAll: () -> Unit,
    onSelect: (Long) -> Unit,
    onRename: (CategoryDto) -> Unit,
    onDelete: (CategoryDto) -> Unit,
    onNew: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "All" — no management menu (it isn't a user category).
        item {
            CategoryChip(
                label = "All",
                selected = activeCategoryId == null,
                manageable = false,
                onClick = onSelectAll,
                onRename = {},
                onDelete = {},
            )
        }
        items(categories, key = { it.id }) { cat ->
            CategoryChip(
                label = "${cat.title}  ${cat.mangaCount}",
                selected = activeCategoryId == cat.id,
                manageable = true,
                onClick = { onSelect(cat.id) },
                onRename = { onRename(cat) },
                onDelete = { onDelete(cat) },
            )
        }
        // "+ New" creation chip.
        item { NewCategoryChip(onClick = onNew) }
    }
}

/**
 * A single accent-aware glass category chip. Selected => accent fill + white text; idle =>
 * frosted glass + hairline. When [manageable] the chip reveals a kebab affordance that
 * opens a Rename / Delete [DropdownMenu].
 */
@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    manageable: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = LocalNyoraAccent.current.color
    val shape = RoundedCornerShape(50)
    var menuOpen by remember { mutableStateOf(false) }

    // Idle chips use the shared frosted-glass overlay; selected chips paint with accent.
    val fillModifier = if (selected) {
        Modifier
            .clip(shape)
            .background(accent)
            .border(
                width = 1.dp,
                color = NyoraTokens.hairlineStrong,
                shape = shape,
            )
    } else {
        Modifier.glassOverlay(shape = shape, fill = NyoraTokens.surface1)
    }

    Box {
        Row(
            modifier = fillModifier
                .hoverLift(shape = shape, scaleTo = 1.04f)
                .clickable { onClick() }
                .padding(
                    start = 18.dp,
                    end = if (manageable) 6.dp else 18.dp,
                    top = 9.dp,
                    bottom = 9.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color.White else NyoraTokens.onSurfaceBody,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
            if (manageable) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        contentDescription = "Manage collection",
                        tint = if (selected) Color.White.copy(alpha = 0.85f) else NyoraTokens.onSurfaceFaint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Rename / Delete management menu, themed onto a glass surface.
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier
                .background(NyoraTokens.surface1)
                .border(1.dp, NyoraTokens.hairlineFaint, RoundedCornerShape(14.dp)),
        ) {
            DropdownMenuItem(
                text = { Text("Rename", color = NyoraTokens.onSurfaceHigh) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.DriveFileRenameOutline,
                        contentDescription = null,
                        tint = NyoraTokens.onSurfaceBody,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = { menuOpen = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = NyoraTokens.crimson) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = NyoraTokens.crimson,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

@Composable
private fun NewCategoryChip(onClick: () -> Unit) {
    val accent = LocalNyoraAccent.current.color
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(accent.copy(alpha = 0.10f))
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.55f),
                shape = shape,
            )
            .hoverLift(shape = shape, scaleTo = 1.05f, glowColor = accent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "New",
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ── Empty state ─────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyLibraryState() {
    val accent = LocalNyoraAccent.current.color
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .glassCard(shape = RoundedCornerShape(28.dp), fill = NyoraTokens.surface1)
                .padding(horizontal = 48.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.FavoriteBorder,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            SystemTag(text = "Nothing here yet")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your library is empty",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = NyoraTokens.onSurfaceHigh,
                letterSpacing = (-0.4).sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Heart a manga and it will land here.",
                style = MaterialTheme.typography.bodyMedium,
                color = NyoraTokens.onSurfaceMuted,
            )
        }
    }
}

// ── Favourite cover card ────────────────────────────────────────────────────────────

@Composable
private fun FavouriteBentoCard(manga: Manga, coverUrl: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        AnimeAsyncImage(
            model = coverUrl.ifBlank { null },
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .hoverLift(shape = RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = manga.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = NyoraTokens.onSurfaceHigh,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

// ── Shared category-name dialog (create + rename) ────────────────────────────────────

@Composable
private fun CategoryNameDialog(
    heading: String,
    eyebrow: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val accent = LocalNyoraAccent.current.color
    var name by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NyoraTokens.surface1,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                SystemTag(text = eyebrow)
                Spacer(Modifier.height(6.dp))
                Text(
                    heading,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = NyoraTokens.onSurfaceHigh,
                    letterSpacing = (-0.4).sp,
                )
            }
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Collection name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    cursorColor = accent,
                    focusedLabelColor = accent,
                    focusedTextColor = NyoraTokens.onSurfaceHigh,
                    unfocusedTextColor = NyoraTokens.onSurfaceBody,
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
            ) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NyoraTokens.onSurfaceMuted)
            }
        },
    )
}
