package dev.serverpages.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.serverpages.service.CaptureService

data class ServiceState(
    val serverRunning: Boolean = false,
    val capturing: Boolean = false,
    val quality: String = "720p",
    val serverUrl: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    fun refreshState() {
        val service = CaptureService.instance
        _state.value = if (service != null) {
            ServiceState(
                serverRunning = service.isServerRunning(),
                capturing = service.isCapturing(),
                quality = service.getQualityLabel(),
                serverUrl = service.getServerUrl()
            )
        } else {
            ServiceState()
        }
    }
}
