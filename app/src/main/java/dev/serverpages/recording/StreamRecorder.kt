package dev.serverpages.recording

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tee from WebRTC's [org.webrtc.VideoTrack] into hidden mp4 segments on disk
 * and capture mic audio in parallel via [AudioRecord], multiplexing both into
 * the same MediaMuxer.
 *
 * Output: /storage/emulated/0/Movies/.airdeck/recording_<ts>.mp4
 *
 * The folder is prefixed `.` and contains a `.nomedia` marker so galleries
 * won't index it. CameraBackup picks recordings up via direct FileObserver,
 * not MediaStore, then deletes them after successful upload.
 *
 * Each segment caps at [SEGMENT_MS] of presentation time and rolls over to
 * the next file without blocking the WebRTC frame thread.
 */
class StreamRecorder(private val context: Context) : VideoSink {

    companion object {
        private const val TAG = "StreamRecorder"
        private const val MIME_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MIME_AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val SEGMENT_MS = 30L * 60L * 1000L           // 30 minutes
        private const val FOLDER_NAME = ".airdeck"
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        // Audio config: 44.1 kHz mono 16-bit PCM → AAC LC @ 64 kbps. Wide
        // device support; small file footprint.
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_CHANNELS = 1
        private const val AUDIO_BIT_RATE = 64_000
    }

    private val drainThread = HandlerThread("StreamRecorderDrain").apply { start() }
    private val drainHandler = Handler(drainThread.looper)

    @Volatile private var running = false
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var fps = 30

    // Video pipeline.
    private var videoEncoder: MediaCodec? = null
    private var videoTrackIndex = -1
    private val videoFormatReady = AtomicBoolean(false)

    // Audio pipeline.
    private var audioEncoder: MediaCodec? = null
    private var audioTrackIndex = -1
    private val audioFormatReady = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false

    // Muxer.
    private var muxer: MediaMuxer? = null
    @Volatile private var muxerStarted = false

    // PTS tracking.
    private var firstFrameNs = -1L
    private var segmentStartNs = -1L
    private var audioSampleCount = 0L
    private var currentFile: File? = null

