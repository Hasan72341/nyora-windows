package com.nyora.windows.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.nyora.windows.AppState
import com.nyora.windows.NavDest
import com.nyora.windows.ui.screen.BookmarksScreen
import com.nyora.windows.ui.screen.CatalogSheet
import com.nyora.windows.ui.screen.DetailsScreen
import com.nyora.windows.ui.screen.DownloadsScreen
import com.nyora.windows.ui.screen.ExploreScreen
import com.nyora.windows.ui.screen.FavouritesScreen
import com.nyora.windows.ui.screen.GlobalSearchScreen
import com.nyora.windows.ui.screen.HistoryScreen
import com.nyora.windows.ui.screen.LocalFilesScreen
import com.nyora.windows.ui.screen.ReaderScreen
import com.nyora.windows.ui.screen.SettingsScreen
import com.nyora.windows.ui.screen.StatsScreen
import com.nyora.windows.ui.screen.SuggestionsScreen
import com.nyora.windows.ui.screen.UpdatesScreen
import com.nyora.windows.ui.theme.NyoraTheme
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.ambientGlow

@Composable
fun App(state: AppState) {
    NyoraTheme(appearance = state.appearance, accent = state.accent) {
        // First-run start page gates the whole app until the user signs in or skips.
        if (state.showWelcome) {
            WelcomeScreen(state)
            return@NyoraTheme
        }
        Box(
            modifier = Modifier.fillMaxSize().onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    // Ctrl+Shift+F → global search
                    event.key == Key.F && event.isCtrlPressed && event.isShiftPressed -> {
                        state.showGlobalSearch = true; true
                    }
                    // Ctrl+Comma → open Settings
                    event.key == Key.Comma && event.isCtrlPressed -> {
                        state.destination = NavDest.SETTINGS; true
                    }
                    // Escape → close overlays
                    event.key == Key.Escape -> {
                        when {
                            state.showGlobalSearch -> { state.showGlobalSearch = false; true }
                            state.showCatalog      -> { state.showCatalog = false; true }
                            state.showReader       -> { state.closeReader(); true }
                            state.showDetails      -> { state.showDetails = false; true }
                            else -> false
                        }
                    }
                    else -> false
                }
            },
        ) {
            when {
                state.showReader       -> ReaderScreen(state)
                state.showGlobalSearch -> GlobalSearchScreen(state)
                state.showCatalog      -> CatalogSheet(state)
                state.showDetails      -> DetailsScreen(state)
                else                   -> MainLayout(state)
            }

            // Snackbar status banner
            state.statusMessage?.let { msg ->
                Box(Modifier.fillMaxSize().padding(bottom = 16.dp), contentAlignment = Alignment.BottomCenter) {
                    Snackbar { Text(msg) }
                }
            }

            // Content & language preferences re-run (Settings ▸ "Re-run setup"),
            // overlays everything.
            if (state.showPreferences) {
                PreferencesOverlay(state)
            }

            // Cloudflare manual-clearance dialog (overlays everything, incl. the reader)
            if (state.cloudflareHost != null) {
                CloudflareDialog(state)
            }
        }
    }
}

@Composable
private fun MainLayout(state: AppState) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 900.dp
        val sidebarWidth = if (isCompact) 80.dp else 248.dp

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = NyoraTokens.bg
        ) {
            MainContent(state, isCompact, sidebarWidth)
        }
    }
}

@Composable
private fun MainContent(state: AppState, isCompact: Boolean, sidebarWidth: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxSize()) {
        // Screen Content (Background layer)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = sidebarWidth) // Leave space for the floating sidebar
        ) {
            when (state.destination) {
                NavDest.EXPLORE -> ExploreScreen(state)
                NavDest.FAVOURITES -> FavouritesScreen(state)
                NavDest.HISTORY -> HistoryScreen(state)
                NavDest.BOOKMARKS -> BookmarksScreen(state)
                NavDest.UPDATES -> UpdatesScreen(state)
                NavDest.LOCAL_FILES -> LocalFilesScreen(state)
                NavDest.DOWNLOADS -> DownloadsScreen(state)
                NavDest.SETTINGS -> SettingsScreen(state)
                NavDest.STATS -> StatsScreen(state)
                NavDest.SUGGESTIONS -> SuggestionsScreen(state)
            }
        }

        // Signature ambient accent glow — ONE soft radial over the content area (below the
        // sidebar). Empty, non-interactive overlay so clicks pass through; restores the
        // luminous "glow bleed" without the old square-corner seam.
        Box(Modifier.fillMaxSize().padding(start = sidebarWidth).ambientGlow(0.05f))

        // Floating Sidebar (Foreground layer)
        NyoraSidebar(
            current = state.destination,
            onSelect = {
                state.destination = it
                when (it) {
                    NavDest.DOWNLOADS -> state.loadDownloads()
                    NavDest.FAVOURITES -> state.loadCategories()
                    NavDest.STATS -> state.loadStats()
                    NavDest.SUGGESTIONS -> state.loadSuggestions()
                    else -> Unit
                }
            },
            onSearch = { state.showGlobalSearch = true },
            isCompact = isCompact
        )
    }
}
