package dev.serverpages.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.serverpages.server.ChatMessage
import dev.serverpages.server.CodeInfo
import dev.serverpages.server.ConversationSummary
import dev.serverpages.service.CaptureService

enum class SetupStep {
    NOTIFICATIONS,
    CAPTURE,
    DONE
}

data class ServiceState(
    val serverRunning: Boolean = false,
    val capturing: Boolean = false,
    val quality: String = "720p",
    val serverUrl: String = "",
    val tailscaleUrl: String = "",
    val publicUrl: String = "",
    val accessCode: String = "",
    val codes: List<CodeInfo> = emptyList(),
    val viewerCount: Int = 0,
    val setupStep: SetupStep = SetupStep.DONE
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    // Chat state
    private val _selectedCode = MutableStateFlow<String?>(null)
    val selectedCode: StateFlow<String?> = _selectedCode.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    fun setSetupStep(step: SetupStep) {
        _state.value = _state.value.copy(setupStep = step)
    }

    fun refreshState() {
        val service = CaptureService.instance
        val currentStep = _state.value.setupStep
        _state.value = if (service != null) {
            ServiceState(
                serverRunning = service.isServerRunning(),
                capturing = service.isCapturing(),
                quality = service.getQualityLabel(),
                serverUrl = service.getServerUrl(),
                tailscaleUrl = service.getTailscaleUrl(),
                publicUrl = service.getPublicUrl(),
                accessCode = service.getAccessCode(),
                codes = service.getCodes(),
                viewerCount = service.getViewerCount(),
                setupStep = if (service.isServerRunning()) SetupStep.DONE else currentStep
            )
        } else {
            ServiceState(setupStep = currentStep)
        }
    }

    fun selectConversation(code: String?) {
        _selectedCode.value = code
        if (code != null) {
            refreshChatMessages()
        } else {
            _chatMessages.value = emptyList()
        }
    }

    fun refreshChat() {
        val service = CaptureService.instance ?: return
        _conversations.value = service.getConversations()
        val code = _selectedCode.value
        if (code != null) {
            refreshChatMessages()
        }
    }

    private fun refreshChatMessages() {
        val service = CaptureService.instance ?: return
        val code = _selectedCode.value ?: return
        _chatMessages.value = service.getChatMessages(code)
    }

    fun sendMessage(text: String) {
        val code = _selectedCode.value ?: return
        val service = CaptureService.instance ?: return
        service.sendChatMessage(code, text)
        refreshChatMessages()
        refreshChat()
    }
}
