# ServerPages-Android

Unattended screen broadcaster + media server for Android. Turns any phone into a headless streaming device — captures the screen as HLS and serves it on port 3333. Browse and stream media files from any browser on your network. Designed for zero daily interaction.

## Features

- **Unattended operation** — auto-starts on boot, runs forever, no UI interaction needed
- **Live screen capture** — MediaProjection → H.264 → HLS (720p/1080p)
- **Media browser** — browse DCIM, Pictures, Videos, Music, Downloads
- **Embedded HTTP server** — NanoHTTPd on port 3333, no external dependencies
- **Tailscale integration** — auto-launches Tailscale, detects tailnet IP, enables remote access
- **Quality toggle** — switch 720p/1080p from the browser
- **Dark theme** — matches the Windows ServerPages UI

## One-Time Setup

### 1. Install APK

```
adb install app-debug.apk
```

Or copy APK to phone and open it.

### 2. First Launch

Open the app. It will:
1. Request permissions (storage, notifications) — tap **Allow**
2. Request screen capture — tap **Start now**
3. Start the HTTP server + screen capture
4. Disappear to background automatically

That's it. The phone is now streaming.

### 3. Tailscale (optional, for remote access)

1. Install [Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn) from Play Store
2. Open Tailscale, log in to your tailnet
3. Go to **Android Settings → Network → VPN → Tailscale → Always-on VPN** (enable)
4. ServerPages auto-detects and auto-launches Tailscale on every boot — no further setup needed
5. Access from any device on your tailnet: `http://100.x.x.x:3333`

For public internet access, enable [Tailscale Funnel](https://tailscale.com/kb/1223/funnel) from the admin console for this device.

## Daily Use

**Zero interaction.** The phone streams and serves files 24/7.

Open in any browser:
- `http://<phone-ip>:3333` — dashboard with stream preview + media browser
- `http://<phone-ip>:3333/live.html` — full-screen live stream with quality toggle
- `http://<phone-ip>:3333/media.html` — browse and play phone media files

## After Reboot

Everything auto-starts except screen capture (Android kernel restriction). One tap required:

1. Phone boots → HTTP server starts automatically, media browsing works immediately
2. Tailscale auto-launches and reconnects to tailnet
3. Notification appears: **"tap to enable capture"**
4. Tap the notification → tap **Start now** → capture resumes
5. Runs indefinitely until next reboot

This single tap per reboot is the **only human interaction** — Android does not allow apps to record the screen without explicit user consent. There is no way around this on stock Android.

## API Routes

| Route | Method | Description |
|---|---|---|
| `/` | GET | Dashboard |
| `/live.html` | GET | Live HLS stream player |
| `/media.html` | GET | Media file browser |
| `/hls/screen.m3u8` | GET | HLS manifest |
| `/hls/segNNNNN.mp4` | GET | HLS segments |
| `/api/status` | GET | Server status (capturing, uptime, quality) |
| `/api/files?dir=...` | GET | Directory listing (JSON) |
| `/api/stream?path=...` | GET | File streaming (Range/206 support) |
| `/api/download?path=...` | GET | File download |
| `/api/quality` | POST | Switch 720p/1080p `{"quality":"1080p"}` |

## Architecture

```
BootReceiver (BOOT_COMPLETED)
├── CaptureService (foreground service, runs forever)
│   ├── WebServer (NanoHTTPd :3333)
│   │   ├── Static pages (assets/web/)
│   │   ├── HLS segments (cache/hls/)
│   │   └── API routes (files, stream, download, status, quality)
│   ├── ScreenCapture (MediaProjection → VirtualDisplay → MediaCodec → HlsWriter)
│   │   └── HlsWriter (standalone MP4 segments + m3u8 manifest)
│   └── Tailscale (auto-detect, auto-launch, IP discovery)
└── Notification → tap → MainActivity → MediaProjection permission → back to background
```

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | HTTP server |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Background capture |
| `WAKE_LOCK` | Prevent CPU sleep |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on boot |
| `POST_NOTIFICATIONS` | Foreground service notification |
| `READ_MEDIA_*` / `READ_EXTERNAL_STORAGE` | Media file browser |

## Requirements

- Android 10+ (API 29)
- Target SDK 33
- Tailscale app (optional, for remote access)

## Dependencies

- NanoHTTPd 2.3.1 (embedded HTTP server, ~40KB)
- Hilt 2.53.1 (dependency injection)
- Jetpack Compose (minimal status UI)
- GSON 2.11.0 (JSON serialization)

No Firebase, Room, Retrofit, or WorkManager. Intentionally minimal.
