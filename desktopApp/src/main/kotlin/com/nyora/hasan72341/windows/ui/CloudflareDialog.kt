package com.nyora.windows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nyora.windows.AppState

/**
 * Shown when a source returns a Cloudflare challenge. The in-process engine can't
 * run the browser challenge, so the user pastes a `cf_clearance` cookie (from their
 * own browser's devtools); [AppState.applyCloudflareCookie] injects it into the
 * shared OkHttp jar and re-runs the failed operation.
 */
@Composable
fun CloudflareDialog(state: AppState) {
    val host = state.cloudflareHost ?: return
    var cookie by remember(host) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { state.dismissCloudflare() },
        title = { Text("Cloudflare challenge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$host is protected by Cloudflare and Nyora can't run the challenge in-app.")
                Text(
                    "1.  Open https://$host/ in your browser and pass the check.\n" +
                        "2.  DevTools → Application/Storage → Cookies → copy the cf_clearance " +
                        "value (or the whole Cookie header).\n" +
                        "3.  Paste it below — it'll be reused for every request to this host.",
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { state.applyCloudflareCookie(cookie) },
                enabled = cookie.isNotBlank(),
            ) { Text("Apply & retry") }
        },
        dismissButton = {
            TextButton(onClick = { state.dismissCloudflare() }) { Text("Cancel") }
        },
    )
}
