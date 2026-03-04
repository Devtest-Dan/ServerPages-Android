package dev.serverpages.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import dev.serverpages.capture.QualityPreset
import dev.serverpages.webrtc.WebRtcServer
import java.io.File
import java.io.FileInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Embedded HTTP server on port 3333.
 *
 * Auth: 4-digit code (10 unique codes) -> cookie session. Admin path bypasses auth.
 * Viewer tracking: counts unique IPs fetching /hls/screen.m3u8 (excludes admin).
 * Chat: 1-to-many private conversations (viewer <-> streamer only).
 */
@Suppress("DEPRECATION")
class WebServer(
    private val context: Context,
    private val hlsDir: File,
    port: Int = 3333
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "WebServer"
        private const val SESSION_COOKIE = "sp_token"
        private const val VIEWER_TIMEOUT_MS = 30_000L
    }

    private val gson = Gson()
    private var startTimeMs = System.currentTimeMillis()

    // Callbacks from CaptureService
    var onQualityChange: ((String) -> Boolean)? = null
    var onCameraSwitch: (() -> Boolean)? = null
    var getCaptureState: (() -> Boolean)? = null
    var getCurrentQuality: (() -> String)? = null
    var getCameraFacing: (() -> String)? = null
    var getTailscaleUrl: (() -> String)? = null
    var getPublicUrl: (() -> String)? = null
    var getWebRtcServer: (() -> WebRtcServer?)? = null

    // Multi-code auth
    var accessCodes: List<CodeInfo> = emptyList()
    private val tokenToCode = ConcurrentHashMap<String, CodeInfo>()
    private val validTokens = ConcurrentHashMap.newKeySet<String>()

    // Chat: code -> messages
    val conversations = ConcurrentHashMap<String, MutableList<ChatMessage>>()

    // Viewer tracking: IP -> last seen timestamp
    private val viewers = ConcurrentHashMap<String, Long>()

    fun getViewerCount(): Int {
        val cutoff = System.currentTimeMillis() - VIEWER_TIMEOUT_MS
        viewers.entries.removeIf { it.value < cutoff }
        return viewers.size
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method
        val clientIp = session.remoteIpAddress ?: "unknown"

        return try {
            when {
                // --- Public routes (no auth) ---
                uri == "/login" || uri == "/login.html" ->
                    serveAsset("web/login.html", "text/html")
                uri == "/style.css" ->
                    serveAsset("web/style.css", "text/css")
                uri == "/api/auth" && method == Method.POST ->
                    handleAuth(session)

                // --- Admin routes (no auth, excluded from viewer count) ---
                uri == "/admin" || uri == "/admin/" || uri == "/admin/live.html" ->
                    serveAsset("web/admin.html", "text/html")
                uri == "/admin/media.html" ->
                    serveAsset("web/media.html", "text/html")
                uri == "/admin/preview.html" ->
                    serveAsset("web/preview.html", "text/html")
                uri.startsWith("/admin/hls/") ->
                    handleHls(uri.removePrefix("/admin/hls/"))
                uri == "/admin/api/status" ->
                    handleStatus()
                uri == "/admin/api/camera" && method == Method.POST ->
                    handleCameraSwitch()
                uri == "/admin/api/source" && method == Method.POST ->
                    handleSourceSwitch(session)
                uri == "/admin/api/files" && method == Method.GET ->
                    handleFiles(session)
                uri == "/admin/api/stream" && method == Method.GET ->
                    handleStream(session)
                uri == "/admin/api/download" && method == Method.GET ->
                    handleDownload(session)
                uri == "/admin/api/download-folder" && method == Method.GET ->
                    handleDownloadFolder(session)

                // --- Admin WebRTC (no auth) ---
                uri == "/admin/webrtc.js" ->
                    serveAsset("web/webrtc.js", "application/javascript")
                uri == "/admin/api/webrtc/status" && method == Method.GET ->
                    handleWebRtcStatus()
                uri == "/admin/api/webrtc/offer" && method == Method.POST ->
                    handleWebRtcOffer(session, clientIp)
                uri == "/admin/api/webrtc/hangup" && method == Method.POST ->
                    handleWebRtcHangup(session)

                // --- Admin chat APIs (no auth) ---
                uri == "/admin/api/codes" && method == Method.GET ->
                    handleAdminCodes()
                uri == "/admin/api/conversations" && method == Method.GET ->
                    handleAdminConversations()
                uri == "/admin/api/chat/messages" && method == Method.GET ->
                    handleAdminChatMessages(session)
                uri == "/admin/api/chat/send" && method == Method.POST ->
                    handleAdminChatSend(session)

                // --- Everything else requires auth ---
                else -> {
                    if (!isAuthenticated(session)) {
                        return if (uri.startsWith("/hls/") || uri.startsWith("/api/")) {
                            newFixedLengthResponse(
                                Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized"
                            )
                        } else {
                            redirectToLogin()
                        }
                    }

                    when {
                        uri == "/api/status" && method == Method.GET -> handleStatus()
                        uri == "/api/quality" && method == Method.POST -> handleQuality(session)
                        uri == "/api/camera" && method == Method.POST -> handleCameraSwitch()

                        // WebRTC signaling
                        uri == "/api/webrtc/status" && method == Method.GET ->
                            handleWebRtcStatus()
                        uri == "/api/webrtc/offer" && method == Method.POST ->
                            handleWebRtcOffer(session, clientIp)
                        uri == "/api/webrtc/hangup" && method == Method.POST ->
                            handleWebRtcHangup(session)
                        uri == "/webrtc.js" ->
                            serveAsset("web/webrtc.js", "application/javascript")

                        // Viewer chat APIs
                        uri == "/api/chat/send" && method == Method.POST ->
                            handleViewerChatSend(session)
                        uri == "/api/chat/messages" && method == Method.GET ->
                            handleViewerChatMessages(session)

                        uri.startsWith("/hls/") -> {
                            val fileName = uri.removePrefix("/hls/")
                            if (fileName == "screen.m3u8") {
                                viewers[clientIp] = System.currentTimeMillis()
                            }
                            handleHls(fileName)
                        }

                        uri == "/" || uri == "/index.html" ->
                            serveAsset("web/index.html", "text/html")
                        uri == "/live.html" ->
                            serveAsset("web/live.html", "text/html")

                        else -> newFixedLengthResponse(
                            Response.Status.NOT_FOUND, MIME_HTML, "Not Found"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serving $uri", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf("error" to e.message))
        }
    }

    // --- Auth ---

    private fun getTokenFromSession(session: IHTTPSession): String? {
        val cookies = session.cookies
        val token = cookies?.read(SESSION_COOKIE)
        if (token != null && validTokens.contains(token)) return token

        val cookieHeader = session.headers["cookie"] ?: return null
        val manualToken = cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter("=")
        return if (manualToken != null && validTokens.contains(manualToken)) manualToken else null
    }

    private fun isAuthenticated(session: IHTTPSession): Boolean {
        return getTokenFromSession(session) != null
    }

    private fun getCodeForSession(session: IHTTPSession): CodeInfo? {
        val token = getTokenFromSession(session) ?: return null
        return tokenToCode[token]
    }

    private fun handleAuth(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        try { session.parseBody(body) } catch (_: Exception) {}

        val postData = body["postData"] ?: body["content"] ?: ""

        val json = try {
            if (postData.isNotEmpty()) {
                gson.fromJson(postData, JsonObject::class.java)
            } else null
        } catch (_: Exception) { null }

        val code = json?.get("code")?.asString
            ?: session.parms["code"]
            ?: ""

        Log.d(TAG, "Auth attempt -- code: '$code', body keys: ${body.keys}")

        // Find matching code from the 10 access codes
        val codeInfo = accessCodes.find { it.code == code }
        if (codeInfo == null) {
            return jsonResponse(
                Response.Status.FORBIDDEN,
                mapOf("error" to "Invalid code")
            )
        }

        // Generate session token
        val token = java.util.UUID.randomUUID().toString()
        validTokens.add(token)
        codeInfo.token = token
        tokenToCode[token] = codeInfo

        // Initialize conversation if not exists
        conversations.putIfAbsent(code, mutableListOf())

        val response = jsonResponse(
            Response.Status.OK,
            mapOf("ok" to true)
        )
        response.addHeader("Set-Cookie", "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax")
        return response
    }

    private fun redirectToLogin(): Response {
        val response = newFixedLengthResponse(
            Response.Status.REDIRECT_SEE_OTHER,
            MIME_HTML,
            "Redirecting to login..."
        )
        response.addHeader("Location", "/login")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    // --- Viewer Chat APIs ---

    private fun handleViewerChatSend(session: IHTTPSession): Response {
        val codeInfo = getCodeForSession(session)
            ?: return jsonResponse(Response.Status.UNAUTHORIZED, mapOf("error" to "No session"))

        val body = HashMap<String, String>()
        try { session.parseBody(body) } catch (_: Exception) {}
        val postData = body["postData"] ?: body["content"] ?: ""
        val json = try { gson.fromJson(postData, JsonObject::class.java) } catch (_: Exception) { null }
        val text = json?.get("text")?.asString?.trim()
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing text"))

        if (text.isEmpty() || text.length > 1000) {
            return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Invalid message"))
        }

        val msg = ChatMessage(from = "viewer", text = text)
        conversations.getOrPut(codeInfo.code) { mutableListOf() }.add(msg)
        Log.d(TAG, "Chat [${codeInfo.code}] viewer: $text")

        return jsonResponse(Response.Status.OK, mapOf("ok" to true, "time" to msg.time))
    }

    private fun handleViewerChatMessages(session: IHTTPSession): Response {
        val codeInfo = getCodeForSession(session)
            ?: return jsonResponse(Response.Status.UNAUTHORIZED, mapOf("error" to "No session"))

        val sinceParam = session.parms["since"]?.toLongOrNull() ?: 0L
        val messages = conversations[codeInfo.code]
            ?.filter { it.time > sinceParam }
            ?.map { mapOf("from" to it.from, "text" to it.text, "time" to it.time) }
            ?: emptyList()

        return jsonResponse(Response.Status.OK, mapOf("messages" to messages, "code" to codeInfo.code))
    }

    // --- Admin Chat APIs ---

    private fun handleAdminCodes(): Response {
        val codes = accessCodes.map { ci ->
            mapOf(
                "code" to ci.code,
                "label" to ci.label,
                "connected" to ci.isConnected
            )
        }
        return jsonResponse(Response.Status.OK, mapOf("codes" to codes))
    }

    private fun handleAdminConversations(): Response {
        val convos = accessCodes.mapNotNull { ci ->
            val msgs = conversations[ci.code]
            if (msgs == null || msgs.isEmpty()) {
                // Still show connected codes even with no messages
                if (ci.isConnected) {
                    mapOf(
                        "code" to ci.code,
                        "label" to ci.label,
                        "connected" to true,
                        "lastMessage" to "",
                        "lastTime" to 0L,
                        "messageCount" to 0
                    )
                } else null
            } else {
                val last = msgs.last()
                mapOf(
                    "code" to ci.code,
                    "label" to ci.label,
                    "connected" to ci.isConnected,
                    "lastMessage" to last.text,
                    "lastTime" to last.time,
                    "messageCount" to msgs.size
                )
            }
        }
        return jsonResponse(Response.Status.OK, mapOf("conversations" to convos))
    }

    private fun handleAdminChatMessages(session: IHTTPSession): Response {
        val code = session.parms["code"]
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing code"))
        val sinceParam = session.parms["since"]?.toLongOrNull() ?: 0L

        val messages = conversations[code]
            ?.filter { it.time > sinceParam }
            ?.map { mapOf("from" to it.from, "text" to it.text, "time" to it.time) }
            ?: emptyList()

        return jsonResponse(Response.Status.OK, mapOf("messages" to messages))
    }

    private fun handleAdminChatSend(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        try { session.parseBody(body) } catch (_: Exception) {}
        val postData = body["postData"] ?: body["content"] ?: ""
        val json = try { gson.fromJson(postData, JsonObject::class.java) } catch (_: Exception) { null }

        val code = json?.get("code")?.asString
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing code"))
        val text = json.get("text")?.asString?.trim()
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing text"))

        if (text.isEmpty() || text.length > 1000) {
            return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Invalid message"))
        }

        // Verify code exists
        if (accessCodes.none { it.code == code }) {
            return jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "Unknown code"))
        }

        val msg = ChatMessage(from = "streamer", text = text)
        conversations.getOrPut(code) { mutableListOf() }.add(msg)
        Log.d(TAG, "Chat [$code] streamer: $text")

        return jsonResponse(Response.Status.OK, mapOf("ok" to true, "time" to msg.time))
    }

    // --- API: Status ---

    private fun handleStatus(): Response {
        val capturing = getCaptureState?.invoke() ?: false
        val quality = getCurrentQuality?.invoke() ?: "720p"
        val uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000.0
        val manifestExists = File(hlsDir, "screen.m3u8").exists()

        val tailscale = getTailscaleUrl?.invoke() ?: ""
        val camera = getCameraFacing?.invoke() ?: "back"
        val publicUrlValue = getPublicUrl?.invoke() ?: ""

        val webrtcServer = getWebRtcServer?.invoke()
        val webrtcActive = webrtcServer?.isRunning ?: false
        val webrtcPeers = webrtcServer?.getPeerCount() ?: 0

        val source = dev.serverpages.service.CaptureService.instance?.getCurrentSource() ?: "camera"
        val screenAvailable = dev.serverpages.service.CaptureService.instance?.isScreenAvailable() ?: false

        return jsonResponse(
            Response.Status.OK, mapOf(
                "capturing" to capturing,
                "uptime" to uptimeSec,
                "streamReady" to (capturing && (manifestExists || webrtcActive)),
                "quality" to quality,
                "camera" to camera,
                "viewers" to (getViewerCount() + webrtcPeers),
                "tailscaleUrl" to tailscale,
                "publicUrl" to publicUrlValue,
                "webrtc" to webrtcActive,
                "webrtcPeers" to webrtcPeers,
                "source" to source,
                "screenAvailable" to screenAvailable
            )
        )
    }

    // --- API: Files ---

    private fun handleFiles(session: IHTTPSession): Response {
        val dirParam = session.parms["dir"] ?: return jsonResponse(
            Response.Status.BAD_REQUEST, mapOf("error" to "Missing dir parameter")
        )

        val dir = MediaBrowser.resolveDir(dirParam)

        if (!MediaBrowser.isPathAllowed(dir)) {
            return jsonResponse(Response.Status.FORBIDDEN, mapOf("error" to "Access denied"))
        }

        val dirFile = File(dir)
        if (!dirFile.exists() || !dirFile.isDirectory) {
            return jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "Directory not found"))
        }

        return try {
            val listing = MediaBrowser.listDir(dir)
            jsonResponse(Response.Status.OK, listing)
        } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf("error" to "Cannot read directory: ${e.message}"))
        }
    }

    // --- API: Stream (with Range support) ---

    private fun handleStream(session: IHTTPSession): Response {
        val filePath = session.parms["path"]
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing path"))

        val file = File(filePath)
        if (!MediaBrowser.isPathAllowed(filePath)) {
            return jsonResponse(Response.Status.FORBIDDEN, mapOf("error" to "Access denied"))
        }
        if (!file.exists()) {
            return jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "File not found"))
        }

        val mimeType = MediaBrowser.getMimeType(file.name)
        val fileSize = file.length()
        val rangeHeader = session.headers["range"]

        return if (rangeHeader != null) {
            serveRangeRequest(file, fileSize, mimeType, rangeHeader)
        } else {
            val fis = FileInputStream(file)
            val response = newFixedLengthResponse(Response.Status.OK, mimeType, fis, fileSize)
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Length", fileSize.toString())
            response
        }
    }

    private fun serveRangeRequest(
        file: File,
        fileSize: Long,
        mimeType: String,
        rangeHeader: String
    ): Response {
        val rangeParts = rangeHeader.replace("bytes=", "").split("-")
        val start = rangeParts[0].toLongOrNull() ?: 0
        val end = if (rangeParts.size > 1 && rangeParts[1].isNotBlank()) {
            rangeParts[1].toLong()
        } else {
            fileSize - 1
        }
        val chunkSize = end - start + 1

        val fis = FileInputStream(file)
        fis.skip(start)

        val response = newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            mimeType,
            fis,
            chunkSize
        )
        response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", chunkSize.toString())
        return response
    }

    // --- API: Download ---

    private fun handleDownload(session: IHTTPSession): Response {
        val filePath = session.parms["path"]
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing path"))

        val file = File(filePath)
        if (!MediaBrowser.isPathAllowed(filePath)) {
            return jsonResponse(Response.Status.FORBIDDEN, mapOf("error" to "Access denied"))
        }
        if (!file.exists()) {
            return jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "File not found"))
        }

        val fis = FileInputStream(file)
        val response = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", fis, file.length())
        response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        return response
    }

    // --- API: Download Folder (zip) ---

    private fun handleDownloadFolder(session: IHTTPSession): Response {
        val dirParam = session.parms["dir"]
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing dir parameter"))

        val dir = File(MediaBrowser.resolveDir(dirParam))
        if (!MediaBrowser.isPathAllowed(dir.absolutePath)) {
            return jsonResponse(Response.Status.FORBIDDEN, mapOf("error" to "Access denied"))
        }
        if (!dir.exists() || !dir.isDirectory) {
            return jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "Directory not found"))
        }

        val pipedIn = PipedInputStream(65536)
        val pipedOut = PipedOutputStream(pipedIn)

        Thread {
            try {
                ZipOutputStream(pipedOut).use { zip ->
                    val basePath = dir.absolutePath
                    addDirToZip(zip, dir, basePath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating zip for $dirParam", e)
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.start()

        val zipName = "${dir.name}.zip"
        val response = newChunkedResponse(Response.Status.OK, "application/zip", pipedIn)
        response.addHeader("Content-Disposition", "attachment; filename=\"$zipName\"")
        return response
    }

    private fun addDirToZip(zip: ZipOutputStream, dir: File, basePath: String) {
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (entry.name.startsWith(".")) continue
            val relativePath = entry.absolutePath.removePrefix(basePath).trimStart('/')
            if (entry.isDirectory) {
                addDirToZip(zip, entry, basePath)
            } else if (entry.isFile) {
                try {
                    zip.putNextEntry(ZipEntry(relativePath))
                    FileInputStream(entry).use { fis ->
                        fis.copyTo(zip, 8192)
                    }
                    zip.closeEntry()
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping file in zip: ${entry.name}", e)
                }
            }
        }
    }

    // --- API: Quality toggle ---

    private fun handleQuality(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""

        val json = try { gson.fromJson(postData, JsonObject::class.java) } catch (_: Exception) { null }
        val quality = json?.get("quality")?.asString
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing quality"))

        if (QualityPreset.fromLabel(quality) == null) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                mapOf("error" to "Invalid quality. Use: ${QualityPreset.entries.joinToString { it.label }}")
            )
        }

        val currentQuality = getCurrentQuality?.invoke() ?: "720p"
        if (quality == currentQuality) {
            return jsonResponse(Response.Status.OK, mapOf("quality" to currentQuality, "changed" to false))
        }

        val changed = onQualityChange?.invoke(quality) ?: false
        return jsonResponse(Response.Status.OK, mapOf("quality" to quality, "changed" to changed))
    }

    // --- API: Camera switch ---

    private fun handleCameraSwitch(): Response {
        val switched = onCameraSwitch?.invoke() ?: false
        val camera = getCameraFacing?.invoke() ?: "back"
        return jsonResponse(Response.Status.OK, mapOf("camera" to camera, "switched" to switched))
    }

    // --- API: Source switch ---

    private fun handleSourceSwitch(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        try { session.parseBody(body) } catch (_: Exception) {}
        val postData = body["postData"] ?: body["content"] ?: ""
        val json = try { gson.fromJson(postData, JsonObject::class.java) } catch (_: Exception) { null }

        val mode = json?.get("mode")?.asString
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing mode"))

        if (mode != "camera" && mode != "screen") {
            return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Invalid mode. Use: camera, screen"))
        }

        val switched = dev.serverpages.service.CaptureService.instance?.switchSource(mode) ?: false
        val currentSource = dev.serverpages.service.CaptureService.instance?.getCurrentSource() ?: "camera"
        return jsonResponse(Response.Status.OK, mapOf("source" to currentSource, "switched" to switched))
    }

    // --- HLS segment serving ---

    private fun handleHls(fileName: String): Response {
        val file = File(hlsDir, fileName)
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        val (mimeType, cacheControl) = when {
            fileName.endsWith(".m3u8") -> "application/vnd.apple.mpegurl" to "no-cache, no-store"
            fileName.endsWith(".mp4") -> "video/mp4" to "max-age=10"
            fileName.endsWith(".ts") -> "video/mp2t" to "max-age=10"
            else -> "application/octet-stream" to "no-cache"
        }

        val fis = FileInputStream(file)
        val response = newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length())
        response.addHeader("Cache-Control", cacheControl)
        response.addHeader("Access-Control-Allow-Credentials", "true")
        return response
    }

    // --- Static asset serving ---

    private fun serveAsset(assetPath: String, mimeType: String): Response {
        return try {
            val stream = context.assets.open(assetPath)
            val bytes = stream.readBytes()
            stream.close()
            newFixedLengthResponse(Response.Status.OK, mimeType, bytes.inputStream(), bytes.size.toLong())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Asset not found: $assetPath")
        }
    }

    // --- WebRTC Signaling ---

    private fun handleWebRtcStatus(): Response {
        val webrtcServer = getWebRtcServer?.invoke()
        val active = webrtcServer?.isRunning ?: false
        return jsonResponse(
            Response.Status.OK, mapOf(
                "webrtc" to active,
                "peers" to (webrtcServer?.getPeerCount() ?: 0),
                "iceServers" to WebRtcServer.ICE_SERVER_URLS.map { mapOf("urls" to it) }
            )
        )
    }

    private fun handleWebRtcOffer(session: IHTTPSession, clientIp: String): Response {
        val webrtcServer = getWebRtcServer?.invoke()
            ?: return jsonResponse(Response.Status.SERVICE_UNAVAILABLE, mapOf("error" to "WebRTC not available"))

        val body = HashMap<String, String>()
        try { session.parseBody(body) } catch (_: Exception) {}
        val postData = body["postData"] ?: body["content"] ?: ""
        val json = try { gson.fromJson(postData, com.google.gson.JsonObject::class.java) } catch (_: Exception) { null }

        val sdpOffer = json?.get("sdp")?.asString
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing sdp"))

        // Use clientIp + timestamp as viewer ID for uniqueness
        val viewerId = "$clientIp-${System.currentTimeMillis()}"

        val answerSdp = webrtcServer.handleOffer(viewerId, sdpOffer)
            ?: return jsonResponse(Response.Status.INTERNAL_ERROR, mapOf("error" to "Failed to create answer"))

        // Track this viewer's peer ID in the session for hangup
        return jsonResponse(Response.Status.OK, mapOf("sdp" to answerSdp, "viewerId" to viewerId))
    }

    private fun handleWebRtcHangup(session: IHTTPSession): Response {
        val webrtcServer = getWebRtcServer?.invoke()
            ?: return jsonResponse(Response.Status.OK, mapOf("ok" to true))

        val body = HashMap<String, String>()
        try { session.parseBody(body) } catch (_: Exception) {}
        val postData = body["postData"] ?: body["content"] ?: ""
        val json = try { gson.fromJson(postData, com.google.gson.JsonObject::class.java) } catch (_: Exception) { null }

        val viewerId = json?.get("viewerId")?.asString
        if (viewerId != null) {
            webrtcServer.removePeer(viewerId)
        }
        return jsonResponse(Response.Status.OK, mapOf("ok" to true))
    }

    // --- Helpers ---

    private fun jsonResponse(status: Response.Status, data: Any): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(status, "application/json", json)
    }
}
