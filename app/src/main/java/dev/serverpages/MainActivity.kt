package dev.serverpages

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.serverpages.service.CaptureService
import dev.serverpages.ui.ContentPlayerActivity
import dev.serverpages.ui.MainScreen
import dev.serverpages.ui.MainViewModel
import kotlinx.coroutines.delay

/**
 * Minimal activity — exists only as a permission gateway.
 *
 * Flow:
 * 1. Request runtime permissions (storage, notifications) if needed
 * 2. Immediately request MediaProjection permission
 * 3. Start CaptureService with full capture
 * 4. Move to background (finish activity)
 *
 * The activity is only visible for the permission dialogs.
 * On subsequent launches (e.g., from notification tap), it re-requests
 * MediaProjection if capture isn't running, then goes to background again.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var storageAccessLauncher: ActivityResultLauncher<Intent>
    private var isInitialSetup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MediaProjection permission launcher
        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.i(TAG, "MediaProjection granted — starting capture")
                startCaptureService(result.resultCode, result.data!!)
            } else {
                Log.w(TAG, "MediaProjection denied — server-only mode")
                startServerOnly()
            }
            // Only go to background after the initial permission setup
            if (isInitialSetup) {
                moveTaskToBack(true)
                isInitialSetup = false
            }
        }

        // Runtime permission launcher
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val denied = results.filter { !it.value }.keys
            if (denied.isNotEmpty()) {
                Log.w(TAG, "Permissions denied: $denied")
            }
            // After runtime permissions, check for all-files access
            checkAllFilesAccess()
        }

        // Storage access settings launcher (MANAGE_EXTERNAL_STORAGE)
        storageAccessLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.i(TAG, "All files access granted")
                } else {
                    Log.w(TAG, "All files access denied — media browser will not work")
                }
            }
            requestMediaProjection()
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7),
                    surface = Color(0xFF1A1A1A),
                    background = Color(0xFF0D0D0D),
                    onBackground = Color(0xFFE0E0E0),
                    onSurface = Color(0xFFE0E0E0)
                )
            ) {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.state.collectAsState()

                LaunchedEffect(Unit) {
                    while (true) {
                        viewModel.refreshState()
                        delay(1000)
                    }
                }

                MainScreen(
                    state = state,
                    onContentMode = {
                        startActivity(Intent(this@MainActivity, ContentPlayerActivity::class.java))
                    }
                )
            }
        }

        // Auto-start: check what's needed
        startAutoFlow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tapped notification — re-request capture if not running
        startAutoFlow()
    }

    private fun startAutoFlow() {
        // If capture is already running, stay visible so user can see the UI
        if (CaptureService.instance?.isCapturing() == true) {
            Log.i(TAG, "Already capturing — showing UI")
            return
        }

        // First-time setup — will auto-minimize after permissions
        isInitialSetup = true

        // Check if runtime permissions are needed
        val needed = getNeededPermissions()
        if (needed.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: $needed")
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            // Runtime permissions granted — check all-files access
            checkAllFilesAccess()
        }
    }

    private fun checkAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Log.i(TAG, "Requesting MANAGE_EXTERNAL_STORAGE")
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                storageAccessLauncher.launch(intent)
            } catch (_: Exception) {
                // Fallback for devices that don't support per-app intent
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                storageAccessLauncher.launch(intent)
            }
        } else {
            requestMediaProjection()
        }
    }

    private fun getNeededPermissions(): List<String> {
        val perms = mutableListOf<String>()

        // Only need notification permission as a runtime dialog
        // MANAGE_EXTERNAL_STORAGE is handled separately via settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        return perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaProjection() {
        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projManager.createScreenCaptureIntent())
    }

    private fun startServerOnly() {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START_SERVER
        }
        startForegroundService(intent)
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START_CAPTURE
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_QUALITY, "720p")
        }
        startForegroundService(intent)
    }
}
