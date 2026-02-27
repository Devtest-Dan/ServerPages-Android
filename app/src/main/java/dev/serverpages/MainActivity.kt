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
import dev.serverpages.ui.SetupStep
import kotlinx.coroutines.delay
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var storageAccessLauncher: ActivityResultLauncher<Intent>
    private var isInitialSetup = false
    private lateinit var viewModel: MainViewModel

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

        // Runtime permission launcher (notifications step)
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val denied = results.filter { !it.value }.keys
            if (denied.isNotEmpty()) {
                Log.w(TAG, "Permissions denied: $denied")
            }
            // Advance to next step
            advanceSetupStep()
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
            // Proceed to capture after storage settings
            isInitialSetup = true
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
                this@MainActivity.viewModel = viewModel

                LaunchedEffect(Unit) {
                    // Determine initial setup step
                    viewModel.setSetupStep(getInitialSetupStep())
                    while (true) {
                        viewModel.refreshState()
                        delay(1000)
                    }
                }

                MainScreen(
                    state = state,
                    onContentMode = {
                        startActivity(Intent(this@MainActivity, ContentPlayerActivity::class.java))
                    },
                    onSetupAction = {
                        handleSetupAction(state.setupStep)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tapped notification — re-check setup state
        if (::viewModel.isInitialized) {
            viewModel.setSetupStep(getInitialSetupStep())
        }
    }

    private fun getInitialSetupStep(): SetupStep {
        // If already capturing, skip setup entirely
        if (CaptureService.instance?.isCapturing() == true) {
            return SetupStep.DONE
        }

        // Check notifications
        if (!hasNotificationPermission()) {
            return SetupStep.NOTIFICATIONS
        }

        // Need screen capture
        return SetupStep.CAPTURE
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed below Android 13
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun handleSetupAction(step: SetupStep) {
        when (step) {
            SetupStep.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                } else {
                    advanceSetupStep()
                }
            }
            SetupStep.CAPTURE -> {
                // Request storage access silently before capture if needed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        storageAccessLauncher.launch(intent)
                    } catch (_: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        storageAccessLauncher.launch(intent)
                    }
                } else {
                    isInitialSetup = true
                    requestMediaProjection()
                }
            }
            SetupStep.DONE -> {}
        }
    }

    private fun advanceSetupStep() {
        if (!::viewModel.isInitialized) return
        val current = viewModel.state.value.setupStep
        val next = when (current) {
            SetupStep.NOTIFICATIONS -> SetupStep.CAPTURE
            SetupStep.CAPTURE -> SetupStep.DONE
            SetupStep.DONE -> SetupStep.DONE
        }
        Log.i(TAG, "Setup: $current → $next")
        viewModel.setSetupStep(next)
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
