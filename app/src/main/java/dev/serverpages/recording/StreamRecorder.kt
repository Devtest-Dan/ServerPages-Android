package dev.serverpages.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tee from WebRTC's [org.webrtc.VideoTrack] into mp4 files on disk.
 *
 * Encodes H.264 via [MediaCodec] (Image-based YUV input) and muxes via
 * [MediaMuxer]. Rolls over to a new file every [SEGMENT_MS] of presentation
 * time so CameraBackup can pick up segments while recording continues.
 *
 * Output: /storage/emulated/0/Movies/AirDeck/recording_YYYYMMDD_HHMMSS.mp4
 *
 * Files are registered with MediaStore on close so CameraBackup's observer
 * sees them.
 */
class StreamRecorder(private val context: Context) : VideoSink {

    companion object {
        private const val TAG = "StreamRecorder"
        private const val MIME_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val SEGMENT_MS = 30L * 60L * 1000L           // 30 minutes
        private const val FOLDER_NAME = "AirDeck"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    private val drainThread = HandlerThread("StreamRecorderDrain").apply { start() }
    private val drainHandler = Handler(drainThread.looper)

    @Volatile private var running = false
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var fps = 30

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var firstFramePresentationUs = -1L
    private var segmentStartFramePresentationUs = -1L
    private var currentFile: File? = null

    private val outputRoot: File by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            FOLDER_NAME
        ).also { it.mkdirs() }
    }

    /** Begin recording. Must be called from any thread; idempotent. */
    @Synchronized
    fun start(width: Int, height: Int, fps: Int) {
        if (running) return
        this.width = width
        this.height = height
        this.fps = fps
        firstFramePresentationUs = -1L
        segmentStartFramePresentationUs = -1L

        // Orphan recovery: if we were force-killed last run, the previous
        // segment never got registered with MediaStore. Sweep the folder now
        // so CameraBackup can pick up anything we left behind.
        sweepOrphanedSegments()

        try {
            openSegment()
            running = true
            Log.i(TAG, "Recording started ${width}x${height}@${fps}fps → ${currentFile?.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start recorder", e)
            closeQuietly()
        }
    }

    private fun sweepOrphanedSegments() {
        try {
            outputRoot.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.forEach { f ->
                registerWithMediaStore(f)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sweepOrphanedSegments threw: ${e.message}")
        }
    }

    /** Stop recording. Flushes the current segment and finalizes the file. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            closeSegment()
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing segment", e)
        }
        drainThread.quitSafely()
        Log.i(TAG, "Recording stopped")
    }

    override fun onFrame(frame: VideoFrame) {
        // Critical: swallow ALL exceptions. WebRTC's native side aborts
        // the camera thread if a Java VideoSink throws.
        try {
            if (!running) return
            val enc = encoder ?: return

            val nowUs = System.nanoTime() / 1000L
            if (firstFramePresentationUs < 0) {
                firstFramePresentationUs = nowUs
                segmentStartFramePresentationUs = nowUs
            }
            val ptsUs = nowUs - firstFramePresentationUs

            if (nowUs - segmentStartFramePresentationUs >= SEGMENT_MS * 1000L) {
                rolloverSegment()
                segmentStartFramePresentationUs = nowUs
            }

            val i420 = frame.buffer.toI420() ?: return
            try {
                val idx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (idx >= 0) {
                    val image = enc.getInputImage(idx)
                    if (image != null && image.width >= width && image.height >= height) {
                        val bytes = copyI420ToImage(i420, image)
                        enc.queueInputBuffer(idx, 0, bytes, ptsUs, 0)
                    } else {
                        // Couldn't get an Image — release the buffer back without queueing.
                        enc.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                    }
                }
                drainHandler.post { drainEncoder(false) }
            } finally {
                i420.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onFrame threw — recording may be incomplete: ${t.message}")
        }
    }

    // ─── Segment lifecycle ───────────────────────────────────────────────────

    private fun openSegment() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(outputRoot, "recording_$ts.mp4")
        currentFile = file

        val format = MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val enc = MediaCodec.createEncoderByType(MIME_VIDEO)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        encoder = enc

        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = -1
        muxerStarted = false
    }

    private fun closeSegment() {
        val enc = encoder ?: return
        try {
            // Signal end of stream.
            val idx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (idx >= 0) {
                enc.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            // Drain remaining buffers until EOS.
            drainEncoder(endOfStream = true)
        } catch (e: Throwable) {
            Log.w(TAG, "Drain at close threw: ${e.message}")
        } finally {
            closeQuietly()
            registerWithMediaStore(currentFile)
        }
    }

    private fun rolloverSegment() {
        Log.i(TAG, "Rolling over segment: ${currentFile?.name}")
        closeSegment()
        firstFramePresentationUs = -1L  // reset PTS for new segment
        openSegment()
    }

    private fun closeQuietly() {
        try { encoder?.stop() } catch (_: Throwable) {}
        try { encoder?.release() } catch (_: Throwable) {}
        encoder = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Throwable) {}
        try { muxer?.release() } catch (_: Throwable) {}
        muxer = null
        muxerStarted = false
        trackIndex = -1
    }

    // ─── Encoder drain ───────────────────────────────────────────────────────

    private val bufferInfo = MediaCodec.BufferInfo()

    /** Pulls encoded chunks out of [encoder] and feeds them into [muxer]. */
    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mux = muxer ?: return
        while (true) {
            val outIdx = try {
                enc.dequeueOutputBuffer(bufferInfo, if (endOfStream) DEQUEUE_TIMEOUT_US else 0)
            } catch (e: Throwable) {
                Log.w(TAG, "dequeueOutputBuffer threw: ${e.message}")
                return
            }
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // else keep spinning until EOS
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        Log.w(TAG, "Format changed twice; ignoring")
                    } else {
                        trackIndex = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                }
                outIdx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0 && muxerStarted) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        try {
                            mux.writeSampleData(trackIndex, outBuf, bufferInfo)
                        } catch (e: Throwable) {
                            Log.w(TAG, "writeSampleData threw: ${e.message}")
                        }
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    // ─── YUV plumbing ────────────────────────────────────────────────────────

    /**
     * Copy a WebRTC [VideoFrame.I420Buffer] into a MediaCodec input
     * [android.media.Image]. Returns the total bytes written so the caller
     * can pass the right size to [MediaCodec.queueInputBuffer].
     */
    private fun copyI420ToImage(src: VideoFrame.I420Buffer, dst: android.media.Image): Int {
        val planes = dst.planes
        val cropW = minOf(src.width, dst.width)
        val cropH = minOf(src.height, dst.height)
        val yBytes = copyPlane(src.dataY, src.strideY, cropW, cropH, planes[0])
        val uBytes = copyPlane(src.dataU, src.strideU, cropW / 2, cropH / 2, planes[1])
        val vBytes = copyPlane(src.dataV, src.strideV, cropW / 2, cropH / 2, planes[2])
        return yBytes + uBytes + vBytes
    }

    private fun copyPlane(
        srcBuf: java.nio.ByteBuffer,
        srcStride: Int,
        w: Int,
        h: Int,
        dstPlane: android.media.Image.Plane,
    ): Int {
        val dstBuf = dstPlane.buffer
        val dstRowStride = dstPlane.rowStride
        val dstPixelStride = dstPlane.pixelStride
        val src = srcBuf.duplicate().apply { rewind() }
        val rowBuf = ByteArray(srcStride)
        var bytesWritten = 0

        for (row in 0 until h) {
            src.position(row * srcStride)
            // Defensive: srcStride might be smaller than w on tightly packed
            // buffers; clamp the read to what's available.
            val readLen = minOf(srcStride, src.remaining())
            src.get(rowBuf, 0, readLen)

            val dstRowStart = row * dstRowStride
            if (dstPixelStride == 1) {
                // Planar: bulk write w bytes (rowStride may be > w; rest is padding).
                dstBuf.position(dstRowStart)
                val safeW = minOf(w, dstBuf.remaining())
                dstBuf.put(rowBuf, 0, safeW)
                bytesWritten += safeW
            } else {
                // Semi-planar: write each sample at pixelStride apart. We
                // never write past w * pixelStride within a row.
                for (col in 0 until w) {
                    val pos = dstRowStart + col * dstPixelStride
                    if (pos >= dstBuf.limit()) break
                    dstBuf.position(pos)
                    dstBuf.put(rowBuf[col])
                    bytesWritten++
                }
            }
        }
        return bytesWritten
    }

    // ─── MediaStore registration ─────────────────────────────────────────────

    private fun registerWithMediaStore(file: File?) {
        val f = file ?: return
        // Skip empty / aborted segments — they'd just show up in MediaStore
        // as ~3KB placeholders and trigger CameraBackup uploads of garbage.
        // 50KB is "more than just an mp4 header"; smaller means encoder
        // never produced video.
        if (!f.exists() || f.length() < 50_000) {
            if (f.exists()) runCatching { f.delete() }
            return
        }
        try {
            // MediaScannerConnection scans the existing file and inserts/updates
            // its MediaStore row. This fires the observer CameraBackup listens
            // on. Doing this AND a manual MediaStore.insert() would duplicate
            // the row (with a "(1)" suffix), so we use only the scanner.
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(f.absolutePath),
                arrayOf("video/mp4"),
                null
            )
        } catch (e: Throwable) {
            Log.w(TAG, "MediaStore register failed: ${e.message}")
        }
    }

    private fun estimateBitrate(w: Int, h: Int): Int =
        // ~0.1 bits per pixel per frame at 30fps gives a reasonable mp4 size.
        (w * h * fps * 0.10).toInt().coerceAtLeast(800_000)
}
