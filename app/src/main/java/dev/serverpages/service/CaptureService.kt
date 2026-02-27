package dev.serverpages.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
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
 * Foreground service that runs:
 * 1. Screen capture (MediaProjection → MediaCodec → HLS segments)
 * 2. HTTP server (NanoHTTPd on port 3333)
 *
 * The HTTP server starts immediately (media browsing works without capture).
 * Screen capture requires a MediaProjection token passed via intent extras.
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
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentQuality: QualityPreset = QualityPreset.P720

    private val hlsDir: File by lazy {
        File(cacheDir, "hls").apply { mkdirs() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_SERVER -> {
                startForeground(NOTIFICATION_ID, buildNotification("Media server running"))
                acquireWakeLock()
                startWebServer()
            }

            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val qualityLabel = intent.getStringExtra(EXTRA_QUALITY) ?: "720p"
                currentQuality = QualityPreset.fromLabel(qualityLabel) ?: QualityPreset.P720

                if (resultData != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Starting capture..."))
                    acquireWakeLock()
                    startWebServer()
                    startCapture(resultCode, resultData)
                } else {
                    Log.e(TAG, "Missing MediaProjection result data")
                }
            }

            ACTION_STOP -> {
                stopCapture()
                stopWebServer()
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
            onQualityChange = { label -> changeQuality(label) }
        }

        try {
            webServer!!.start()
            val ip = getLocalIpAddress()
            Log.i(TAG, "HTTP server started on http://$ip:$PORT")
            updateNotification("Serving on http://$ip:$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }
    }

    private fun stopWebServer() {
        webServer?.stop()
        webServer = null
        Log.i(TAG, "HTTP server stopped")
    }

    // ─── Screen Capture ──────────────────────────────────────────────────────

    private fun startCapture(resultCode: Int, data: Intent) {
        if (screenCapture?.isCapturing == true) {
            screenCapture?.stop()
        }

        screenCapture = ScreenCapture(this, hlsDir)
        screenCapture!!.start(resultCode, data, currentQuality, serviceScope)

        val ip = getLocalIpAddress()
        updateNotification("LIVE on http://$ip:$PORT")
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
            // Need MediaProjection token to restart — can't restart capture without it
            // Stop capture, user needs to re-grant permission
            stopCapture()
            val ip = getLocalIpAddress()
            updateNotification("Quality changed to ${preset.label} — tap to re-enable capture")
            return true
        }

        return true
    }

    fun isCapturing(): Boolean = screenCapture?.isCapturing ?: false
    fun isServerRunning(): Boolean = webServer != null
    fun getQualityLabel(): String = currentQuality.label
    fun getServerUrl(): String = "http://${getLocalIpAddress()}:$PORT"

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
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
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

    // ─── Network ─────────────────────────────────────────────────────────────

    private fun getLocalIpAddress(): String {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "localhost"
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: try WifiManager
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
