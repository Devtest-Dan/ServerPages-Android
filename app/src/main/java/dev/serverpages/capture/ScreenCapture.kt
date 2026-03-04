package dev.serverpages.capture

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.io.File

/**
 * Captures video via Camera2 → MediaCodec (H.264) → HlsWriter.
 * Also supports screen capture via MediaProjection (legacy mode).
 */
class ScreenCapture(
    private val context: Context,
    private val hlsDir: File
) {
    companion object {
        private const val TAG = "ScreenCapture"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var hlsWriter: HlsWriter? = null
    private var encoderJob: Job? = null
    private var quality: QualityPreset = QualityPreset.P720

    // Camera2
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var useCamera: Boolean = false
    private var cameraFacing: Int = CameraCharacteristics.LENS_FACING_BACK

    private var previewSurface: Surface? = null

    @Volatile
    var isCapturing: Boolean = false
        private set

    /**
     * Start camera capture (no MediaProjection needed).
     */
    fun startCamera(
        preset: QualityPreset,
        scope: CoroutineScope
    ) {
        if (isCapturing) {
            Log.w(TAG, "Already capturing")
            return
        }

        useCamera = true
        quality = preset

        // Setup encoder
        if (!setupEncoder(preset)) return

        // Setup HLS writer
        hlsWriter = HlsWriter(hlsDir)

        // Start camera thread
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        // Open camera
        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCamera(camManager, cameraFacing)
        if (cameraId == null) {
            Log.e(TAG, "No camera found for facing $cameraFacing")
            cleanup()
            return
        }

        try {
            camManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraSession(camera, preset)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            cleanup()
            return
        }

        isCapturing = true
        Log.i(TAG, "Camera capture starting: ${preset.label} (${preset.width}x${preset.height})")

        // Start encoder output drain loop
        encoderJob = scope.launch(Dispatchers.IO) {
            drainEncoder()
        }
    }

    private fun findCamera(manager: CameraManager, facing: Int): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing == facing) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    @Volatile
    private var switchRetryCount = 0
    private var previousFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    @Volatile
    private var isReverting = false

    /**
     * Switch between front and back camera without stopping the encoder.
     * Handles Samsung adaptivebrightnessgo conflict with retry logic.
     */
    fun switchCamera(scope: CoroutineScope) {
        if (!isCapturing || !useCamera) return

        val newFacing = if (cameraFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        previousFacing = cameraFacing
        switchRetryCount = 0
        isReverting = false

        // Close current camera
        closeCameraOnly()

        cameraFacing = newFacing

        openCameraForSwitch()
    }

    private fun closeCameraOnly() {
        try { cameraSession?.close() } catch (_: Exception) {}
        cameraSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
    }

    private fun openCameraForSwitch() {
        val handler = cameraHandler
        if (handler == null) {
            Log.e(TAG, "Camera handler is null, cannot switch")
            if (!isReverting) revertCamera()
            return
        }

        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCamera(camManager, cameraFacing)
        if (cameraId == null) {
            Log.e(TAG, "No camera found for facing $cameraFacing")
            if (!isReverting) revertCamera()
            return
        }

        Log.i(TAG, "Opening camera $cameraId (facing=${getCameraLabel()}, attempt=${switchRetryCount + 1}, revert=$isReverting)")

        try {
            camManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "Camera opened successfully: ${getCameraLabel()}")
                    cameraDevice = camera
                    switchRetryCount = 0
                    isReverting = false
                    createCameraSession(camera, quality)
                    Log.i(TAG, "Switched to ${getCameraLabel()}")
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected (likely Samsung adaptive brightness)")
                    camera.close()
                    cameraDevice = null
                    cameraSession = null
                    // Retry — our foreground service has higher priority
                    if (switchRetryCount < 3) {
                        switchRetryCount++
                        Log.i(TAG, "Retrying camera open (attempt ${switchRetryCount + 1})...")
                        handler.postDelayed({ openCameraForSwitch() }, 500)
                    } else if (!isReverting) {
                        Log.e(TAG, "Failed to acquire camera after retries, reverting")
                        revertCamera()
                    } else {
                        Log.e(TAG, "Revert also failed — camera unavailable")
                    }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error on switch: $error (revert=$isReverting)")
                    camera.close()
                    cameraDevice = null
                    cameraSession = null
                    if (switchRetryCount < 3 && error == ERROR_CAMERA_IN_USE) {
                        switchRetryCount++
                        Log.i(TAG, "Camera in use, retrying (attempt ${switchRetryCount + 1})...")
                        handler.postDelayed({ openCameraForSwitch() }, 500)
                    } else if (!isReverting) {
                        revertCamera()
                    } else {
                        Log.e(TAG, "Revert also failed — camera unavailable")
                    }
                }
            }, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception: ${e.reason} — ${e.message}")
            if (!isReverting) revertCamera()
            else Log.e(TAG, "Revert also failed — camera unavailable")
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied on switch", e)
            if (!isReverting) revertCamera()
            else Log.e(TAG, "Revert also failed — camera unavailable")
        }
    }

    private fun revertCamera() {
        isReverting = true
        switchRetryCount = 0
        Log.w(TAG, "Reverting to previous camera (${if (previousFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"})")
        cameraFacing = previousFacing
        openCameraForSwitch()
    }

    fun getCameraLabel(): String {
        return if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
    }

    /**
     * Set a preview surface for on-device display.
     * If capturing, recreates the camera session to include it.
     */
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        // Recreate session if camera is active
        val cam = cameraDevice
        if (cam != null && isCapturing && useCamera) {
            try { cameraSession?.close() } catch (_: Exception) {}
            cameraSession = null
            createCameraSession(cam, quality)
        }
    }

    private fun createCameraSession(camera: CameraDevice, preset: QualityPreset) {
        val encoderSurface = inputSurface ?: return

        val surfaces = mutableListOf(encoderSurface)
        previewSurface?.let { surfaces.add(it) }

        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraSession = session
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(encoderSurface)
                            previewSurface?.let { addTarget(it) }
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        session.setRepeatingRequest(request.build(), null, cameraHandler)
                        Log.i(TAG, "Camera session started (preview=${previewSurface != null})")
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera session configure failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera session", e)
        }
    }

    /**
     * Start screen capture via MediaProjection (original mode).
     */
    fun start(
        resultCode: Int,
        data: Intent,
        preset: QualityPreset,
        scope: CoroutineScope
    ) {
        if (isCapturing) {
            Log.w(TAG, "Already capturing")
            return
        }

        useCamera = false
        quality = preset
        val projManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = projManager.getMediaProjection(resultCode, data)

        if (projection == null) {
            Log.e(TAG, "MediaProjection is null")
            return
        }

        // Register stop callback
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                stop()
            }
        }, null)

        // Setup encoder
        if (!setupEncoder(preset)) {
            cleanup()
            return
        }

        // Setup HLS writer
        hlsWriter = HlsWriter(hlsDir)

        // Create virtual display
        val metrics = context.resources.displayMetrics
        virtualDisplay = projection!!.createVirtualDisplay(
            "ServerPages",
            preset.width,
            preset.height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        isCapturing = true
        Log.i(TAG, "Screen capture started: ${preset.label} (${preset.width}x${preset.height})")

        // Start encoder output drain loop
        encoderJob = scope.launch(Dispatchers.IO) {
            drainEncoder()
        }
    }

    private fun setupEncoder(preset: QualityPreset): Boolean {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            preset.width,
            preset.height
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, preset.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, preset.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, preset.keyFrameInterval)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
        }

        return try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec!!.createInputSurface()
            codec!!.start()
            Log.d(TAG, "Encoder configured: ${preset.width}x${preset.height} @ ${preset.bitrate}bps")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure encoder", e)
            false
        }
    }

    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        var formatSet = false

        while (isCapturing) {
            val outputIndex = codec?.dequeueOutputBuffer(bufferInfo, 10_000) ?: break

            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec!!.outputFormat
                    hlsWriter?.setFormat(newFormat)
                    formatSet = true
                    Log.d(TAG, "Encoder output format changed: $newFormat")
                }

                outputIndex >= 0 -> {
                    if (!formatSet) {
                        codec!!.releaseOutputBuffer(outputIndex, false)
                        continue
                    }

                    val outputBuffer = codec!!.getOutputBuffer(outputIndex) ?: continue

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        hlsWriter?.writeSample(outputBuffer, bufferInfo)
                    }

                    codec!!.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.i(TAG, "End of stream")
                        break
                    }
                }
            }
        }

        Log.d(TAG, "Encoder drain loop exited")
    }

    fun stop() {
        if (!isCapturing) return
        isCapturing = false

        encoderJob?.cancel()
        encoderJob = null

        cleanup()
        Log.i(TAG, "Capture stopped")
    }

    private fun cleanup() {
        // Camera cleanup
        try { cameraSession?.close() } catch (e: Exception) { Log.w(TAG, "Session close", e) }
        cameraSession = null

        try { cameraDevice?.close() } catch (e: Exception) { Log.w(TAG, "Camera close", e) }
        cameraDevice = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        // Screen capture cleanup
        try { virtualDisplay?.release() } catch (e: Exception) { Log.w(TAG, "VD release", e) }
        virtualDisplay = null

        try {
            codec?.signalEndOfInputStream()
            codec?.stop()
            codec?.release()
        } catch (e: Exception) { Log.w(TAG, "Codec release", e) }
        codec = null
        inputSurface = null

        try { projection?.stop() } catch (e: Exception) { Log.w(TAG, "Projection stop", e) }
        projection = null

        hlsWriter?.stop()
        hlsWriter = null
    }

    fun getQuality(): QualityPreset = quality
}
