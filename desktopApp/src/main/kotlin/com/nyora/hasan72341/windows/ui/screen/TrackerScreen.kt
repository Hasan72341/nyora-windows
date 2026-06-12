package com.nyora.windows.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.bridge.AniListMediaDto
import com.nyora.windows.ui.theme.*

@Composable
fun TrackerScreen(state: AppState, onBack: () -> Unit) {
    val accent = LocalNyoraAccent.current.color
    var tokenInput by remember { mutableStateOf(state.anilistToken) }
    var showToken by remember { mutableStateOf(false) }

    val isLinked = tokenInput.isNotBlank()
    val mediaList = state.anilistResult?.data?.Page?.media ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier
                    .size(48.dp),
                color = NyoraTokens.surface1,
                shape = RoundedCornerShape(12.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = NyoraTokens.onSurfaceHigh,
                    )
                }
            }

            Column {
                SectionHeader(
                    title = "AniList",
                    subtitle = "Track your progress",
                )
            }

            Spacer(Modifier.weight(1f))

            // Linked status tag — mint if token set, muted otherwise
            SystemTag(
                text = if (isLinked) "Linked" else "Not linked",
                color = if (isLinked) NyoraTokens.mint else NyoraTokens.onSurfaceFaint,
            )
        }

        // ── Token entry glass panel ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(shape = RoundedCornerShape(20.dp), fill = NyoraTokens.surface1)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Access Token",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NyoraTokens.onSurfaceHigh,
                )
            }

            // Masked OutlinedTextField
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("AniList OAuth access token") },
                placeholder = { Text("Paste your bearer token here…", color = NyoraTokens.onSurfaceFaint) },
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                trailingIcon = {
                    TextButton(
                        onClick = { showToken = !showToken },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = if (showToken) "Hide" else "Show",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent,
                    unfocusedBorderColor = NyoraTokens.hairlineFaint,
                    focusedLabelColor = accent,
                    cursorColor = accent,
                ),
                shape = RoundedCornerShape(14.dp),
            )

            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { state.setAnilistToken(tokenInput) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Save Token", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { state.anilistSearch("Naruto") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    enabled = isLinked && !state.anilistLoading,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isLinked) accent.copy(alpha = 0.55f) else NyoraTokens.hairlineFaint,
                    ),
                ) {
                    if (state.anilistLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = accent,
                        )
                    } else {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isLinked) accent else NyoraTokens.onSurfaceMuted,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Test connection",
                        color = if (isLinked) accent else NyoraTokens.onSurfaceMuted,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Inline note about OAuth + auto-scrobble
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = NyoraTokens.surface1,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "Full OAuth web flow is planned for a future release. " +
                            "For now, paste an OAuth access token obtained from anilist.co/settings/developer. " +
                            "Once a token is saved, per-chapter scrobble fires automatically as you read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // ── Test results section ─────────────────────────────────────────────
        AnimatedVisibility(
            visible = state.anilistLoading || mediaList.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionHeader(
                        title = if (state.anilistLoading) "Searching…" else "Results",
                        subtitle = if (!state.anilistLoading && mediaList.isNotEmpty())
                            "${mediaList.size} title${if (mediaList.size != 1) "s" else ""} found"
                        else null,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.anilistLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = accent,
                        )
                    }
                }

                if (!state.anilistLoading) {
                    // Use a fixed-height lazy column so the outer scroll still works
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(mediaList) { media ->
                            AniListMediaRow(media = media, state = state)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}

// ----- Private composables -------------------------------------------------

@Composable
private fun AniListMediaRow(media: AniListMediaDto, state: AppState) {
    val accent = LocalNyoraAccent.current.color
    val displayTitle = media.title.english ?: media.title.romaji ?: media.title.native ?: "Unknown"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(shape = RoundedCornerShape(16.dp), fill = NyoraTokens.surface1)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cover thumbnail
        AnimeAsyncImage(
            model = media.coverImage.large,
            contentDescription = displayTitle,
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(0.72f),
            shape = RoundedCornerShape(10.dp),
        )

        // Title + metadata
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = NyoraTokens.onSurfaceHigh,
                maxLines = 2,
            )
            if (media.title.english != null && media.title.romaji != null) {
                Text(
                    text = media.title.romaji,
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                    maxLines = 1,
                )
            }
        }

        // Score + chapters badges
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            media.averageScore?.let { score ->
                Surface(
                    color = accent.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "${score / 10.0}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = accent,
                    )
                }
            }
            media.chapters?.let { ch ->
                Surface(
                    color = NyoraTokens.surface1,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "$ch ch",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = NyoraTokens.onSurfaceMuted,
                    )
                }
            }
        }
    }
}
