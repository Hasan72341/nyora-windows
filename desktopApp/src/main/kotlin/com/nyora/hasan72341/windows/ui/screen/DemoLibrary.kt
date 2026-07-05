package com.nyora.windows.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.glassCard

/**
 * Bundled, self-contained demo shown in Explore until a real source repository is
 * added. It's a small tour of original Nyora content — no network, no third-party
 * material — so the app is demonstrably functional (browse + read) with nothing
 * infringing shipped. Once a valid repository is added (Settings ▸ Source
 * repository), `repositoryActive` flips true and the real sources replace this.
 */

private data class DemoPage(val heading: String, val body: String)

private data class DemoManga(
    val title: String,
    val subtitle: String,
    val cover: String,   // a single glyph rendered on the generated cover
    val pages: List<DemoPage>,
)

private val DEMO_LIBRARY = listOf(
    DemoManga(
        title = "Welcome to Nyora",
        subtitle = "A quick tour",
        cover = "破",
        pages = listOf(
            DemoPage("Welcome", "Nyora is a clean, fast reader built for one thing: getting out of your way so you can read."),
            DemoPage("One quiet shelf", "Favourites, history, bookmarks and downloads all live in one place — and sync across your devices when you sign in."),
            DemoPage("No ads. Ever.", "Just your library and the page in front of you."),
        ),
    ),
    DemoManga(
        title = "Reader Features",
        subtitle = "Guide",
        cover = "読",
        pages = listOf(
            DemoPage("Reading modes", "Read paged, right-to-left, or as a continuous webtoon scroll — switch any time from the reader."),
            DemoPage("Gestures & keys", "The side zones (or arrow keys) turn pages; N and P jump between chapters; pinch or scroll to zoom."),
            DemoPage("Translate", "Turn on translation to read foreign pages in your language, in place."),
        ),
    ),
    DemoManga(
        title = "Add Your Sources",
        subtitle = "Guide",
        cover = "源",
        pages = listOf(
            DemoPage("Bring your own sources", "Nyora ships without any sources of its own. You add them by pointing the app at a source repository you trust."),
            DemoPage("How to add one", "Open Settings ▸ Source repository, paste your repository link, and choose Add. Your sources appear here in Explore."),
            DemoPage("That's it", "Once a repository is added, this demo steps aside and your real library takes over."),
        ),
    ),
)

@Composable
fun DemoLibraryView(state: AppState) {
    var open by remember { mutableStateOf<DemoManga?>(null) }
    val current = open
    if (current != null) {
        DemoReaderView(current) { open = null }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        SectionHeader(title = "Featured", subtitle = "A quick tour of Nyora")
        Spacer(Modifier.height(18.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(168.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(DEMO_LIBRARY) { m -> DemoCard(m) { open = m } }
        }
    }
}

@Composable
private fun DemoCard(m: DemoManga, onClick: () -> Unit) {
    val accent = LocalNyoraAccent.current.color
    Column(modifier = Modifier.width(168.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.35f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(m.cover, fontSize = 68.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.92f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            m.title, color = NyoraTokens.onSurfaceHigh, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(m.subtitle, color = NyoraTokens.onSurfaceFaint, fontSize = 12.sp)
    }
}

@Composable
private fun DemoReaderView(m: DemoManga, onBack: () -> Unit) {
    val accent = LocalNyoraAccent.current.color
    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar: back + title + DEMO badge
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape)
                    .background(NyoraTokens.surface2).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("←", color = NyoraTokens.onSurfaceHigh, fontSize = 17.sp) }
            Spacer(Modifier.width(14.dp))
            Text(m.title, color = NyoraTokens.onSurfaceHigh, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                    .background(accent.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp),
            ) { Text("DEMO", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            m.pages.forEachIndexed { i, page ->
                Column(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                        .glassCard(shape = RoundedCornerShape(20.dp), fill = NyoraTokens.surface1)
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("PAGE ${i + 1}", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(page.heading, color = NyoraTokens.onSurfaceHigh, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(page.body, color = NyoraTokens.onSurfaceMuted, fontSize = 15.sp, lineHeight = 23.sp)
                }
            }
            Text(
                "This is a demo. Add a source repository in Settings to read your own library.",
                color = NyoraTokens.onSurfaceFaint, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
