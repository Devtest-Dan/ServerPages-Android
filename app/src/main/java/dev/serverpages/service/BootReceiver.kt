package dev.serverpages.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts the CaptureService in server-only mode on boot.
 * Screen capture requires user interaction (MediaProjection permission),
 * but the HTTP server + media browser work immediately.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed — starting ServerPages HTTP server")

        val serviceIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START_SERVER
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
