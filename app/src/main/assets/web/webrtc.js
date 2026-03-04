/**
 * AirDeck WebRTC client — P2P video streaming.
 *
 * Flow:
 * 1. Fetch /api/webrtc/status for ICE server config
 * 2. Create RTCPeerConnection with STUN servers
 * 3. Add recvonly transceivers for video + audio
 * 4. Create offer, wait for ICE gathering complete
 * 5. POST complete offer to /api/webrtc/offer
 * 6. Set returned SDP answer as remote description
 * 7. pc.ontrack -> attach stream to <video> element
 */
class AirDeckWebRTC {
  constructor(videoElement, opts = {}) {
    this.video = videoElement;
    this.pc = null;
    this.viewerId = null;
    this.onConnected = opts.onConnected || (() => {});
    this.onDisconnected = opts.onDisconnected || (() => {});
    this.onError = opts.onError || (() => {});
    this._closed = false;
  }

  async start() {
    try {
      // 1. Get ICE config from server
      const statusRes = await fetch('/api/webrtc/status', { credentials: 'include' });
      if (!statusRes.ok) throw new Error('WebRTC not available');
      const status = await statusRes.json();
      if (!status.webrtc) throw new Error('WebRTC not active on server');

      const iceServers = (status.iceServers || []).map(s => ({ urls: s.urls }));

      // 2. Create peer connection
      this.pc = new RTCPeerConnection({ iceServers });

      // 3. Add recvonly transceivers
      this.pc.addTransceiver('video', { direction: 'recvonly' });
      this.pc.addTransceiver('audio', { direction: 'recvonly' });

      // 7. Handle incoming tracks — use event.streams[0] from SDP msid
      this.pc.ontrack = (event) => {
        // Use the stream from the SDP a=msid line
        if (event.streams && event.streams[0]) {
          if (this.video.srcObject !== event.streams[0]) {
            this.video.srcObject = event.streams[0];
          }
        } else {
          if (!this._stream) {
            this._stream = new MediaStream();
            this.video.srcObject = this._stream;
          }
          this._stream.addTrack(event.track);
        }

        event.track.onunmute = () => {
          this.video.play().catch(() => {});
        };
        this.video.play().catch(() => {});
      };

      // Connection state monitoring
      this.pc.onconnectionstatechange = () => {
        const state = this.pc ? this.pc.connectionState : 'null';
        console.log('[AirDeck] connectionState:', state);
        if (state === 'connected') {
          this.onConnected();
        } else if (state === 'disconnected' || state === 'failed' || state === 'closed') {
          this.onDisconnected();
        }
      };

      this.pc.oniceconnectionstatechange = () => {
        const state = this.pc ? this.pc.iceConnectionState : 'null';
        if (state === 'failed') {
          this.onError('ICE connection failed');
        }
      };

      // 4. Create offer and wait for ICE gathering
      const offer = await this.pc.createOffer();
      await this.pc.setLocalDescription(offer);

      // Wait for ICE gathering complete
      await this._waitForIceGathering();

      if (this._closed) return;

      // 5. POST complete offer to server
      const offerSdp = this.pc.localDescription.sdp;
      const offerRes = await fetch('/api/webrtc/offer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ sdp: offerSdp })
      });

      if (!offerRes.ok) throw new Error('Offer rejected: ' + offerRes.status);
      const answerData = await offerRes.json();

      if (answerData.error) throw new Error(answerData.error);

      this.viewerId = answerData.viewerId;

      // 6. Set remote answer
      await this.pc.setRemoteDescription({
        type: 'answer',
        sdp: answerData.sdp
      });

      // Auto-play
      this.video.play().catch(() => {});

    } catch (err) {
      this.onError(err.message || 'WebRTC failed');
      throw err;
    }
  }

  _waitForIceGathering() {
    return new Promise((resolve) => {
      if (this.pc.iceGatheringState === 'complete') {
        resolve();
        return;
      }
      const timeout = setTimeout(() => {
        // Proceed with whatever candidates we have after 5s
        resolve();
      }, 5000);

      this.pc.onicegatheringstatechange = () => {
        if (this.pc.iceGatheringState === 'complete') {
          clearTimeout(timeout);
          resolve();
        }
      };
    });
  }

  stop() {
    this._closed = true;
    if (this.pc) {
      this.pc.close();
      this.pc = null;
    }
    // Notify server
    if (this.viewerId) {
      try {
        navigator.sendBeacon('/api/webrtc/hangup',
          new Blob([JSON.stringify({ viewerId: this.viewerId })],
            { type: 'application/json' }));
      } catch (e) {}
      this.viewerId = null;
    }
    if (this.video) {
      this.video.srcObject = null;
    }
  }
}

// Clean up on page unload
window.addEventListener('beforeunload', () => {
  if (window._airdeckWebRTC) {
    window._airdeckWebRTC.stop();
  }
});
