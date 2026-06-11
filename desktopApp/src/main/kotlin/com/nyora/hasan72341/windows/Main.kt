package com.nyora.windows

import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.nyora.windows.ui.App
import com.nyora.hasan72341.shared.NyoraFacade
import com.nyora.hasan72341.shared.data.ExtensionInstaller
import com.nyora.hasan72341.shared.data.SourceCatalogClient
import com.nyora.hasan72341.shared.extension.JvmExtensionRuntime
import com.nyora.hasan72341.shared.proxy.NyoraRestServer
import com.nyora.hasan72341.shared.reader.PageImageLoader
import com.nyora.hasan72341.shared.repository.JsonToSqlMigration
import com.nyora.hasan72341.shared.repository.SqlDelightLibraryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

fun main() {
    val repository = SqlDelightLibraryRepository()
    JsonToSqlMigration.runIfNeeded(repository)

    val facade = NyoraFacade(
        repository = repository,
        runtime = JvmExtensionRuntime(),
    )

    // Start the REST server in-process. The image proxy endpoint requires it,
    // and keeping the same HTTP surface means future CLI / remote clients work too.
    val server = NyoraRestServer(
        facade = facade,
        catalog = SourceCatalogClient(),
        installer = ExtensionInstaller(),
        pageLoader = PageImageLoader(),
    )
    val baseUrl = server.start()

    val appState = AppState(facade = facade, imageBaseUrl = baseUrl)

    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })

    application {
        // ── (1) Confirm-before-quit ───────────────────────────────────────────
        // showQuitDialog tracks whether the confirmation AlertDialog is visible.
        var showQuitDialog by remember { mutableStateOf(false) }

        Window(
            onCloseRequest = {
                if (appState.confirmBeforeQuit) {
                    // Don't exit immediately — ask first.
                    showQuitDialog = true
                } else {
                    exitApplication()
                }
            },
            title = "Nyora",
            state = WindowState(width = 1280.dp, height = 800.dp),
        ) {
            App(state = appState)

            // Confirmation dialog rendered inside the window's composition scope.
            if (showQuitDialog) {
                AlertDialog(
                    onDismissRequest = { showQuitDialog = false },
                    title   = { Text("Quit Nyora?") },
                    text    = { Text("Are you sure you want to quit?") },
                    confirmButton = {
                        Button(onClick = { showQuitDialog = false; exitApplication() }) {
                            Text("Quit")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showQuitDialog = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }

            // ── (2) Keep-screen-on heartbeat ──────────────────────────────────
            // While the reader is open and keepScreenOn is enabled, nudge the
            // mouse pointer by ±1 px every 30 s to prevent the OS screensaver /
            // power manager from activating. Wrapped in runCatching so it is a
            // complete no-op when java.awt.Robot is unavailable (headless, Wayland
            // without XWayland, container environments, etc.).
            val keepActive = appState.keepScreenOn && appState.showReader
            LaunchedEffect(keepActive) {
                if (!keepActive) return@LaunchedEffect
                runCatching {
                    val robot = java.awt.Robot()
                    while (isActive) {
                        delay(30_000L)
                        if (!isActive) break
                        runCatching {
                            val pos = java.awt.MouseInfo.getPointerInfo().location
                            robot.mouseMove(pos.x + 1, pos.y)
                            robot.mouseMove(pos.x,     pos.y)
                        }
                    }
                }
            }
        }
    }
}
