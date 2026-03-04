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
import dev.serverpages.server.WebServer
import kotlinx.coroutines.*
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Foreground service — runs everything unattended:
 * 1. HTTP server (NanoHTTPd on port 3333) — starts immediately
 * 2. Screen capture (MediaProjection → MediaCodec → HLS) — starts when token provided
 * 3. Tailscale — auto-launches if installed
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

        private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

        @Volatile
        var instance: CaptureService? = null
            private set
    }

    private var webServer: WebServer? = null
    private var screenCapture: ScreenCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentQuality: QualityPreset = QualityPreset.P720
    private var tailscaleHostname: String = ""
    private var accessCode: String = ""

    private val hlsDir: File by lazy {
        File(cacheDir, "hls").apply { mkdirs() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        accessCode = String.format("%04d", (0..9999).random())
        Log.i(TAG, "Service created — access code: $accessCode")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_SERVER -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting server..."))
                acquireWakeLock()
                acquireWifiLock()
                startWebServer()
                launchTailscale()
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
                launchTailscale()
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

    override fun onDestroy() {
        stopCapture()
        stopWebServer()
        releaseWifiLock()
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
            getCaptureState = { screenCapture?.isCapturing ?: false }
            getCurrentQuality = { currentQuality.label }
            getCameraFacing = { this@CaptureService.getCameraLabel() }
            getTailscaleUrl = { tailscaleHostname }
            onQualityChange = { label -> changeQuality(label) }
            onCameraSwitch = { switchCamera() }
            this.accessCode = this@CaptureService.accessCode
        }

        try {
            webServer!!.start()
            val ip = getLocalIpAddress()
            Log.i(TAG, "HTTP server started on http://$ip:$PORT")
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
        if (screenCapture?.isCapturing == true) {
            screenCapture?.stop()
        }

        screenCapture = ScreenCapture(this, hlsDir)
        // Use camera mode — bypasses MediaProjection
        screenCapture!!.startCamera(currentQuality, serviceScope)

        updateNotification(buildLiveNotificationText())
    }

    private fun stopCapture() {
        screenCapture?.stop()
        screenCapture = null
    }

    private fun changeQuality(label: String): Boolean {
        val preset = QualityPreset.fromLabel(label) ?: return false
        if (preset == currentQuality) return false
        currentQuality = preset

        if (screenCapture?.isCapturing == true) {
            stopCapture()
            val ip = getLocalIpAddress()
            updateNotification("Quality → ${preset.label} — tap to re-enable capture")
            return true
        }

        return true
    }

    private fun buildLiveNotificationText(): String {
        val ip = getLocalIpAddress()
        val base = "LIVE on http://$ip:$PORT | Code: $accessCode"
        return if (tailscaleHostname.isNotEmpty()) {
            "$base\nTailscale: $tailscaleHostname"
        } else base
    }

    fun isCapturing(): Boolean = screenCapture?.isCapturing ?: false
    fun isServerRunning(): Boolean = webServer != null
    fun getQualityLabel(): String = currentQuality.label
    fun getServerUrl(): String = "http://${getLocalIpAddress()}:$PORT"
    fun getTailscaleUrl(): String = tailscaleHostname
    fun getAccessCode(): String = accessCode
    fun getViewerCount(): Int = webServer?.getViewerCount() ?: 0
    fun getCameraLabel(): String = screenCapture?.getCameraLabel() ?: "back"

    fun switchCamera(): Boolean {
        if (screenCapture?.isCapturing != true) return false
        screenCapture?.switchCamera(serviceScope)
        return true
    }

    // ─── Tailscale ───────────────────────────────────────────────────────────

    private fun launchTailscale() {
        if (!isTailscaleInstalled()) {
            Log.i(TAG, "Tailscale not installed — skipping")
            return
        }

        // Launch Tailscale app to ensure VPN is connected
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.i(TAG, "Tailscale app launched")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch Tailscale", e)
        }

        // Try to detect Tailscale IP after a delay (VPN takes a moment)
        serviceScope.launch {
            delay(5000)
            detectTailscaleIp()
        }
    }

    private fun isTailscaleInstalled(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun detectTailscaleIp() {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                // Tailscale creates a "tun" interface, usually named "tun0" or "tailscale0"
                if (!intf.name.startsWith("tun") && !intf.name.contains("tailscale")) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val tsIp = addr.hostAddress ?: continue
                        // Tailscale IPs are in the 100.x.x.x range
                        if (tsIp.startsWith("100.")) {
                            tailscaleHostname = "http://$tsIp:$PORT"
                            Log.i(TAG, "Tailscale detected: $tailscaleHostname")
                            // Update notification with Tailscale URL
                            if (screenCapture?.isCapturing == true) {
                                updateNotification(buildLiveNotificationText())
                            }
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect Tailscale IP", e)
        }
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(dev.serverpages.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(dev.serverpages.R.string.notification_channel_description)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
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
            .setContentTitle("ServerPages")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─── WakeLock ────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ServerPages::CaptureWakeLock")
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
        wifiLock = wm.createWifiLock(mode, "ServerPages::WifiLock")
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
                // Skip Tailscale tun interface — we want the LAN IP
                if (intf.name.startsWith("tun") || intf.name.contains("tailscale")) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("100.")) return ip // Skip Tailscale CGNAT range
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
