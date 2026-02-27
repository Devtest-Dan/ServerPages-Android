package dev.serverpages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BgColor = Color(0xFF0D0D0D)
private val SurfaceColor = Color(0xFF1A1A1A)
private val TextColor = Color(0xFFE0E0E0)
private val TextMuted = Color(0xFF888888)
private val AccentColor = Color(0xFF4FC3F7)
private val DangerColor = Color(0xFFEF5350)
private val GreenColor = Color(0xFF66BB6A)
private val OrangeColor = Color(0xFFFFA726)

@Composable
fun MainScreen(state: ServiceState, onContentMode: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    when {
                        state.capturing -> GreenColor
                        state.serverRunning -> AccentColor
                        else -> DangerColor
                    }
                )
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "ServerPages",
            color = AccentColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = when {
                state.capturing -> "LIVE"
                state.serverRunning -> "Server Running"
                else -> "Starting..."
            },
            color = TextColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        if (state.serverRunning) {
            Spacer(Modifier.height(24.dp))

            // Access code card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "VIEWER CODE",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.accessCode,
                        color = OrangeColor,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Share this code with viewers",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Viewer count
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "${state.viewerCount}",
                        color = if (state.viewerCount > 0) GreenColor else TextMuted,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.viewerCount == 1) "viewer watching" else "viewers watching",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Server URL
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF111111),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.serverUrl,
                    color = AccentColor,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(12.dp),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Quality: ${state.quality}",
                color = TextMuted,
                fontSize = 13.sp
            )

            if (state.tailscaleUrl.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF111111),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Tailscale",
                            color = GreenColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = state.tailscaleUrl,
                            color = AccentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Content Mode button
            Button(
                onClick = onContentMode,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentColor,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Content Mode",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Play media files full-screen for viewers",
                color = TextMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "For 10+ viewers use computer",
                color = OrangeColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }

        if (!state.serverRunning && !state.capturing) {
            Spacer(Modifier.height(24.dp))

            Text(
                text = "Grant permissions to start automatically...",
                color = TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
