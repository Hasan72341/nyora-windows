package com.nyora.windows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.multiplatform.webview.cookie.WebViewCookieManager
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewState
import com.nyora.windows.AppState
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The exact User-Agent the local helper sends (`NYORA_BROWSER_UA`). The embedded
 * WebView MUST use this byte-for-byte, because a `cf_clearance` cookie is bound to
 * the UA (and IP) that solved the challenge — solve it under a different UA and the
 * helper's subsequent requests are rejected.
 */
private const val HELPER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"

private enum class EnginePhase { PREPARING, READY, FAILED }

/**
 * Process-wide KCEF/Chromium bootstrap. `KCEF.init` may only run once per process,
 * so the state lives in a singleton the dialog observes. On first ever run KCEF
 * downloads a Chromium bundle (~100 MB) into the Nyora config dir; progress is
 * surfaced via [status]. Any failure flips [phase] to FAILED and the dialog falls
 * back to the manual paste flow.
 */
private object CloudflareBrowserEngine {
    var phase by mutableStateOf(EnginePhase.PREPARING)
        private set
    var status by mutableStateOf("Preparing browser…")
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var started = false

    private fun installDir(): File {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        val home = System.getProperty("user.home").orEmpty()
        val base = if (os.contains("mac") || os.contains("darwin")) {
            File(home, "Library/Application Support/Nyora")
        } else {
            val xdg = System.getenv("XDG_CONFIG_HOME")
            if (!xdg.isNullOrBlank()) File(xdg, "nyora") else File(home, ".config/nyora")
        }
        return File(base, "kcef-bundle")
    }

    /** Idempotent: initializes KCEF at most once per process. Safe to call on every
     *  dialog open. */
    suspend fun ensureInitialized() {
        if (started) return
        started = true
        phase = EnginePhase.PREPARING
        status = "Preparing browser…"
        errorMessage = null
        try {
            withContext(Dispatchers.IO) {
                val dir = installDir().apply { mkdirs() }
                KCEF.init(
                    builder = {
                        installDir(dir)
                        progress {
                            onDownloading { pct ->
                                status = "Downloading browser… ${pct.toInt().coerceIn(0, 100)}%"
                            }
                            onExtracting { status = "Extracting browser…" }
                            onInitializing { status = "Starting browser…" }
                            onInitialized { status = "Ready." }
                        }
                        settings {
                            cachePath = File(dir, "cache").absolutePath
                            persistSessionCookies = true
                        }
                    },
                    onError = { t ->
                        errorMessage = t?.message ?: "Browser engine failed to initialize."
                        phase = EnginePhase.FAILED
                    },
                    onRestartRequired = {
                        errorMessage = "Restart Nyora to finish preparing the in-app browser."
                        phase = EnginePhase.FAILED
                    },
                )
            }
            if (phase != EnginePhase.FAILED) phase = EnginePhase.READY
        } catch (t: Throwable) {
            errorMessage = t.message ?: "Browser engine failed to initialize."
            phase = EnginePhase.FAILED
        }
    }

    /** Allow a fresh init attempt after a failure. */
    fun retry() {
        if (phase == EnginePhase.FAILED) {
            started = false
            phase = EnginePhase.PREPARING
        }
    }
}

/**
 * Shown when a source returns a Cloudflare challenge. The in-process parser engine
 * can't run the browser challenge, so we load `https://<host>/` in an embedded
 * Chromium WebView (KCEF) using the SAME User-Agent the helper sends. The user (or
 * Cloudflare's passive JS) solves the challenge; we poll the browser cookie store,
 * and once `cf_clearance` appears we capture the FULL cookie set for the host and
 * hand it to [AppState.applyCloudflareCookie], which injects it into the shared
 * OkHttp jar and re-runs the failed operation.
 *
 * If the browser engine fails to initialize the dialog degrades to a manual
 * paste-from-devtools flow so the feature never becomes a dead end.
 */
