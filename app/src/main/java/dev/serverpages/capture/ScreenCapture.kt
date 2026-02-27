package dev.serverpages.capture

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.io.File

/**
 * Captures the screen via MediaProjection → VirtualDisplay → MediaCodec (H.264) → HlsWriter.
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

    @Volatile
    var isCapturing: Boolean = false
        private set

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

        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec!!.createInputSurface()
            codec!!.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure encoder", e)
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
        Log.i(TAG, "Capture started: ${preset.label} (${preset.width}x${preset.height})")

        // Start encoder output drain loop
        encoderJob = scope.launch(Dispatchers.IO) {
            drainEncoder()
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
