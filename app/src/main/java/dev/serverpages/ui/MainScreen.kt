package dev.serverpages.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.serverpages.server.ChatMessage
import dev.serverpages.server.CodeInfo
import dev.serverpages.server.ConversationSummary
import dev.serverpages.service.CaptureService

private val BgColor = Color(0xFF0D0D0D)
private val SurfaceColor = Color(0xFF1A1A1A)
private val TextColor = Color(0xFFE0E0E0)
private val TextMuted = Color(0xFF888888)
private val AccentColor = Color(0xFF4FC3F7)
private val DangerColor = Color(0xFFEF5350)
private val GreenColor = Color(0xFF66BB6A)
private val OrangeColor = Color(0xFFFFA726)
private val PurpleColor = Color(0xFFCE93D8)
private val StreamerBubble = Color(0xFF1565C0)
private val ViewerBubble = Color(0xFF2E2E2E)

private enum class Tab { STREAM, CODES, CHATS }

@Composable
fun MainScreen(
    state: ServiceState,
    conversations: List<ConversationSummary> = emptyList(),
    chatMessages: List<ChatMessage> = emptyList(),
    selectedCode: String? = null,
    onContentMode: () -> Unit = {},
    onSetupAction: () -> Unit = {},
    onToggleCamera: () -> Unit = {},
    onSelectConversation: (String?) -> Unit = {},
    onSendMessage: (String) -> Unit = {}
) {
    if (state.setupStep != SetupStep.DONE && !state.serverRunning) {
        SetupScreen(step = state.setupStep, onAction = onSetupAction)
    } else {
        var currentTab by remember { mutableStateOf(Tab.STREAM) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgColor)
        ) {
            // AirDeck header + status
            if (state.serverRunning) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    state.capturing -> GreenColor
                                    state.serverRunning -> AccentColor
                                    else -> DangerColor
                                }
                            )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(text = "AirDeck", color = AccentColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = when {
                            state.capturing -> "LIVE"
                            state.serverRunning -> "Server Running"
                            else -> "Starting..."
                        },
                        color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                }

                // Tab bar below header
                TabRow(
                    selectedTabIndex = currentTab.ordinal,
                    containerColor = SurfaceColor,
                    contentColor = AccentColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = currentTab == Tab.STREAM,
                        onClick = { currentTab = Tab.STREAM },
                        text = { Text("Stream", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = currentTab == Tab.CODES,
                        onClick = { currentTab = Tab.CODES },
                        text = { Text("Codes", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = currentTab == Tab.CHATS,
                        onClick = { currentTab = Tab.CHATS },
                        text = {
                            val unread = conversations.sumOf { it.messageCount }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Chats", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                if (unread > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Badge(containerColor = DangerColor) {
                                        Text("$unread", color = Color.White, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    )
                }
            }

            when (currentTab) {
                Tab.STREAM -> LiveScreen(
                    state = state,
                    onContentMode = onContentMode,
                    onToggleCamera = onToggleCamera
                )
                Tab.CODES -> CodesScreen(codes = state.codes)
                Tab.CHATS -> ChatsScreen(
                    conversations = conversations,
                    chatMessages = chatMessages,
                    selectedCode = selectedCode,
                    onSelectConversation = onSelectConversation,
                    onSendMessage = onSendMessage
                )
            }
        }
    }
}

// ─── Setup Screen ─────────────────────────────────────────────────────────────

@Composable
private fun SetupScreen(step: SetupStep, onAction: () -> Unit) {
    val (emoji, title, subtitle, buttonText, buttonColor) = when (step) {
        SetupStep.NOTIFICATIONS -> SetupContent(
            emoji = "\uD83D\uDD14",
            title = "Unlock the Signal",
            subtitle = "Let us whisper sweet notifications\nso you never miss a beat",
            buttonText = "Light It Up",
            buttonColor = AccentColor
        )
        SetupStep.CAPTURE -> SetupContent(
            emoji = "\uD83D\uDE80",
            title = "Go Live",
            subtitle = "One last thing — let us capture\nyour screen and beam it everywhere",
            buttonText = "Launch It",
            buttonColor = GreenColor
        )
        SetupStep.DONE -> return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = emoji, fontSize = 72.sp)
        Spacer(Modifier.height(24.dp))
        Text(
            text = title, color = TextColor, fontSize = 28.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = subtitle, color = TextMuted, fontSize = 15.sp,
            textAlign = TextAlign.Center, lineHeight = 22.sp
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor, contentColor = Color.Black),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(text = buttonText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(16.dp))
        val stepNum = when (step) {
            SetupStep.NOTIFICATIONS -> 1; SetupStep.CAPTURE -> 2; SetupStep.DONE -> 2
        }
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            for (i in 1..2) {
                Box(
                    modifier = Modifier
                        .size(if (i == stepNum) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (i <= stepNum) buttonColor else Color(0xFF333333))
                )
                if (i < 2) Spacer(Modifier.width(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text = "Step $stepNum of 2", color = Color(0xFF555555), fontSize = 12.sp)
    }
}

private data class SetupContent(
    val emoji: String, val title: String, val subtitle: String,
    val buttonText: String, val buttonColor: Color
)

// ─── Stream Tab (existing LiveScreen) ─────────────────────────────────────────

@Composable
private fun LiveScreen(state: ServiceState, onContentMode: () -> Unit, onToggleCamera: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.serverRunning) {
            // Compact access code card
            Surface(shape = RoundedCornerShape(8.dp), color = SurfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "VIEWER CODE", color = TextMuted, fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp
                    )
                    Text(
                        text = state.accessCode, color = OrangeColor, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 6.sp
                    )
                    Text(
                        text = "10 codes — see Codes tab",
                        color = TextMuted, fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Viewer count
            Surface(shape = RoundedCornerShape(8.dp), color = SurfaceColor, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "${state.viewerCount}", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = if (state.viewerCount > 0) GreenColor else TextMuted
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.viewerCount == 1) "viewer watching" else "viewers watching",
                        color = TextMuted, fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Server URL
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF111111), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = state.serverUrl, color = AccentColor, fontSize = 14.sp,
                    modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(text = "Quality: ${state.quality}", color = TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))

            // Camera toggle
            Button(
                onClick = onToggleCamera,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.capturing) DangerColor else GreenColor,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (state.capturing) "Stop Camera" else "Start Camera",
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                )
            }

            // Camera preview
            if (state.capturing) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "PREVIEW", color = TextMuted, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp), color = Color.Black,
                    modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f)
                ) {
                    DisposableEffect(Unit) {
                        onDispose { CaptureService.instance?.setPreviewSurface(null) }
                    }
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                        CaptureService.instance?.setPreviewSurface(Surface(st))
                                    }
                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                        CaptureService.instance?.setPreviewSurface(null)
                                        return true
                                    }
                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                    )
                }
            }

            if (state.publicUrl.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF111111), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "Public URL (Internet)", color = Color(0xFF42A5F5), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(text = state.publicUrl, color = AccentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (state.tailscaleUrl.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF111111), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "Tailscale", color = GreenColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(text = state.tailscaleUrl, color = AccentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onContentMode,
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Content Mode", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(text = "Play media files full-screen for viewers", color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(text = "For 10+ viewers use computer", color = OrangeColor, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }

        if (!state.serverRunning && !state.capturing) {
            Spacer(Modifier.height(24.dp))
            Text(text = "Grant permissions to start automatically...", color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

// ─── Codes Tab ────────────────────────────────────────────────────────────────

@Composable
private fun CodesScreen(codes: List<CodeInfo>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "ACCESS CODES",
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Text(
            text = "Share one code per viewer for private chat",
            color = TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        codes.forEach { codeInfo ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (codeInfo.isConnected) GreenColor else Color(0xFF444444))
                    )
                    Spacer(Modifier.width(12.dp))

                    // Code
                    Text(
                        text = codeInfo.code,
                        color = OrangeColor,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp
                    )

                    Spacer(Modifier.weight(1f))

                    // Label + status
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = codeInfo.label,
                            color = TextColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (codeInfo.isConnected) "Connected" else "Unused",
                            color = if (codeInfo.isConnected) GreenColor else TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

// ─── Chats Tab ────────────────────────────────────────────────────────────────

@Composable
private fun ChatsScreen(
    conversations: List<ConversationSummary>,
    chatMessages: List<ChatMessage>,
    selectedCode: String?,
    onSelectConversation: (String?) -> Unit,
    onSendMessage: (String) -> Unit
) {
    if (selectedCode == null) {
        // Conversation list
        ConversationListScreen(
            conversations = conversations,
            onSelect = onSelectConversation
        )
    } else {
        // Chat view
        ChatDetailScreen(
            code = selectedCode,
            conversations = conversations,
            messages = chatMessages,
            onBack = { onSelectConversation(null) },
            onSend = onSendMessage
        )
    }
}

@Composable
private fun ConversationListScreen(
    conversations: List<ConversationSummary>,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
    ) {
        Text(
            text = "CONVERSATIONS",
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(16.dp)
        )

        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "No conversations yet", color = TextMuted, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Viewers will appear here when they\nconnect with an access code",
                        color = Color(0xFF555555),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(conversations) { convo ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(convo.code) },
                        color = BgColor
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Avatar circle with code
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (convo.connected) AccentColor else Color(0xFF333333)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = convo.code.take(2),
                                    color = if (convo.connected) Color.Black else TextMuted,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = convo.label,
                                        color = TextColor,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = convo.code,
                                        color = OrangeColor,
                                        fontSize = 12.sp
                                    )
                                }
                                if (convo.lastMessage.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = convo.lastMessage,
                                        color = TextMuted,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (convo.messageCount > 0) {
                                Badge(containerColor = AccentColor) {
                                    Text("${convo.messageCount}", color = Color.Black, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF1A1A1A), thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
private fun ChatDetailScreen(
    code: String,
    conversations: List<ConversationSummary>,
    messages: List<ChatMessage>,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    val convo = conversations.find { it.code == code }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when messages change
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
    ) {
        // Header
        Surface(color = SurfaceColor, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "<",
                    color = AccentColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(end = 12.dp)
                )
                Column {
                    Text(
                        text = convo?.label ?: "Viewer",
                        color = TextColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Code: $code",
                        color = OrangeColor,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No messages yet",
                            color = TextMuted,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            items(messages) { msg ->
                val isStreamer = msg.from == "streamer"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isStreamer) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isStreamer) 16.dp else 4.dp,
                            bottomEnd = if (isStreamer) 4.dp else 16.dp
                        ),
                        color = if (isStreamer) StreamerBubble else ViewerBubble,
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = msg.text,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = formatTime(msg.time),
                                color = Color(0xFF999999),
                                fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
            }
        }

        // Input bar
        Surface(color = SurfaceColor, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Type a message...", color = TextMuted, fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentColor,
                        unfocusedBorderColor = Color(0xFF333333),
                        focusedTextColor = TextColor,
                        unfocusedTextColor = TextColor,
                        cursorColor = AccentColor
                    ),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSend(inputText.trim())
                            inputText = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentColor,
                        contentColor = Color.Black
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    Text("->", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return String.format("%02d:%02d", h, m)
}
