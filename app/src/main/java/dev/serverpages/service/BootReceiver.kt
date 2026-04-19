package dev.serverpages.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed — starting AirDeck")

        val serviceIntent = Intent(context, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START_SERVER
        }
        context.startForegroundService(serviceIntent)
    }
}
