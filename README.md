# ServerPages-Android

Unattended screen broadcaster + media server for Android. Turns any phone into a headless streaming device — captures the screen as HLS and serves it on port 3333. Browse and stream media files from any browser on your network. Designed for zero daily interaction.

## Features

- **Unattended operation** — auto-starts on boot, runs forever, no UI interaction needed
- **Live screen capture** — MediaProjection → H.264 → HLS (720p/1080p)
- **4-digit access code** — viewers must enter a code to watch the stream
- **Admin monitor** — unauthenticated stream + media browser at `/admin` for the device owner
- **Live viewer counter** — real-time viewer count on the app and admin page
- **Content Mode** — play media files full-screen for viewers when phone is unattended
- **Media browser** — browse DCIM, Pictures, Videos, Music, Downloads (admin only)
- **Folder download** — download entire folders as zip (admin only)
- **Embedded HTTP server** — NanoHTTPd on port 3333, no external dependencies
- **Tailscale integration** — auto-launches Tailscale, detects tailnet IP, shows URL in notification + admin page
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

The Tailscale URL is shown in three places once detected:
- **Notification** — second line below the LAN URL
- **Admin page** — green text in the stream info bar
- **App screen** — Tailscale card at the bottom

For public internet access, enable [Tailscale Funnel](https://tailscale.com/kb/1223/funnel) from the admin console for this device.

## Access Code

On each service start, a random **4-digit code** is generated. Viewers must enter this code to watch the live stream. The code is shown:

- On the **app UI** — large orange digits under "VIEWER CODE"
- In the **notification** — `LIVE on http://x.x.x.x:3333 | Code: 1234` (+ Tailscale URL if detected)
- On the **admin page** — via the status API

Share this code with people you want to give access. The code changes every time the service restarts.

### Viewer Flow

1. Viewer opens `http://<phone-ip>:3333` in a browser
2. Redirected to the **login page** — enters the 4-digit code
3. On success, a session cookie is set and they can watch the live stream
4. Wrong code shows an error with shake animation, inputs clear for retry

### Admin Monitor

The admin page at `http://<phone-ip>:3333/admin` requires **no authentication**. It provides:

- **Stream monitor** — verify the stream is working without needing the access code
- **Media browser** — browse and play files from DCIM, Pictures, Videos, Music, Downloads

The admin stream is **not counted** in the viewer total. Media browsing is only available through the admin page — authenticated viewers can only watch the live stream.

### Viewer Counter

The app shows a live count of how many viewers are currently watching. A viewer is counted when they fetch the HLS manifest (`/hls/screen.m3u8`). Viewers are removed from the count after 30 seconds of inactivity. The admin stream uses a separate path (`/admin/hls/`) and is excluded.

**Note:** For 10+ viewers, use the Windows version of ServerPages on a computer. A phone's network and CPU are not designed for high concurrent viewer counts.

## Content Mode

When the phone is left unattended, the screen turns off and viewers see a black stream. **Content Mode** solves this by playing media files full-screen with the screen kept on.

Tap **Content Mode** on the app screen to start. The phone will:

1. Keep the screen on permanently (`FLAG_KEEP_SCREEN_ON`)
2. Go full-screen immersive (landscape, no status/nav bars)
3. Cycle through all images and videos from DCIM, Pictures, Movies, Downloads
4. Images display for 8 seconds, videos play to completion
5. Playlist is shuffled and loops forever
6. Audio-only files are skipped (nothing visual to capture)

MediaProjection captures the full-screen content and streams it to viewers via HLS.

**Tap anywhere** on the screen to exit Content Mode and return to the app.

## Daily Use

**Zero interaction.** The phone streams and serves files 24/7.

Open in any browser:
- `http://<phone-ip>:3333` — viewer dashboard with stream preview (requires access code)
- `http://<phone-ip>:3333/live.html` — full-screen live stream with quality toggle (requires access code)
- `http://<phone-ip>:3333/admin` — admin: stream monitor + media browser (no auth, excluded from viewer count)
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
| `/admin/media.html` | GET | Media file browser |
| `/admin/hls/screen.m3u8` | GET | HLS manifest (admin) |
| `/admin/hls/segNNNNN.mp4` | GET | HLS segments (admin) |
| `/admin/api/status` | GET | Server status + viewer count + Tailscale URL |
| `/admin/api/files?dir=...` | GET | Directory listing (JSON) |
| `/admin/api/stream?path=...` | GET | File streaming (Range/206 support) |
| `/admin/api/download?path=...` | GET | File download |
| `/admin/api/download-folder?dir=...` | GET | Download folder as zip |

### Protected (requires access code)

| Route | Method | Description |
|---|---|---|
| `/` | GET | Dashboard (stream preview) |
| `/live.html` | GET | Live HLS stream player |
| `/hls/screen.m3u8` | GET | HLS manifest (counts as viewer) |
| `/hls/segNNNNN.mp4` | GET | HLS segments |
| `/api/status` | GET | Server status (capturing, uptime, quality, viewers, tailscaleUrl) |
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
│   │   ├── Admin: /admin, /admin/media.html, /admin/hls/*, /admin/api/*
│   │   ├── Protected: /, /live.html, /hls/*, /api/status, /api/quality
│   │   ├── Static pages (assets/web/)
│   │   └── HLS segments (cache/hls/)
│   ├── ScreenCapture (MediaProjection → VirtualDisplay → MediaCodec → HlsWriter)
│   │   └── HlsWriter (standalone MP4 segments + m3u8 manifest)
│   └── Tailscale (auto-detect, auto-launch, IP discovery)
├── ContentPlayerActivity (full-screen media playback, keeps screen on)
│   └── Cycles images (8s) + videos (to completion), shuffled, looping
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
