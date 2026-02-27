package dev.serverpages.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * On boot:
 * 1. Start CaptureService in server-only mode (HTTP + media browser immediately)
 * 2. Launch Tailscale if installed (ensures VPN reconnects)
 * 3. Notification prompts user to tap for screen capture (one tap per reboot)
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed — starting ServerPages")

        // Start HTTP server
        val serviceIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START_SERVER
        }
        context.startForegroundService(serviceIntent)

        // Auto-launch Tailscale if installed
        launchTailscale(context)
    }

    private fun launchTailscale(context: Context) {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            Log.i(TAG, "Tailscale not installed — skipping")
            return
        }

        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.i(TAG, "Tailscale launched on boot")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch Tailscale on boot", e)
        }
    }
}
