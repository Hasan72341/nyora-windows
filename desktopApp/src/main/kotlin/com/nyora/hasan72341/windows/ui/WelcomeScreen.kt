package com.nyora.windows.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraTokens

/** First-run start page: brand, a line of intent, and the two ways in —
 *  sign in (carry your library across devices) or continue as a guest. */
@Composable
fun WelcomeScreen(state: AppState) {
    val accent = LocalNyoraAccent.current.color
    val busy = state.cloudSyncBusy
    // Keep the welcome up while Google sign-in runs; dismiss only once it succeeds.
    val authed = state.cloudSyncStatus?.isAuthenticated == true
    LaunchedEffect(Unit) { state.refreshCloudSyncStatus() }
    LaunchedEffect(authed) { if (authed) state.finishOnboarding() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B0707), NyoraTokens.bg, Color.Black))),
        contentAlignment = Alignment.Center,
    ) {
        // soft accent wash bleeding down from the top edge
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(320.dp)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.12f), Color.Transparent))),
        )
        Column(
            modifier = Modifier.widthIn(max = 430.dp).padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource("nyora_logo.png"),
                contentDescription = "Nyora",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                "破壊 · MANGA, UNINTERRUPTED",
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.5.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "NYORA",
                color = NyoraTokens.onSurfaceHigh,
                fontSize = 60.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Read like the world can wait.",
                color = NyoraTokens.onSurfaceHigh,
                fontSize = 23.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                lineHeight = 31.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Your whole library — synced, private, and yours. Sign in to carry your shelf across every device, or dive straight in.",
                color = NyoraTokens.onSurfaceMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(28.dp))
            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { state.cloudSignIn(email, password) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
            ) {
                Text(if (busy) "Signing in…" else "Sign in", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { state.cloudRegister(email, password) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Create account")
            }
            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = { state.finishOnboarding() },
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Text("Continue as guest", color = NyoraTokens.onSurfaceMuted, fontSize = 14.sp)
            }
        }
    }
}
