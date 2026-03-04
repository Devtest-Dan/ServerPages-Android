package dev.serverpages.webrtc

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages WebRTC peer connections for P2P live streaming.
 * Camera is owned by WebRTC's Camera2Capturer — only one client can open Camera2 at a time.
 * STUN only (no TURN) — if P2P fails, viewer falls back to HLS.
 */
class WebRtcServer(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcServer"
        private const val VIDEO_TRACK_ID = "airdeck-video"
        private const val AUDIO_TRACK_ID = "airdeck-audio"
        private const val LOCAL_STREAM_ID = "airdeck-stream"
        private const val ICE_GATHER_TIMEOUT_SEC = 3L

        val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )

        val ICE_SERVER_URLS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"
        )
    }

    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var capturer: Camera2Capturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // Screen capture via ImageReader (reliable frame delivery)
    private var screenProjection: MediaProjection? = null
    private var screenVirtualDisplay: VirtualDisplay? = null
    private var screenImageReader: ImageReader? = null
    private var screenThread: HandlerThread? = null
    private var screenHandler: Handler? = null
    private var screenFrameCount = 0
    @Volatile private var cachedScreenY: ByteBuffer? = null
    @Volatile private var cachedScreenU: ByteBuffer? = null
    @Volatile private var cachedScreenV: ByteBuffer? = null
    private var screenRepeatTimer: java.util.Timer? = null

    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private var useFrontCamera = true
    private var audioEnabled = true
    var currentSource: String = "camera"
        private set
    private var captureWidth = 1280
    private var captureHeight = 720
    private var captureFps = 30

    @Volatile
    var isRunning = false
        private set

    fun getEglBase(): EglBase? = eglBase

    fun initialize() {
        if (factory != null) return

        eglBase = EglBase.create()

        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.i(TAG, "PeerConnectionFactory initialized")
    }

    fun startCamera(width: Int = 1280, height: Int = 720, fps: Int = 30) {
        val f = factory ?: run { Log.e(TAG, "Factory not initialized"); return }

        captureWidth = width
        captureHeight = height
        captureFps = fps

        // Video source + capturer
        videoSource = f.createVideoSource(false)
        surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCaptureThread", eglBase!!.eglBaseContext)

        val cameraEnumerator = Camera2Enumerator(context)
        val cameraName = findCamera(cameraEnumerator, useFrontCamera)
            ?: run { Log.e(TAG, "No camera found"); return }

        capturer = Camera2Capturer(context, cameraName, null)
        capturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(width, height, fps)

        localVideoTrack = f.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        localVideoTrack!!.setEnabled(true)

        // Audio source
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = f.createAudioSource(audioConstraints)
        localAudioTrack = f.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack!!.setEnabled(audioEnabled)

        isRunning = true
        Log.i(TAG, "Camera started: ${width}x${height}@${fps}fps, front=$useFrontCamera")
    }

    fun setLocalRenderer(renderer: SurfaceViewRenderer?) {
        if (renderer != null) {
            localVideoTrack?.addSink(renderer)
        } else {
            localVideoTrack?.let { track ->
                // Removing sinks is handled by the renderer's release
            }
        }
    }

    fun removeRendererSink(renderer: SurfaceViewRenderer) {
        localVideoTrack?.removeSink(renderer)
    }

    /**
     * Handle an SDP offer from a viewer. Creates a PeerConnection, adds local tracks,
     * sets the remote offer, creates an answer, waits for ICE gathering to complete,
     * and returns the complete SDP answer.
     */
    fun handleOffer(viewerId: String, sdpOffer: String): String? {
        val f = factory ?: return null
        if (!isRunning) return null

        // Remove existing peer if reconnecting
        removePeer(viewerId)

        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val iceGatherLatch = CountDownLatch(1)
        val wasConnected = AtomicBoolean(false)

        val observer = object : PeerConnection.Observer {
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    Log.d(TAG, "[$viewerId] ICE gathering complete")
                    iceGatherLatch.countDown()
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    Log.d(TAG, "[$viewerId] ICE candidate: ${candidate.sdp}")
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "[$viewerId] ICE connection: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        wasConnected.set(true)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        // Transient — ICE can recover. Do NOT remove peer.
                        Log.w(TAG, "[$viewerId] ICE disconnected (transient, keeping peer)")
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        // Terminal — clean up only if previously connected
                        if (wasConnected.get()) {
                            Log.w(TAG, "[$viewerId] ICE failed after connection, removing peer")
                            removePeer(viewerId)
                        }
                    }
                    else -> {}
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "[$viewerId] Connection state: $state")
            }
        }

        val pc = f.createPeerConnection(rtcConfig, observer) ?: run {
            Log.e(TAG, "[$viewerId] Failed to create PeerConnection")
            return null
        }

        peers[viewerId] = pc

        // Set remote offer FIRST — creates transceivers from the browser's offer
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)
        val setRemoteLatch = CountDownLatch(1)
        var setRemoteSuccess = false

        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                setRemoteSuccess = true
                Log.d(TAG, "[$viewerId] setRemoteDescription success")
                setRemoteLatch.countDown()
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[$viewerId] setRemoteDescription failed: $error")
                setRemoteLatch.countDown()
            }
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, offer)

        if (!setRemoteLatch.await(5, TimeUnit.SECONDS) || !setRemoteSuccess) {
            removePeer(viewerId)
            return null
        }

        // Add local tracks — reuses transceivers created by the offer
        localVideoTrack?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID)) }
        localAudioTrack?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID)) }
        Log.d(TAG, "[$viewerId] Tracks added, transceivers=${pc.transceivers.size}")

        // Create answer
        val answerLatch = CountDownLatch(1)
        var answerSdp: String? = null

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            answerSdp = sdp.description
                            answerLatch.countDown()
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "[$viewerId] setLocalDescription failed: $error")
                            answerLatch.countDown()
                        }
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    }, sdp)
                } else {
                    answerLatch.countDown()
                }
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "[$viewerId] createAnswer failed: $error")
                answerLatch.countDown()
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())

        if (!answerLatch.await(5, TimeUnit.SECONDS) || answerSdp == null) {
            removePeer(viewerId)
            return null
        }

        // Wait for ICE gathering to complete (returns complete SDP with all candidates)
        if (!iceGatherLatch.await(ICE_GATHER_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            Log.w(TAG, "[$viewerId] ICE gathering timed out, returning partial answer")
        }

        // Return the final SDP with gathered ICE candidates
        val finalSdp = pc.localDescription?.description ?: answerSdp
        Log.i(TAG, "[$viewerId] Answer ready, peers=${peers.size}")
        return finalSdp
    }

    fun removePeer(viewerId: String) {
        peers.remove(viewerId)?.let { pc ->
            try {
                pc.close()
                pc.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing peer $viewerId", e)
            }
            Log.d(TAG, "[$viewerId] Peer removed, remaining=${peers.size}")
        }
    }

    fun getPeerCount(): Int = peers.size

    fun switchToScreen(projection: MediaProjection) {
        val f = factory ?: run { Log.e(TAG, "Factory not initialized"); return }

        // Stop camera capturer
        try { capturer?.stopCapture() } catch (e: Exception) { Log.w(TAG, "Error stopping camera capturer", e) }
        capturer?.dispose()
        capturer = null

        // Save old chain to dispose after replacement
        val oldTrack = localVideoTrack
        val oldSource = videoSource
        val oldHelper = surfaceTextureHelper

        // Create fresh video source (isScreencast=true for encoding hints)
        val newSource = f.createVideoSource(true)
        newSource.capturerObserver.onCapturerStarted(true)

        // ImageReader for reliable frame delivery from VirtualDisplay
        screenThread = HandlerThread("ScreenCaptureThread").apply { start() }
        screenHandler = Handler(screenThread!!.looper)

        val w = captureWidth
        val h = captureHeight

        // VirtualDisplay only produces frames on screen change — we need to
        // cache the last I420 data and re-push it at target FPS for continuous streaming
        screenImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        screenFrameCount = 0
        // Pre-allocate direct ByteBuffers for I420 caching
        val yBuf = ByteBuffer.allocateDirect(w * h)
        val uBuf = ByteBuffer.allocateDirect((w / 2) * (h / 2))
        val vBuf = ByteBuffer.allocateDirect((w / 2) * (h / 2))
        screenImageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            screenFrameCount++
            if (screenFrameCount <= 5 || screenFrameCount % 60 == 0) {
                Log.i(TAG, "Screen frame #$screenFrameCount (new from VirtualDisplay)")
            }
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride

                // Convert RGBA to I420 into direct ByteBuffers
                synchronized(this@WebRtcServer) {
                    yBuf.clear(); uBuf.clear(); vBuf.clear()
                    rgbaToI420Direct(buffer, rowStride, yBuf, uBuf, vBuf, w, h)
                    yBuf.flip(); uBuf.flip(); vBuf.flip()
                    cachedScreenY = yBuf
                    cachedScreenU = uBuf
                    cachedScreenV = vBuf
                }

                // Push to WebRTC
                val i420Buffer = JavaI420Buffer.wrap(w, h,
                    yBuf.slice(), w,
                    uBuf.slice(), w / 2,
                    vBuf.slice(), w / 2, null)
                val videoFrame = VideoFrame(i420Buffer, 0, System.nanoTime())
                newSource.capturerObserver.onFrameCaptured(videoFrame)
                videoFrame.release()
            } catch (e: Exception) {
                Log.w(TAG, "Screen frame error", e)
            } finally {
                image.close()
            }
        }, screenHandler)

        // Use the live MediaProjection created at startup (not stale Intent data)
        screenProjection = projection
        screenProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped externally")
            }
        }, screenHandler)

        val metrics = context.resources.displayMetrics
        screenVirtualDisplay = screenProjection!!.createVirtualDisplay(
            "AirDeck_Screen", w, h,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            screenImageReader!!.surface,
            null, screenHandler
        )
        Log.i(TAG, "VirtualDisplay created: ${w}x${h}@${metrics.densityDpi}dpi")

        videoSource = newSource
        localVideoTrack = f.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        localVideoTrack!!.setEnabled(true)

        // Replace track on all existing peer senders (no renegotiation needed)
        replaceVideoTrackOnPeers(localVideoTrack!!)

        // Start a timer that re-pushes the last frame at ~10fps to keep the stream alive
        // (VirtualDisplay only sends frames when screen content changes)
        val src = newSource
        screenRepeatTimer = java.util.Timer("ScreenRepeat").also { timer ->
            timer.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    try {
                        synchronized(this@WebRtcServer) {
                            val y = cachedScreenY ?: return
                            val u = cachedScreenU ?: return
                            val v = cachedScreenV ?: return
                            val buf = JavaI420Buffer.wrap(w, h,
                                y.slice(), w,
                                u.slice(), w / 2,
                                v.slice(), w / 2, null)
                            val frame = VideoFrame(buf, 0, System.nanoTime())
                            src.capturerObserver.onFrameCaptured(frame)
                            frame.release()
                        }
                    } catch (_: Exception) {}
                }
            }, 100L, 100L)  // 10fps repeat
        }

        // Dispose old camera chain
        oldTrack?.dispose()
        oldSource?.dispose()
        oldHelper?.dispose()
        surfaceTextureHelper = null

        currentSource = "screen"
        Log.i(TAG, "Switched to screen capture: ${w}x${h} (repeat at 10fps)")
    }

    /**
     * Convert RGBA pixels from ImageReader plane to I420 direct ByteBuffers for WebRTC.
     * Buffers must be cleared before calling; will be filled with data (position at end).
     */
    private fun rgbaToI420Direct(
        rgba: ByteBuffer, rowStride: Int,
        yBuf: ByteBuffer, uBuf: ByteBuffer, vBuf: ByteBuffer,
        width: Int, height: Int
    ) {
        for (y in 0 until height) {
            val rowOff = y * rowStride
            for (x in 0 until width) {
                val px = rowOff + x * 4
                val r = rgba.get(px).toInt() and 0xFF
                val g = rgba.get(px + 1).toInt() and 0xFF
                val b = rgba.get(px + 2).toInt() and 0xFF

                // BT.601 full-range
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(yVal.coerceIn(0, 255).toByte())

                if (y % 2 == 0 && x % 2 == 0) {
                    val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uBuf.put(uVal.coerceIn(0, 255).toByte())
                    vBuf.put(vVal.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    fun switchToCamera() {
        val f = factory ?: run { Log.e(TAG, "Factory not initialized"); return }

        // Stop screen capture
        stopScreenCapture()

        // Save old chain
        val oldTrack = localVideoTrack
        val oldSource = videoSource

        // Create fresh video chain — isScreencast=false for camera
        surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCaptureThread", eglBase!!.eglBaseContext)
        videoSource = f.createVideoSource(false)

        val cameraEnumerator = Camera2Enumerator(context)
        val cameraName = findCamera(cameraEnumerator, useFrontCamera)
            ?: run { Log.e(TAG, "No camera found for switch"); return }

        capturer = Camera2Capturer(context, cameraName, null)
        capturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(captureWidth, captureHeight, captureFps)

        localVideoTrack = f.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        localVideoTrack!!.setEnabled(true)

        // Replace track on all existing peer senders
        replaceVideoTrackOnPeers(localVideoTrack!!)

        // Dispose old chain
        oldTrack?.dispose()
        oldSource?.dispose()

        currentSource = "camera"
        Log.i(TAG, "Switched to camera: ${captureWidth}x${captureHeight}@${captureFps}fps, front=$useFrontCamera")
    }

    private fun stopScreenCapture() {
        screenRepeatTimer?.cancel()
        screenRepeatTimer = null
        synchronized(this) {
            cachedScreenY = null
            cachedScreenU = null
            cachedScreenV = null
        }
        screenVirtualDisplay?.release()
        screenVirtualDisplay = null
        screenImageReader?.close()
        screenImageReader = null
        // Don't stop screenProjection — it's owned by CaptureService and reusable
        screenProjection = null
        screenThread?.quitSafely()
        screenThread = null
        screenHandler = null
    }

    private fun replaceVideoTrackOnPeers(newTrack: VideoTrack) {
        for ((id, pc) in peers) {
            try {
                for (sender in pc.senders) {
                    if (sender.track()?.kind() == "video") {
                        sender.setTrack(newTrack, false)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$id] Failed to replace video track", e)
            }
        }
        Log.d(TAG, "Replaced video track on ${peers.size} peers")
    }

    fun switchCamera() {
        if (currentSource != "camera") {
            Log.w(TAG, "switchCamera() ignored — currently on $currentSource")
            return
        }
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                useFrontCamera = isFront
                Log.i(TAG, "Camera switched to ${if (isFront) "front" else "back"}")
            }
            override fun onCameraSwitchError(error: String?) {
                Log.e(TAG, "Camera switch failed: $error")
            }
        })
    }

    fun isFrontCamera(): Boolean = useFrontCamera

    fun toggleAudio(): Boolean {
        audioEnabled = !audioEnabled
        localAudioTrack?.setEnabled(audioEnabled)
        Log.i(TAG, "Audio ${if (audioEnabled) "enabled" else "muted"}")
        return audioEnabled
    }

    fun isAudioEnabled(): Boolean = audioEnabled

    fun stop() {
        isRunning = false

        // Close all peers
        peers.keys.toList().forEach { removePeer(it) }

        // Stop camera/screen capturer
        try {
            capturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping camera capturer", e)
        }
        capturer?.dispose()
        capturer = null

        stopScreenCapture()
        currentSource = "camera"

        localVideoTrack?.dispose()
        localVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null

        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        factory?.dispose()
        factory = null

        eglBase?.release()
        eglBase = null

        Log.i(TAG, "WebRtcServer stopped")
    }

    private fun findCamera(enumerator: Camera2Enumerator, preferFront: Boolean): String? {
        val names = enumerator.deviceNames
        // First try preferred side
        for (name in names) {
            if (preferFront && enumerator.isFrontFacing(name)) return name
            if (!preferFront && enumerator.isBackFacing(name)) return name
        }
        // Fallback to any camera
        return names.firstOrNull()
    }
}
