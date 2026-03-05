package dev.serverpages.hub

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class HeartbeatManager(private val context: Context) {

    companion object {
        private const val TAG = "HeartbeatManager"
        private const val HUB_URL = "https://airdeck-hub.vercel.app"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val MAX_BACKOFF_MS = 120_000L
        private const val WAKE_POLL_INTERVAL_MS = 30_000L
        private const val PREFS_NAME = "airdeck_hub"
        private const val KEY_DEVICE_ID = "device_id"
    }

    var getPublicUrl: (() -> String)? = null
    var getSource: (() -> String)? = null
    var getQuality: (() -> String)? = null
    var onWakeCommand: (() -> Unit)? = null

    private val deviceId: String
    private var heartbeatJob: Job? = null
    private var wakeJob: Job? = null
    private var currentInterval = HEARTBEAT_INTERVAL_MS
    private val startTime = SystemClock.elapsedRealtime()

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = "airdeck-${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            id
        }
    }

    fun start(scope: CoroutineScope) {
        register(scope)
        currentInterval = HEARTBEAT_INTERVAL_MS
        heartbeatJob = scope.launch {
            while (true) {
                delay(currentInterval)
                val success = withContext(Dispatchers.IO) { sendHeartbeat() }
                if (success) {
                    currentInterval = HEARTBEAT_INTERVAL_MS
                } else {
                    currentInterval = (currentInterval * 2).coerceAtMost(MAX_BACKOFF_MS)
                    Log.w(TAG, "Heartbeat failed — backoff to ${currentInterval / 1000}s")
                }
            }
        }
        wakeJob = scope.launch {
            while (true) {
                delay(WAKE_POLL_INTERVAL_MS)
                withContext(Dispatchers.IO) { pollWakeFlag() }
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        wakeJob?.cancel()
        heartbeatJob = null
        wakeJob = null
    }

    fun onUrlChanged(scope: CoroutineScope, url: String) {
        scope.launch { withContext(Dispatchers.IO) { sendHeartbeat() } }
    }

    private fun register(scope: CoroutineScope) {
        scope.launch { withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", Build.MODEL)
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("androidVersion", Build.VERSION.RELEASE)
            }
            postJson("$HUB_URL/api/register", body)
        } }
    }

    private fun sendHeartbeat(): Boolean {
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("publicUrl", getPublicUrl?.invoke() ?: "")
                put("battery", getBatteryLevel())
                put("uptime", ((SystemClock.elapsedRealtime() - startTime) / 1000).toInt())
                put("source", getSource?.invoke() ?: "camera")
                put("quality", getQuality?.invoke() ?: "720p")
            }
            postJson("$HUB_URL/api/heartbeat", body)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed: ${e.message}")
            false
        }
    }

    private fun pollWakeFlag() {
        try {
            val conn = URL("$HUB_URL/api/wake-flag/$deviceId").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(response)
            if (json.optBoolean("wake", false)) {
                Log.i(TAG, "Wake command received, restarting tunnel")
                onWakeCommand?.invoke()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wake poll failed: ${e.message}")
        }
    }

    private fun postJson(urlStr: String, body: JSONObject) {
        try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val code = conn.responseCode
            conn.disconnect()

            if (code !in 200..299) {
                Log.w(TAG, "POST $urlStr returned $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST $urlStr failed: ${e.message}")
        }
    }

    private fun getBatteryLevel(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0) (level * 100) / scale else 0
    }
}