@Composable
fun CloudflareDialog(state: AppState) {
    val host = state.cloudflareHost ?: return

    DialogWindow(
        onCloseRequest = { state.dismissCloudflare() },
        state = rememberDialogState(size = DpSize(560.dp, 740.dp)),
        title = "Verify you're human — $host",
    ) {
        CloudflareSolverContent(state, host)
    }
}

@Composable
private fun CloudflareSolverContent(state: AppState, host: String) {
    val engine = CloudflareBrowserEngine
    var manualMode by remember(host) { mutableStateOf(false) }

    LaunchedEffect(host) { engine.ensureInitialized() }
    // A failed engine drops straight to the manual paste flow.
    LaunchedEffect(engine.phase) {
        if (engine.phase == EnginePhase.FAILED) manualMode = true
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$host is protected by Cloudflare. Complete the check below — this window " +
                "closes automatically once you pass, and the clearance is reused for every " +
                "request to this host.",
            style = MaterialTheme.typography.bodySmall,
        )

        when {
            manualMode -> ManualCookiePanel(
                state = state,
                host = host,
                engineError = engine.errorMessage,
                canUseBrowser = engine.phase == EnginePhase.READY,
                onUseBrowser = { manualMode = false },
            )
            engine.phase == EnginePhase.READY -> BrowserPanel(
                state = state,
                host = host,
                onManual = { manualMode = true },
            )
            else -> PreparingPanel(status = engine.status)
        }

        TextButton(
            onClick = { state.dismissCloudflare() },
            modifier = Modifier.align(Alignment.End),
        ) { Text("Cancel") }
    }
}

@Composable
private fun ColumnScope.BrowserPanel(state: AppState, host: String, onManual: () -> Unit) {
    val webViewState = rememberWebViewState("https://$host/")
    // cf_clearance is UA-bound — force the exact helper UA before the browser loads.
    webViewState.webSettings.apply {
        isJavaScriptEnabled = true
        customUserAgentString = HELPER_UA
    }
    val cookieManager = remember { WebViewCookieManager() }

    // Poll the browser cookie store; once cf_clearance is present, hand the FULL
    // cookie set for the host to AppState (which injects + retries, then clears
    // cloudflareHost — closing this dialog on the next recomposition).
    LaunchedEffect(host) {
        while (true) {
            delay(800)
            val cookies = runCatching { cookieManager.getCookies("https://$host/") }
                .getOrNull().orEmpty()
            if (cookies.any { it.name == "cf_clearance" }) {
                val header = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                state.applyCloudflareCookie(header)
                break
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        WebView(state = webViewState, modifier = Modifier.fillMaxSize())
    }
    TextButton(onClick = onManual) { Text("Trouble passing? Paste a cookie manually") }
}

@Composable
private fun ColumnScope.PreparingPanel(status: String) {
    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "First run downloads a small browser engine — this happens only once.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ColumnScope.ManualCookiePanel(
    state: AppState,
    host: String,
    engineError: String?,
    canUseBrowser: Boolean,
    onUseBrowser: () -> Unit,
) {
    var cookie by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (engineError != null) {
            Text(
                "The in-app browser couldn't start ($engineError). Paste a cf_clearance " +
                    "cookie from your own browser instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "1.  Open https://$host/ in your browser and pass the check.\n" +
                "2.  DevTools → Application/Storage → Cookies → copy the cf_clearance " +
                "value (or the whole Cookie header).\n" +
                "3.  Paste it below.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = cookie,
            onValueChange = { cookie = it },
            label = { Text("cf_clearance=…   (or full Cookie header)") },
            singleLine = false,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        TextButton(
            onClick = { state.applyCloudflareCookie(cookie) },
            enabled = cookie.isNotBlank(),
        ) { Text("Apply & retry") }
        if (canUseBrowser) {
            TextButton(onClick = onUseBrowser) { Text("Use the in-app browser instead") }
        }
    }
}
