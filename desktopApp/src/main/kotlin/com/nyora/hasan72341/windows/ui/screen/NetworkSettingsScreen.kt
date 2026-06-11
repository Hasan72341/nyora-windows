package com.nyora.windows.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nyora.windows.AppState
import com.nyora.windows.bridge.NetworkSettingsDto
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.accentGradient
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag

// ---------------------------------------------------------------------------
// NetworkSettingsScreen — proxy & connectivity configuration panel.
//
// Consumes AppState.networkSettings / loadNetworkSettings() /
// saveNetworkSettings(dto). Keeps a local `draft` copy (re-initialised when
// state.networkSettings changes) so the user can edit freely before saving.
// ---------------------------------------------------------------------------

private val PROXY_TYPES         = listOf("direct", "http", "socks5")
private val DOH_OPTIONS         = listOf("none", "cloudflare", "google", "adguard", "quad9", "mullvad")
private val IMAGE_PROXY_OPTIONS = listOf("none", "weserv", "statically")
private val MIRROR_OPTIONS      = listOf(
    "KEIYOUSHI",
    "KEIYOUSHI_GITHUB",
    "RAWKUMA",
    "MANGADEX",
    "NONE",
)

@Composable
fun NetworkSettingsScreen(state: AppState, onBack: () -> Unit) {
    LaunchedEffect(Unit) { state.loadNetworkSettings() }

    // Local editable draft — re-seeded whenever the loaded settings change.
    var draft by remember(state.networkSettings) {
        mutableStateOf(state.networkSettings ?: NetworkSettingsDto())
    }

    val accent   = LocalNyoraAccent.current.color
    val gradient = accentGradient()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = NyoraTokens.onSurfaceBody,
                )
            }
            Spacer(Modifier.width(8.dp))
            SectionHeader(title = "Network", subtitle = "Proxy & connectivity")
        }

        // ── Content ──────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {

            // ── 1. Proxy ─────────────────────────────────────────────────────
            NetworkGroup(tag = "Proxy", icon = { Icon(Icons.Default.Http, null, modifier = Modifier.size(15.dp), tint = accent) }) {
                // Proxy type — segmented buttons
                SettingsRow(label = "Proxy type") {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.width(260.dp)) {
                        PROXY_TYPES.forEachIndexed { i, type ->
                            SegmentedButton(
                                selected = draft.proxyType == type,
                                onClick = { draft = draft.copy(proxyType = type) },
                                shape = SegmentedButtonDefaults.itemShape(i, PROXY_TYPES.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = accent.copy(alpha = 0.18f),
                                    activeContentColor = accent,
                                    activeBorderColor = accent.copy(alpha = 0.45f),
                                    inactiveContainerColor = NyoraTokens.surface1,
                                    inactiveContentColor = NyoraTokens.onSurfaceMuted,
                                    inactiveBorderColor = NyoraTokens.hairlineFaint,
                                ),
                                label = { Text(type, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) },
                            )
                        }
                    }
                }

                HorizontalDivider(color = NyoraTokens.hairlineFaint, modifier = Modifier.padding(vertical = 4.dp))

                // Proxy address
                SettingsRow(label = "Proxy address") {
                    OutlinedTextField(
                        value = draft.proxyAddress,
                        onValueChange = { draft = draft.copy(proxyAddress = it) },
                        enabled = draft.proxyType != "direct",
                        singleLine = true,
                        placeholder = { Text("127.0.0.1", color = NyoraTokens.onSurfaceFaint) },
                        colors = nyoraTextFieldColors(accent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.width(260.dp),
                    )
                }

                HorizontalDivider(color = NyoraTokens.hairlineFaint, modifier = Modifier.padding(vertical = 4.dp))

                // Proxy port
                SettingsRow(label = "Proxy port") {
                    OutlinedTextField(
                        value = if (draft.proxyPort == 0) "" else draft.proxyPort.toString(),
                        onValueChange = { v ->
                            val n = v.filter { it.isDigit() }.take(5)
                            draft = draft.copy(proxyPort = n.toIntOrNull() ?: 0)
                        },
                        enabled = draft.proxyType != "direct",
                        singleLine = true,
                        placeholder = { Text("8080", color = NyoraTokens.onSurfaceFaint) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = nyoraTextFieldColors(accent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.width(140.dp),
                    )
                }
            }

            // ── 2. DNS ───────────────────────────────────────────────────────
            NetworkGroup(tag = "DNS", icon = { Icon(Icons.Default.Dns, null, modifier = Modifier.size(15.dp), tint = accent) }) {
                SettingsRow(label = "DNS over HTTPS") {
                    NyoraDropdown(
                        options = DOH_OPTIONS,
                        selected = draft.dnsOverHttps,
                        onSelect = { draft = draft.copy(dnsOverHttps = it) },
                        accent = accent,
                    )
                }
            }

            // ── 3. Sources ───────────────────────────────────────────────────
            NetworkGroup(tag = "Sources", icon = { Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(15.dp), tint = accent) }) {
                SettingsRow(label = "GitHub mirror") {
                    NyoraDropdown(
                        options = MIRROR_OPTIONS,
                        selected = draft.githubMirror,
                        onSelect = { draft = draft.copy(githubMirror = it) },
                        accent = accent,
                    )
                }
            }

            // ── 4. Images proxy ──────────────────────────────────────────────
            NetworkGroup(tag = "Images", icon = { Icon(Icons.Default.Image, null, modifier = Modifier.size(15.dp), tint = accent) }) {
                SettingsRow(label = "Images proxy") {
                    NyoraDropdown(
                        options = IMAGE_PROXY_OPTIONS,
                        selected = draft.imagesProxy.takeIf { it in IMAGE_PROXY_OPTIONS } ?: "none",
                        onSelect = { draft = draft.copy(imagesProxy = it) },
                        accent = accent,
                    )
                }
            }

            // ── 5. Security ──────────────────────────────────────────────────
            NetworkGroup(tag = "Security", icon = { Icon(Icons.Default.Shield, null, modifier = Modifier.size(15.dp), tint = accent) }) {
                SettingsSwitch(
                    label = "Ad/Tracker blocking",
                    sublabel = "Block known ad and tracker domains at the network layer.",
                    checked = draft.adTrackerBlocking,
                    onToggle = { draft = draft.copy(adTrackerBlocking = it) },
                    accent = accent,
                )

                HorizontalDivider(color = NyoraTokens.hairlineFaint, modifier = Modifier.padding(vertical = 4.dp))

                SettingsSwitch(
                    label = "Ignore SSL errors",
                    sublabel = "Bypass TLS certificate errors. Use only for local or self-signed servers.",
                    checked = draft.ignoreSSLErrors,
                    onToggle = { draft = draft.copy(ignoreSSLErrors = it) },
                    accent = accent,
                )

                HorizontalDivider(color = NyoraTokens.hairlineFaint, modifier = Modifier.padding(vertical = 4.dp))

                SettingsSwitch(
                    label = "SSL bypass (danger zone)",
                    sublabel = "Disables TLS certificate verification. Use only for local dev servers.",
                    checked = draft.sslBypass,
                    onToggle = { draft = draft.copy(sslBypass = it) },
                    accent = accent,
                )

                HorizontalDivider(color = NyoraTokens.hairlineFaint, modifier = Modifier.padding(vertical = 4.dp))

                SettingsSwitch(
                    label = "Disable connectivity check",
                    sublabel = "Skip the background network reachability probe on startup.",
                    checked = draft.disableConnectivityCheck,
                    onToggle = { draft = draft.copy(disableConnectivityCheck = it) },
                    accent = accent,
                )
            }

            // ── Save button ──────────────────────────────────────────────────
            val saveShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(saveShape)
                    .background(gradient)
                    .clickable {
                        state.saveNetworkSettings(draft)
                        onBack()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                    Text(
                        text = "Save",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Section group — glassCard with a SystemTag eyebrow and icon
// ---------------------------------------------------------------------------

@Composable
private fun NetworkGroup(
    tag: String,
    icon: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon()
            SystemTag(text = tag)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(shape = RoundedCornerShape(20.dp), fill = NyoraTokens.surface1)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Row helpers
// ---------------------------------------------------------------------------

@Composable
private fun SettingsRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = NyoraTokens.onSurfaceBody,
        )
        trailing()
    }
}

@Composable
private fun SettingsSwitch(
    label: String,
    sublabel: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    accent: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = NyoraTokens.onSurfaceBody,
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.bodySmall,
                color = NyoraTokens.onSurfaceMuted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NyoraTokens.onSurfaceHigh,
                checkedTrackColor = accent,
                uncheckedThumbColor = NyoraTokens.onSurfaceMuted,
                uncheckedTrackColor = NyoraTokens.surface1,
                uncheckedBorderColor = NyoraTokens.hairlineFaint,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Minimal dropdown
// ---------------------------------------------------------------------------

@Composable
private fun NyoraDropdown(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    accent: androidx.compose.ui.graphics.Color,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = NyoraTokens.surface1,
                contentColor = NyoraTokens.onSurfaceBody,
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = if (expanded) accent.copy(alpha = 0.45f) else NyoraTokens.hairlineFaint,
            ),
            modifier = Modifier.widthIn(min = 160.dp),
        ) {
            Text(
                text = selected,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = NyoraTokens.onSurfaceBody,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = NyoraTokens.onSurfaceMuted,
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (option == selected) accent else NyoraTokens.onSurfaceBody,
                            fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// TextField color preset aligned to Nyora design tokens
// ---------------------------------------------------------------------------

@Composable
private fun nyoraTextFieldColors(accent: androidx.compose.ui.graphics.Color) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = accent.copy(alpha = 0.6f),
        unfocusedBorderColor = NyoraTokens.hairlineFaint,
        disabledBorderColor = NyoraTokens.glass2,
        focusedTextColor = NyoraTokens.onSurfaceHigh,
        unfocusedTextColor = NyoraTokens.onSurfaceBody,
        disabledTextColor = NyoraTokens.onSurfaceFaint,
        cursorColor = accent,
        focusedContainerColor = NyoraTokens.surface1,
        unfocusedContainerColor = NyoraTokens.surface1,
        disabledContainerColor = NyoraTokens.surface1,
        focusedLabelColor = accent,
        unfocusedLabelColor = NyoraTokens.onSurfaceMuted,
        disabledLabelColor = NyoraTokens.onSurfaceFaint,
        focusedPlaceholderColor = NyoraTokens.onSurfaceFaint,
        unfocusedPlaceholderColor = NyoraTokens.onSurfaceFaint,
        disabledPlaceholderColor = NyoraTokens.onSurfaceFaint,
    )
