package dev.serverpages.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val accessCode: String = "",
    val viewerCount: Int = 0,
    val setupStep: SetupStep = SetupStep.DONE
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

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
                accessCode = service.getAccessCode(),
                viewerCount = service.getViewerCount(),
                setupStep = if (service.isServerRunning()) SetupStep.DONE else currentStep
            )
        } else {
            ServiceState(setupStep = currentStep)
        }
    }
}
