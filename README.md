# ServerPages-Android

Unattended screen broadcaster + media server for Android. Turns any phone into a headless streaming device — captures the screen as HLS and serves it on port 3333. Browse and stream media files from any browser on your network. Designed for zero daily interaction.

## Features

- **Unattended operation** — auto-starts on boot, runs forever, no UI interaction needed
- **Live screen capture** — MediaProjection → H.264 → HLS (720p/1080p)
- **4-digit access code** — viewers must enter a code to watch the stream
- **Admin monitor** — unauthenticated stream at `/admin` for the device owner
- **Live viewer counter** — real-time viewer count on the app and admin page
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

## Access Code

On each service start, a random **4-digit code** is generated. Viewers must enter this code to access the stream and media browser. The code is shown:

- On the **app UI** — large orange digits under "VIEWER CODE"
- In the **notification** — `LIVE on http://x.x.x.x:3333 | Code: 1234`
- On the **admin page** — via the status API

Share this code with people you want to give access. The code changes every time the service restarts.

### Viewer Flow

1. Viewer opens `http://<phone-ip>:3333` in a browser
2. Redirected to the **login page** — enters the 4-digit code
3. On success, a session cookie is set and they can access the stream and media
4. Wrong code shows an error with shake animation, inputs clear for retry

### Admin Monitor

The admin stream at `http://<phone-ip>:3333/admin` requires **no authentication**. Use it to verify the stream is working without needing the access code. The admin stream is **not counted** in the viewer total.

### Viewer Counter

The app shows a live count of how many viewers are currently watching. A viewer is counted when they fetch the HLS manifest (`/hls/screen.m3u8`). Viewers are removed from the count after 30 seconds of inactivity. The admin stream uses a separate path (`/admin/hls/`) and is excluded.

## Daily Use

**Zero interaction.** The phone streams and serves files 24/7.

Open in any browser:
- `http://<phone-ip>:3333` — dashboard (requires access code)
- `http://<phone-ip>:3333/live.html` — full-screen live stream with quality toggle (requires access code)
- `http://<phone-ip>:3333/media.html` — browse and play phone media files (requires access code)
- `http://<phone-ip>:3333/admin` — admin stream monitor (no auth, excluded from viewer count)
- `http://<phone-ip>:3333/login` — login page for viewers

## After Reboot

Everything auto-starts except screen capture (Android kernel restriction). One tap required:

1. Phone boots → HTTP server starts automatically, media browsing works immediately
2. Tailscale auto-launches and reconnects to tailnet
3. Notification appears: **"tap to enable capture"**
4. Tap the notification → tap **Start now** → capture resumes
5. Runs indefinitely until next reboot

This single tap per reboot is the **only human interaction** — Android does not allow apps to record the screen without explicit user consent. There is no way around this on stock Android.

## API Routes

### Public (no auth)

| Route | Method | Description |
|---|---|---|
| `/login` | GET | Login page (4-digit code entry) |
| `/style.css` | GET | Stylesheet |
| `/api/auth` | POST | Authenticate with access code `{"code":"1234"}` |

### Admin (no auth, excluded from viewer count)

| Route | Method | Description |
|---|---|---|
| `/admin` | GET | Admin stream monitor page |
| `/admin/hls/screen.m3u8` | GET | HLS manifest (admin) |
| `/admin/hls/segNNNNN.mp4` | GET | HLS segments (admin) |
| `/admin/api/status` | GET | Server status + viewer count |

### Protected (requires access code)

| Route | Method | Description |
|---|---|---|
| `/` | GET | Dashboard |
| `/live.html` | GET | Live HLS stream player |
| `/media.html` | GET | Media file browser |
| `/hls/screen.m3u8` | GET | HLS manifest (counts as viewer) |
| `/hls/segNNNNN.mp4` | GET | HLS segments |
| `/api/status` | GET | Server status (capturing, uptime, quality, viewers) |
| `/api/files?dir=...` | GET | Directory listing (JSON) |
| `/api/stream?path=...` | GET | File streaming (Range/206 support) |
| `/api/download?path=...` | GET | File download |
| `/api/quality` | POST | Switch 720p/1080p `{"quality":"1080p"}` |

## Architecture

```
BootReceiver (BOOT_COMPLETED)
├── CaptureService (foreground service, runs forever)
│   ├── Access code (random 4-digit, generated on service start)
│   ├── WebServer (NanoHTTPd :3333)
│   │   ├── Auth layer (cookie sessions, 4-digit code validation)
│   │   ├── Viewer tracking (IP → timestamp, 30s expiry)
│   │   ├── Public: /login, /api/auth, /style.css
│   │   ├── Admin: /admin, /admin/hls/*, /admin/api/status
│   │   ├── Protected: /, /live.html, /media.html, /hls/*, /api/*
│   │   ├── Static pages (assets/web/)
│   │   └── HLS segments (cache/hls/)
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
