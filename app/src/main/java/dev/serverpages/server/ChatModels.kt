package dev.serverpages.server

data class ChatMessage(
    val from: String,   // "viewer" or "streamer"
    val text: String,
    val time: Long = System.currentTimeMillis()
)

data class CodeInfo(
    val code: String,
    var label: String,
    var token: String? = null  // null until someone authenticates with this code
) {
    val isConnected: Boolean get() = token != null
}

data class ConversationSummary(
    val code: String,
    val label: String,
    val connected: Boolean,
    val lastMessage: String,
    val lastTime: Long,
    val messageCount: Int
)
