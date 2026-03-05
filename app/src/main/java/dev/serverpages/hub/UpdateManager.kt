package dev.serverpages.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val CHECK_INTERVAL_MS = 3_600_000L // 1 hour
        private const val INITIAL_DELAY_MS = 60_000L // 60s after start
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/Devtest-Dan/ServerPages-Android/releases/latest"
        private const val CHANNEL_ID = "airdeck_updates"
        private const val NOTIFICATION_ID = 9999
        private const val PREFS_NAME = "airdeck_updates"
        private const val KEY_LAST_TAG = "last_downloaded_tag"
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            delay(INITIAL_DELAY_MS)
            while (true) {
                withContext(Dispatchers.IO) { checkForUpdate() }
                delay(CHECK_INTERVAL_MS)
            }
        }
        Log.i(TAG, "Update checker started (interval: ${CHECK_INTERVAL_MS / 60_000}min)")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun checkForUpdate() {
        try {
            val conn = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) {
                Log.w(TAG, "GitHub API returned ${conn.responseCode}")
                conn.disconnect()
                return
            }

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(response)
            val tagName = json.optString("tag_name", "")
            if (tagName.isEmpty()) return

            val remoteVersion = tagName.removePrefix("v")
            val localVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

            if (!isNewer(remoteVersion, localVersion)) {
                Log.d(TAG, "Up to date ($localVersion >= $remoteVersion)")
                return
            }

            // Check if already downloaded this version
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_LAST_TAG, "") == tagName) {
                Log.d(TAG, "Already downloaded $tagName")
                return
            }

            // Find APK asset
            val assets = json.optJSONArray("assets") ?: return
            var downloadUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    downloadUrl = asset.optString("browser_download_url", "")
                    break
                }
            }

            if (downloadUrl.isNullOrEmpty()) {
                Log.w(TAG, "No APK asset found in release $tagName")
                return
            }

            Log.i(TAG, "Update available: $localVersion -> $remoteVersion, downloading...")

            // Download APK
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "airdeck-update.apk")

            val dlConn = URL(downloadUrl).openConnection() as HttpURLConnection
            dlConn.connectTimeout = 30_000
            dlConn.readTimeout = 60_000
            dlConn.instanceFollowRedirects = true

            dlConn.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            dlConn.disconnect()

            Log.i(TAG, "APK downloaded: ${apkFile.length()} bytes")

            // Save tag to avoid re-downloading
            prefs.edit().putString(KEY_LAST_TAG, tagName).apply()

            // Post notification
            postUpdateNotification(apkFile, remoteVersion)

        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    private fun postUpdateNotification(apkFile: File, version: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for AirDeck OTA updates"
        }
        nm.createNotificationChannel(channel)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, installIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("AirDeck Update Available")
            .setContentText("Tap to install v$version")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "Update notification posted for v$version")
    }
}
