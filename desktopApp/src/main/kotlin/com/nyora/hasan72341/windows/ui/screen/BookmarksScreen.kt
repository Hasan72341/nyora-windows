package com.nyora.windows.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.nyora.hasan72341.shared.extension.MangaDetails
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.repository.BookmarkRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bookmarks — saved reader pages grouped per-manga, rendered as the signature
 * "Midnight Sakura" frosted-glass list. Each row resolves its source + chapter and
 * opens the reader landing on the bookmarked page; a pencil opens an inline note editor.
 */
@Composable
fun BookmarksScreen(state: AppState) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(24.dp))
        SectionHeader(title = "Bookmarks", subtitle = "Saved Pages")
        Spacer(Modifier.height(32.dp))

        if (state.bookmarks.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f)) { EmptyBookmarks() }
            return@Column
        }

        // Group by manga so each section reads as one curated card.
        val grouped = state.bookmarks.groupBy { it.mangaTitle.ifBlank { "Unknown" } }

        val listState = rememberLazyListState()
        NyoraScrollContainer(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                contentPadding = PaddingValues(bottom = 40.dp, end = 10.dp),
            ) {
                grouped.forEach { (mangaTitle, rows) ->
                    item(key = mangaTitle) {
                        BookmarkSection(mangaTitle, rows, state)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyBookmarks() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.MenuBook,
                contentDescription = null,
                tint = NyoraTokens.onSurfaceFaint,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "No bookmarks yet",
                color = NyoraTokens.onSurfaceMuted,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap the bookmark ribbon while reading to save a page here.",
                color = NyoraTokens.onSurfaceFaint,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BookmarkSection(
    title: String,
    bookmarks: List<BookmarkRow>,
    state: AppState,
) {
    val accent = LocalNyoraAccent.current.color
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                color = NyoraTokens.onSurfaceHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(12.dp))
            SystemTag(text = "${bookmarks.size} saved", color = accent)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            bookmarks.forEach { bookmark ->
                BookmarkRowCard(bookmark, state)
            }
        }
    }
}

@Composable
private fun BookmarkRowCard(row: BookmarkRow, state: AppState) {
    val shape = RoundedCornerShape(20.dp)
    var showNoteEditor by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverLift(shape = shape)
            .glassCard(shape = shape, fill = NyoraTokens.surface1)
            .clickable { openBookmark(state, row) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimeAsyncImage(
            model = state.coverProxyUrl(row.mangaCoverUrl, null),
            contentDescription = row.mangaTitle,
            modifier = Modifier
                .height(78.dp)
                .width(56.dp),
            shape = RoundedCornerShape(12.dp),
        )

        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = row.chapterTitle.ifBlank { "Chapter" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = NyoraTokens.onSurfaceHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SystemTag(text = "Page ${row.page + 1}")
            }
            if (row.note.isNotBlank()) {
                Text(
                    text = row.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Resume-into-reader beacon.
        IconButton(
            onClick = { openBookmark(state, row) },
            modifier = Modifier
                .size(40.dp)
                .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1),
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoStories,
                contentDescription = "Open at bookmark",
                tint = LocalNyoraAccent.current.color,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(8.dp))

        // Inline note editor trigger.
        IconButton(
            onClick = { showNoteEditor = true },
            modifier = Modifier
                .size(40.dp)
                .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1),
        ) {
            Icon(
                imageVector = Icons.Outlined.EditNote,
                contentDescription = "Edit note",
                tint = NyoraTokens.onSurfaceBody,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(8.dp))

        IconButton(
            onClick = { state.removeBookmark(row.id) },
            modifier = Modifier
                .size(40.dp)
                .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1),
        ) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Delete bookmark",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (showNoteEditor) {
        BookmarkNoteDialog(
            initial = row.note,
            onDismiss = { showNoteEditor = false },
            onSave = { note ->
                saveBookmarkNote(state, row, note)
                showNoteEditor = false
            },
        )
    }
}

@Composable
private fun BookmarkNoteDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NyoraTokens.surface1,
        titleContentColor = NyoraTokens.onSurfaceHigh,
        textContentColor = NyoraTokens.onSurfaceBody,
        title = { Text("Bookmark note", fontWeight = FontWeight.Black) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Add a note for this page…") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text("Save", color = LocalNyoraAccent.current.color, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NyoraTokens.onSurfaceMuted)
            }
        },
    )
}

// ── Behaviour helpers ───────────────────────────────────────────────────────────────

/**
 * Resolves the [Manga] (by id) + its source, locates the bookmarked [MangaChapter]
 * (fetching details if the cached manga has no chapters), opens the reader and seeks to
 * [BookmarkRow.page]. Falls back to opening details when the chapter cannot be resolved.
 */
private fun openBookmark(state: AppState, row: BookmarkRow) {
    state.scope.launch {
        val manga: Manga? = withContext(Dispatchers.IO) {
            state.facade.listMangas().firstOrNull { it.id == row.mangaId }
        }
        if (manga == null) {
            // Manga not in the local cache (e.g. removed) — nothing to open.
            return@launch
        }
        val source = state.sourceFor(manga)

        // Find the chapter in the cached manga; if chapters were never loaded, fetch
        // details (mirrors AppState.openDetails) so we can resolve it by id.
        var chapter: MangaChapter? = manga.chapters.firstOrNull { it.id == row.chapterId }
        var resolvedManga: Manga = manga
        if (chapter == null && source != null) {
            runCatching {
                val full = withContext(Dispatchers.IO) {
                    val svc = state.facade.openExtension(source)
                    val details: MangaDetails = svc.getDetails(manga.url)
                    val merged = details.manga.copy(chapters = details.chapters)
                    state.facade.upsertManga(merged)
                    merged
                }
                resolvedManga = full
                chapter = full.chapters.firstOrNull { it.id == row.chapterId }
            }
        }

        val ch = chapter
        if (ch == null) {
            // Could not resolve a concrete chapter — fall back to the details screen.
            state.openDetails(resolvedManga, source)
            return@launch
        }

        state.openChapter(resolvedManga, ch, source)

        // openChapter resets readerCurrentPage to 0 then restores the *history* page
        // asynchronously after pages load. Wait for pages, then seek to the bookmark.
        if (row.page > 0) {
            var waited = 0
            while (state.readerPages.isEmpty() && state.showReader && waited < 8000) {
                delay(50)
                waited += 50
            }
            if (state.showReader && state.readerChapter?.id == ch.id) {
                val target = row.page.coerceIn(0, (state.readerPages.size - 1).coerceAtLeast(0))
                state.recordReaderPage(target)
            }
        }
    }
}

/**
 * Persists a new note on the bookmark. The underlying `bookmark` table has a UNIQUE
 * index on (manga_id, chapter_id, page) and `addBookmark` issues INSERT OR REPLACE, so
 * re-adding with the same coordinates updates the note in place.
 */
private fun saveBookmarkNote(state: AppState, row: BookmarkRow, note: String) {
    state.scope.launch(Dispatchers.IO) {
        state.facade.addBookmark(row.mangaId, row.chapterId, row.chapterTitle, row.page, note)
        val refreshed = state.facade.bookmarks()
        withContext(Dispatchers.Main) { state.bookmarks = refreshed }
    }
}
