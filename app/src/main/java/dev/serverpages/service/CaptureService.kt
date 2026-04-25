package dev.serverpages.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import dev.serverpages.MainActivity
import dev.serverpages.capture.QualityPreset
import dev.serverpages.capture.ScreenCapture
import dev.serverpages.server.ChatMessage
import dev.serverpages.server.CodeInfo
import dev.serverpages.server.ConversationSummary
import dev.serverpages.server.WebServer
import dev.serverpages.tailscale.TailscaleNode
import dev.serverpages.webrtc.WebRtcServer
import kotlinx.coroutines.*
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Foreground service — runs everything unattended:
 * 1. HTTP server (NanoHTTPd on port 3333) — starts immediately
 * 2. Screen capture (MediaProjection → MediaCodec → HLS) — starts when token provided
 * 3. DanNet WireGuard VPN — detects tun0 interface with 10.10.0.x IP
 */
class CaptureService : LifecycleService() {

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "serverpages_channel"
        private const val NOTIFICATION_ID = 1
        private const val PORT = 3333

        const val ACTION_START_SERVER = "dev.serverpages.START_SERVER"
        const val ACTION_START_CAPTURE = "dev.serverpages.START_CAPTURE"
        const val ACTION_STOP = "dev.serverpages.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_QUALITY = "quality"

