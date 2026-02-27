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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Dark theme colors matching the web CSS
private val BgColor = Color(0xFF0D0D0D)
private val SurfaceColor = Color(0xFF1A1A1A)
private val BorderColor = Color(0xFF333333)
private val TextColor = Color(0xFFE0E0E0)
private val TextMuted = Color(0xFF888888)
private val AccentColor = Color(0xFF4FC3F7)
private val DangerColor = Color(0xFFEF5350)
private val GreenColor = Color(0xFF66BB6A)

@Composable
fun MainScreen(
    state: ServiceState,
    onStartServer: () -> Unit,
    onStartCapture: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // Title
        Text(
            text = "ServerPages",
            color = AccentColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Screen Broadcaster + Media Server",
            color = TextMuted,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(48.dp))

        // Status card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = SurfaceColor,
            border = ButtonDefaults.outlinedButtonBorder.copy(
                width = 1.dp,
            ).let { null },
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Status row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    state.capturing -> GreenColor
                                    state.serverRunning -> AccentColor
                                    else -> DangerColor
                                }
                            )
                    )

                    Text(
                        text = when {
                            state.capturing -> "LIVE — Capturing Screen"
                            state.serverRunning -> "Server Running (No Capture)"
                            else -> "Stopped"
                        },
                        color = TextColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (state.serverRunning) {
                    Spacer(Modifier.height(16.dp))

                    // URL display
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
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Open this URL in any browser on your network",
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    // Quality indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "Quality:", color = TextMuted, fontSize = 13.sp)
                        Text(
                            text = state.quality,
                            color = TextColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        // Action buttons
        if (!state.serverRunning) {
            // Start server only (no capture)
            Button(
                onClick = onStartServer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
            ) {
                Text("Start Server", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(12.dp))
        }

        if (!state.capturing) {
            Button(
                onClick = onStartCapture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.serverRunning) GreenColor else AccentColor
                )
            ) {
                Text(
                    if (state.serverRunning) "Enable Screen Capture" else "Start with Capture",
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (state.serverRunning || state.capturing) {
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerColor)
            ) {
                Text("Stop Everything", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.weight(1f))

        // Footer
        Text(
            text = "Port ${ "3333" } • Media browser works without capture",
            color = TextMuted,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(8.dp))
    }
}