    private val outputRoot: File by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            FOLDER_NAME
        ).also { dir ->
            dir.mkdirs()
            // Hide from gallery / photo apps.
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        }
    }

    @Synchronized
    fun start(width: Int, height: Int, fps: Int) {
        if (running) return
        this.width = width
        this.height = height
        this.fps = fps
        firstFrameNs = -1L
        segmentStartNs = -1L
        audioSampleCount = 0L

        try {
            openSegment()
            running = true
            Log.i(TAG, "Recording started ${width}x${height}@${fps}fps + audio → ${currentFile?.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start recorder", e)
            closeQuietly()
        }
    }

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
        // Critical: WebRTC's native camera thread aborts if a sink throws.
        try {
            if (!running) return
            val enc = videoEncoder ?: return

            val nowNs = System.nanoTime()
            if (firstFrameNs < 0) {
                firstFrameNs = nowNs
                segmentStartNs = nowNs
            }
            val ptsUs = (nowNs - firstFrameNs) / 1000L

            if (nowNs - segmentStartNs >= SEGMENT_MS * 1_000_000L) {
                rolloverSegment()
                segmentStartNs = nowNs
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
                        enc.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                    }
                }
                drainHandler.post { drainEncoders(false) }
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

        val videoFormat = MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, estimateVideoBitrate(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val vEnc = MediaCodec.createEncoderByType(MIME_VIDEO)
        vEnc.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        vEnc.start()
        videoEncoder = vEnc

        val audioFormat = MediaFormat.createAudioFormat(MIME_AUDIO, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val aEnc = MediaCodec.createEncoderByType(MIME_AUDIO)
        aEnc.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aEnc.start()
        audioEncoder = aEnc

        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        videoTrackIndex = -1
        audioTrackIndex = -1
        videoFormatReady.set(false)
        audioFormatReady.set(false)
        muxerStarted = false

        // CRITICAL: start AudioRecord BEFORE any video frame can arrive and
        // race ahead with maybeStartMuxer. This guarantees audioRecord is
        // non-null by the time the muxer-readiness check runs.
        startAudioCapture()
    }

    private fun closeSegment() {
        // Stop audio capture first so its drain finishes naturally.
        stopAudioCapture()

        // Signal EOS on video encoder.
        try {
            val enc = videoEncoder
            if (enc != null) {
                val idx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (idx >= 0) enc.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (_: Throwable) {}

        // Drain everything.
        try {
            drainEncoders(endOfStream = true)
        } catch (e: Throwable) {
            Log.w(TAG, "Final drain threw: ${e.message}")
        }
        closeQuietly()
    }

    private fun rolloverSegment() {
        Log.i(TAG, "Rolling over segment: ${currentFile?.name}")
        closeSegment()
        firstFrameNs = -1L
        audioSampleCount = 0L
        openSegment()
    }

    private fun closeQuietly() {
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.release() } catch (_: Throwable) {}
        videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Throwable) {}
        try { audioEncoder?.release() } catch (_: Throwable) {}
        audioEncoder = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Throwable) {}
        try { muxer?.release() } catch (_: Throwable) {}
        muxer = null
        muxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
        videoFormatReady.set(false)
        audioFormatReady.set(false)
    }

    // ─── Audio capture ───────────────────────────────────────────────────────

    private fun startAudioCapture() {
        try {
            val channelConfig = if (AUDIO_CHANNELS == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = minBuf.coerceAtLeast(8192)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize * 4,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord init failed (state=${rec.state}) — recording video-only")
                rec.release()
                return
            }
            rec.startRecording()
            audioRecord = rec
            audioRunning = true

            audioThread = Thread({ audioCaptureLoop(bufSize) }, "StreamRecorderAudio").apply { start() }
        } catch (e: Throwable) {
            Log.w(TAG, "Audio capture failed (mic in use?) — video-only: ${e.message}")
            audioRunning = false
        }
    }

    private fun stopAudioCapture() {
        audioRunning = false
        try {
            // Signal EOS on audio encoder if still alive.
            val aEnc = audioEncoder
            if (aEnc != null) {
                val idx = aEnc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (idx >= 0) aEnc.queueInputBuffer(idx, 0, 0, audioPtsUs(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (_: Throwable) {}
        try { audioThread?.join(500) } catch (_: Throwable) {}
        audioThread = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    private fun audioCaptureLoop(bufSize: Int) {
        val buf = ByteArray(bufSize)
        while (audioRunning) {
            val rec = audioRecord ?: break
            val enc = audioEncoder ?: break
            val n = try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
            if (n <= 0) continue
            try {
                val idx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (idx >= 0) {
                    val inBuf = enc.getInputBuffer(idx) ?: continue
                    inBuf.clear()
                    inBuf.put(buf, 0, n)
                    val samples = n / 2 / AUDIO_CHANNELS  // 16-bit
                    val ptsUs = audioPtsUs(samples)
                    enc.queueInputBuffer(idx, 0, n, ptsUs, 0)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "audioCaptureLoop iteration threw: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun audioPtsUs(samplesAdded: Int): Long {
        val pts = audioSampleCount * 1_000_000L / AUDIO_SAMPLE_RATE
        audioSampleCount += samplesAdded
        return pts
    }

    // ─── Encoder drain ───────────────────────────────────────────────────────

    private val videoBufferInfo = MediaCodec.BufferInfo()
    private val audioBufferInfo = MediaCodec.BufferInfo()

    /** Pull encoded chunks from both encoders and feed them into [muxer]. */
    private fun drainEncoders(endOfStream: Boolean) {
        drainOne(videoEncoder, videoBufferInfo, isVideo = true, endOfStream = endOfStream)
        drainOne(audioEncoder, audioBufferInfo, isVideo = false, endOfStream = endOfStream)
        maybeStartMuxer()
    }

    private fun drainOne(
        enc: MediaCodec?,
        info: MediaCodec.BufferInfo,
        isVideo: Boolean,
        endOfStream: Boolean,
    ) {
        enc ?: return
        val mux = muxer ?: return
        while (true) {
            val outIdx = try {
                enc.dequeueOutputBuffer(info, if (endOfStream) DEQUEUE_TIMEOUT_US else 0)
            } catch (e: Throwable) {
                Log.w(TAG, "${if (isVideo) "video" else "audio"} dequeueOutputBuffer threw: ${e.message}")
                return
            }
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (isVideo) {
                        if (videoTrackIndex < 0) {
                            videoTrackIndex = mux.addTrack(enc.outputFormat)
                            videoFormatReady.set(true)
                        }
                    } else {
                        if (audioTrackIndex < 0) {
                            audioTrackIndex = mux.addTrack(enc.outputFormat)
                            audioFormatReady.set(true)
                        }
                    }
                    maybeStartMuxer()
                }
                outIdx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(outIdx)
                    val isCodecConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (outBuf != null && info.size > 0 && muxerStarted && !isCodecConfig) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val track = if (isVideo) videoTrackIndex else audioTrackIndex
                        if (track >= 0) {
                            try {
                                mux.writeSampleData(track, outBuf, info)
                            } catch (e: Throwable) {
                                Log.w(TAG, "writeSampleData(${if (isVideo) "video" else "audio"}) threw: ${e.message}")
                            }
                        }
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    @Synchronized
    private fun maybeStartMuxer() {
        if (muxerStarted) return
        if (!videoFormatReady.get()) return
        // Audio is best-effort — start muxer with video-only if audio failed
        // to initialize at all (no audio encoder, no AudioRecord).
        val audioRequired = audioRecord != null
        if (audioRequired && !audioFormatReady.get()) return
        try {
            muxer?.start()
            muxerStarted = true
            Log.i(TAG, "Muxer started (video=$videoTrackIndex audio=$audioTrackIndex)")
        } catch (e: Throwable) {
            Log.e(TAG, "muxer.start failed", e)
        }
    }

    // ─── YUV plumbing ────────────────────────────────────────────────────────

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
            val readLen = minOf(srcStride, src.remaining())
            src.get(rowBuf, 0, readLen)
            val dstRowStart = row * dstRowStride
            if (dstPixelStride == 1) {
                dstBuf.position(dstRowStart)
                val safeW = minOf(w, dstBuf.remaining())
                dstBuf.put(rowBuf, 0, safeW)
                bytesWritten += safeW
            } else {
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

    private fun estimateVideoBitrate(w: Int, h: Int): Int =
        (w * h * fps * 0.10).toInt().coerceAtLeast(800_000)
}
