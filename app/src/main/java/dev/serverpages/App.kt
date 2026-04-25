package dev.serverpages

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import dev.serverpages.service.ServiceWatchdogWorker
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // Bring up the embedded Tailscale daemon as early as possible so the
        // tun device is ready by the time CaptureService asks for the VPN.
        dev.serverpages.tailscale.TailscaleNode.ensureStarted(this)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val watchdogRequest = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "airdeck_watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            watchdogRequest
        )
    }
}