        @Volatile
        var instance: CaptureService? = null
            private set
    }

    private var webServer: WebServer? = null
    private var screenCapture: ScreenCapture? = null
    private var webRtcServer: WebRtcServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var projectionData: Intent? = null
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentQuality: QualityPreset = QualityPreset.P720
    private var publicUrl: String = ""
    private var accessCodes: List<CodeInfo> = emptyList()
    private var heartbeatManager: dev.serverpages.hub.HeartbeatManager? = null
    private var updateManager: dev.serverpages.hub.UpdateManager? = null
    private var networkMonitor: NetworkMonitor? = null

    private val hlsDir: File by lazy {
        File(cacheDir, "hls").apply { mkdirs() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        heartbeatManager = dev.serverpages.hub.HeartbeatManager(this).apply {
            getPublicUrl = { this@CaptureService.publicUrl }
            getSource = { this@CaptureService.getCurrentSource() }
            getQuality = { this@CaptureService.currentQuality.label }
            onWakeCommand = { /* tsnet keeps itself alive */ }
        }
        updateManager = dev.serverpages.hub.UpdateManager(this)
        createNotificationChannel()
        accessCodes = generateUniqueCodes(10)
        Log.i(TAG, "Service created — ${accessCodes.size} access codes generated")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_SERVER -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting server..."))
                acquireWakeLock()
                acquireWifiLock()
                startWebServer()
                // Show notification that prompts user to tap for capture
                val ip = getLocalIpAddress()
                updateNotification("Server on http://$ip:$PORT — tap to enable capture")
            }

            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val qualityLabel = intent.getStringExtra(EXTRA_QUALITY) ?: "720p"
                currentQuality = QualityPreset.fromLabel(qualityLabel) ?: QualityPreset.P720

                startForeground(NOTIFICATION_ID, buildNotification("Starting capture..."))
                acquireWakeLock()
                acquireWifiLock()
                startWebServer()
                startCapture(resultCode, resultData)
            }

            ACTION_STOP -> {
                stopCapture()
                stopWebServer()
                releaseWifiLock()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed (app swiped away) — service continues running")
        // Service stays alive via foreground notification + stopWithTask=false
        // Re-post notification in case system cleared it
        if (webServer != null) {
            updateNotification(buildLiveNotificationText())
        }
    }

    override fun onDestroy() {
        networkMonitor?.unregister()
        networkMonitor = null
        stopCapture()  // Stops WebRTC + ScreenCapture
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        stopWebServer()
        releaseWifiLock()
        updateManager?.stop()
        heartbeatManager?.stop()
        releaseWakeLock()
        serviceScope.cancel()
        instance = null
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    // ─── Web Server ──────────────────────────────────────────────────────────

    private fun startWebServer() {
        if (webServer != null) return

        webServer = WebServer(this, hlsDir, PORT).apply {
            getCaptureState = { webRtcServer?.isRunning ?: (screenCapture?.isCapturing ?: false) }
            getCurrentQuality = { currentQuality.label }
            getCameraFacing = { this@CaptureService.getCameraLabel() }
            getPublicUrl = { publicUrl }
            onQualityChange = { label -> changeQuality(label) }
            onCameraSwitch = { switchCamera() }
            getWebRtcServer = { this@CaptureService.webRtcServer }
            onViewerMessage = { _, _, _ -> /* notifications disabled for IP-camera mode */ }
            this.accessCodes = this@CaptureService.accessCodes
        }

        try {
            webServer!!.start()
            val ip = getLocalIpAddress()
            Log.i(TAG, "HTTP server started on http://$ip:$PORT")
            TailscaleNode.ensureStarted(this)
            TailscaleNode.runLoginAndPoll(serviceScope) { tsIp ->
                publicUrl = if (tsIp.isNotEmpty()) "http://$tsIp:$PORT" else ""
                heartbeatManager?.onUrlChanged(serviceScope, publicUrl)
                if (isCapturing()) updateNotification(buildLiveNotificationText())
            }
            heartbeatManager?.start(serviceScope)
            updateManager?.start(serviceScope)
            networkMonitor = NetworkMonitor(this).apply {
                onNetworkAvailable = { /* tsnet handles reconnect */ }
                onNetworkLost = { /* keep last URL — tsnet may reconnect */ }
                register()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }
    }

    private fun stopWebServer() {
        webServer?.stop()
        webServer = null
        Log.i(TAG, "HTTP server stopped")
    }

    // ─── Capture ───────────────────────────────────────────────────────────

    private fun startCapture(resultCode: Int, data: Intent?) {
        stopCapture()

        // Create MediaProjection immediately while token is fresh
        if (data != null) {
            projectionData = data
            try {
                val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                mediaProjection = pm.getMediaProjection(resultCode, data)
                Log.i(TAG, "MediaProjection created at startup — screen capture available")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create MediaProjection — screen capture unavailable", e)
                mediaProjection = null
            }
        }

        webRtcServer = WebRtcServer(this).also { rtc ->
            rtc.initialize()
            val preset = currentQuality
            rtc.startCamera(preset.width, preset.height, preset.fps)
        }

        updateNotification(buildLiveNotificationText())
    }

    private fun stopCapture() {
        webRtcServer?.stop()
        webRtcServer = null
        screenCapture?.stop()
        screenCapture = null
        // Don't stop mediaProjection here — it's reusable across source switches
        // It's only cleaned up in onDestroy()
    }

    fun toggleCapture() {
        if (webRtcServer?.isRunning == true) {
            stopCapture()
            val ip = getLocalIpAddress()
            updateNotification("Server on http://$ip:$PORT — camera off")
            Log.i(TAG, "Camera stopped by user")
        } else {
            webRtcServer = WebRtcServer(this).also { rtc ->
                rtc.initialize()
                val preset = currentQuality
                rtc.startCamera(preset.width, preset.height, preset.fps)
            }
            updateNotification(buildLiveNotificationText())
            Log.i(TAG, "Camera started by user")
        }
    }

    private fun changeQuality(label: String): Boolean {
        val preset = QualityPreset.fromLabel(label) ?: return false
        if (preset == currentQuality) return false
        currentQuality = preset

        if (webRtcServer?.isRunning == true || screenCapture?.isCapturing == true) {
            stopCapture()
            val ip = getLocalIpAddress()
            updateNotification("Quality -> ${preset.label} — tap to re-enable capture")
            return true
        }

        return true
    }

    private fun buildLiveNotificationText(): String {
        val ip = getLocalIpAddress()
        val firstCode = accessCodes.firstOrNull()?.code ?: "----"
        val base = "LIVE on http://$ip:$PORT | Code: $firstCode"
        val parts = mutableListOf(base)
        if (publicUrl.isNotEmpty()) parts.add("Public: $publicUrl")
        return parts.joinToString("\n")
    }

    fun isCapturing(): Boolean = webRtcServer?.isRunning ?: (screenCapture?.isCapturing ?: false)
    fun isServerRunning(): Boolean = webServer != null
    fun getQualityLabel(): String = currentQuality.label
    fun getServerUrl(): String = "http://${getLocalIpAddress()}:$PORT"
    fun getPublicUrl(): String = publicUrl
    fun getAccessCode(): String = accessCodes.firstOrNull()?.code ?: "----"
    fun getCodes(): List<CodeInfo> = accessCodes
    fun getViewerCount(): Int = (webServer?.getViewerCount() ?: 0) + (webRtcServer?.getPeerCount() ?: 0)
    fun getWebRtcPeerCount(): Int = webRtcServer?.getPeerCount() ?: 0
    fun isWebRtcActive(): Boolean = webRtcServer?.isRunning ?: false
    fun isAudioEnabled(): Boolean = webRtcServer?.isAudioEnabled() ?: false
    fun getCameraLabel(): String {
        webRtcServer?.let { return if (it.isFrontCamera()) "front" else "back" }
        return screenCapture?.getCameraLabel() ?: "back"
    }
    fun getEglBase(): EglBase? = webRtcServer?.getEglBase()
    fun getWebRtcServer(): WebRtcServer? = webRtcServer

    // Chat bridge
    fun getConversations(): List<ConversationSummary> {
        val ws = webServer ?: return emptyList()
        return accessCodes.mapNotNull { ci ->
            val msgs = ws.conversations[ci.code]
            if ((msgs == null || msgs.isEmpty()) && !ci.isConnected) return@mapNotNull null
            val last = msgs?.lastOrNull()
            ConversationSummary(
                code = ci.code,
                label = ci.label,
                connected = ci.isConnected,
                lastMessage = last?.text ?: "",
                lastTime = last?.time ?: 0L,
                messageCount = msgs?.size ?: 0
            )
        }
    }

    fun getChatMessages(code: String): List<ChatMessage> {
        return webServer?.conversations?.get(code)?.toList() ?: emptyList()
    }

    fun sendChatMessage(code: String, text: String) {
        val ws = webServer ?: return
        val msg = ChatMessage(from = "streamer", text = text)
        ws.conversations.getOrPut(code) { mutableListOf() }.add(msg)
    }

    private fun generateUniqueCodes(count: Int): List<CodeInfo> {
        val codes = mutableSetOf<String>()
        while (codes.size < count) {
            codes.add(String.format("%04d", (0..9999).random()))
        }
        return codes.mapIndexed { index, code ->
            CodeInfo(code = code, label = "Viewer ${index + 1}")
        }
    }

    fun setPreviewSurface(surface: android.view.Surface?) {
        screenCapture?.setPreviewSurface(surface)
    }

    fun setPreviewRenderer(renderer: SurfaceViewRenderer?) {
        webRtcServer?.setLocalRenderer(renderer)
    }

    fun removeRendererSink(renderer: SurfaceViewRenderer) {
        webRtcServer?.removeRendererSink(renderer)
    }

    fun switchCamera(): Boolean {
        webRtcServer?.let {
            if (!it.isRunning) return false
            it.switchCamera()
            return true
        }
        if (screenCapture?.isCapturing != true) return false
        screenCapture?.switchCamera(serviceScope)
        return true
    }

    fun toggleAudio(): Boolean = webRtcServer?.toggleAudio() ?: false

    fun switchSource(mode: String): Boolean {
        val rtc = webRtcServer ?: return false
        if (mode == rtc.currentSource) return false
        if (mode == "screen") {
            val proj = mediaProjection ?: return false
            rtc.switchToScreen(proj)
            return true
        }
        if (mode == "camera") {
            rtc.switchToCamera()
            return true
        }
        return false
    }

    fun getCurrentSource(): String = webRtcServer?.currentSource ?: "camera"
    fun isScreenAvailable(): Boolean = mediaProjection != null

    // ─── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(dev.serverpages.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(dev.serverpages.R.string.notification_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AirDeck")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification(text: String) {
        // No-op: notification stays minimal and static to avoid any user-visible churn.
    }

    // ─── WakeLock ────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirDeck::CaptureWakeLock")
        wakeLock!!.acquire()
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        Log.d(TAG, "WakeLock released")
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "AirDeck::WifiLock")
        wifiLock!!.acquire()
        Log.d(TAG, "WifiLock acquired")
    }

    private fun releaseWifiLock() {
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null
        Log.d(TAG, "WifiLock released")
    }

    // ─── Network ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                // Skip VPN tun interfaces — we want the LAN IP
                if (intf.name.startsWith("tun")) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        return ip
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: WifiManager
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) {
                return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
            }
        } catch (_: Exception) {}

        return "localhost"
    }
}
