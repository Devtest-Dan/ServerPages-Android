package dev.serverpages.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ServiceWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ServiceWatchdog"
    }

    override suspend fun doWork(): Result {
        if (CaptureService.instance != null) {
            Log.d(TAG, "Service alive — no action needed")
            return Result.success()
        }

        Log.i(TAG, "Service dead — restarting via foreground service")
        val intent = Intent(applicationContext, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START_SERVER
        }
        try {
            applicationContext.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service: ${e.message}")
            return Result.retry()
        }
        return Result.success()
    }
}
