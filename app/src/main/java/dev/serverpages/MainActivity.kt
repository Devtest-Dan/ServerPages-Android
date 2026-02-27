package dev.serverpages

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
            // Go to background — nothing more to do here
            moveTaskToBack(true)
        }

        // Runtime permission launcher
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val denied = results.filter { !it.value }.keys
            if (denied.isNotEmpty()) {
                Log.w(TAG, "Permissions denied: $denied")
            }
            // Permissions done — now request MediaProjection
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
        // If capture is already running, just go to background
        if (CaptureService.instance?.isCapturing() == true) {
            Log.i(TAG, "Already capturing — going to background")
            moveTaskToBack(true)
            return
        }

        // Check if runtime permissions are needed
        val needed = getNeededPermissions()
        if (needed.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: $needed")
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            // Permissions already granted — go straight to MediaProjection
            requestMediaProjection()
        }
    }

    private fun getNeededPermissions(): List<String> {
        val perms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
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
