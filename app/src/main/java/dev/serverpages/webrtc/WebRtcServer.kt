package dev.serverpages.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.webrtc.*
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

    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private var useFrontCamera = true
    private var audioEnabled = true

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

    fun switchCamera() {
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

        // Stop camera
        try {
            capturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping capturer", e)
        }
        capturer?.dispose()
        capturer = null

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
