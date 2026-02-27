# ServerPages-Android

Screen broadcaster + media server for Android. Captures the phone's screen as HLS, serves it via an embedded HTTP server on port 3333. Browse and stream media files from any browser on your network.

## Features

- **Live screen capture** — MediaProjection → H.264 → HLS (720p/1080p)
- **Media browser** — browse DCIM, Pictures, Videos, Music, Downloads
- **Embedded HTTP server** — NanoHTTPd on port 3333, no external dependencies
- **Boot auto-start** — HTTP server starts on boot, screen capture needs one tap
- **Quality toggle** — switch 720p/1080p from the browser
- **Dark theme** — matches the Windows ServerPages UI

## Requirements

- Android 10+ (API 29+)
- Target SDK 33

## Setup

1. Open in Android Studio
2. Build and install the APK
3. Grant permissions (storage, notifications)
4. Tap "Start with Capture" → grant screen capture permission
5. Open `http://<phone-ip>:3333` in any browser

## Boot Behavior

After reboot:
1. HTTP server auto-starts (media browsing works immediately)
2. Notification: "Tap to enable screen capture"
3. Tap notification → grant MediaProjection permission → capture resumes
4. Runs indefinitely until next reboot or force-kill

## Remote Access (Tailscale)

1. Install Tailscale on the phone
2. Run: `tailscale funnel 3333`
3. Access via your Tailscale URL from anywhere

## Architecture

```
CaptureService (foreground service)
├── WebServer (NanoHTTPd :3333)
│   ├── Static pages (assets/web/)
│   ├── HLS segments (cache/hls/)
│   ├── /api/files, /api/stream, /api/download
│   └── /api/status, /api/quality
├── ScreenCapture (MediaProjection → MediaCodec → HlsWriter)
│   └── HlsWriter (MP4 segments + m3u8 manifest)
└── BootReceiver (BOOT_COMPLETED → server-only mode)
```

## Dependencies

- NanoHTTPd 2.3.1 (HTTP server)
- Hilt 2.53.1 (DI)
- Jetpack Compose (minimal control UI)
- GSON 2.11.0 (JSON)
