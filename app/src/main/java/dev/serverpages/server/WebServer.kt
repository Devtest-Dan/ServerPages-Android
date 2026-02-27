package dev.serverpages.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import dev.serverpages.capture.QualityPreset
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
 * Auth: 4-digit code → cookie session. Admin path bypasses auth.
 * Viewer tracking: counts unique IPs fetching /hls/screen.m3u8 (excludes admin).
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
    var getCaptureState: (() -> Boolean)? = null
    var getCurrentQuality: (() -> String)? = null
    var getTailscaleUrl: (() -> String)? = null

    // Auth
    var accessCode: String = "0000"
    private val validTokens = ConcurrentHashMap.newKeySet<String>()

    // Viewer tracking: IP → last seen timestamp
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
                // ─── Public routes (no auth) ─────────────────────────────
                uri == "/login" || uri == "/login.html" ->
                    serveAsset("web/login.html", "text/html")
                uri == "/style.css" ->
                    serveAsset("web/style.css", "text/css")
                uri == "/api/auth" && method == Method.POST ->
                    handleAuth(session)

                // ─── Admin routes (no auth, excluded from viewer count) ──
                uri == "/admin" || uri == "/admin/" || uri == "/admin/live.html" ->
                    serveAsset("web/admin.html", "text/html")
                uri == "/admin/media.html" ->
                    serveAsset("web/media.html", "text/html")
                uri.startsWith("/admin/hls/") ->
                    handleHls(uri.removePrefix("/admin/hls/"))
                uri == "/admin/api/status" ->
                    handleStatus()
                uri == "/admin/api/files" && method == Method.GET ->
                    handleFiles(session)
                uri == "/admin/api/stream" && method == Method.GET ->
                    handleStream(session)
                uri == "/admin/api/download" && method == Method.GET ->
                    handleDownload(session)
                uri == "/admin/api/download-folder" && method == Method.GET ->
                    handleDownloadFolder(session)

                // ─── Everything else requires auth ───────────────────────
                else -> {
                    if (!isAuthenticated(session)) {
                        return redirectToLogin()
                    }

                    when {
                        uri == "/api/status" && method == Method.GET -> handleStatus()
                        uri == "/api/quality" && method == Method.POST -> handleQuality(session)

                        uri.startsWith("/hls/") -> {
                            val fileName = uri.removePrefix("/hls/")
                            // Track viewer on manifest request
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

    // ─── Auth ─────────────────────────────────────────────────────────────────

    private fun isAuthenticated(session: IHTTPSession): Boolean {
        val cookies = session.cookies ?: return false
        val token = cookies.read(SESSION_COOKIE) ?: return false
        return validTokens.contains(token)
    }

    private fun handleAuth(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""

        val json = try {
            gson.fromJson(postData, JsonObject::class.java)
        } catch (_: Exception) { null }
        val code = json?.get("code")?.asString ?: ""

        if (code != accessCode) {
            return jsonResponse(
                Response.Status.FORBIDDEN,
                mapOf("error" to "Invalid code")
            )
        }

        // Generate session token
        val token = java.util.UUID.randomUUID().toString()
        validTokens.add(token)

        val response = jsonResponse(
            Response.Status.OK,
            mapOf("ok" to true)
        )
        response.addHeader("Set-Cookie", "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Strict")
        return response
    }

    private fun redirectToLogin(): Response {
        val response = newFixedLengthResponse(
            Response.Status.REDIRECT,
            MIME_HTML,
            "Redirecting to login..."
        )
        response.addHeader("Location", "/login")
        return response
    }

    // ─── API: Status ──────────────────────────────────────────────────────────

    private fun handleStatus(): Response {
        val capturing = getCaptureState?.invoke() ?: false
        val quality = getCurrentQuality?.invoke() ?: "720p"
        val uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000.0
        val manifestExists = File(hlsDir, "screen.m3u8").exists()

        val tailscale = getTailscaleUrl?.invoke() ?: ""

        return jsonResponse(
            Response.Status.OK, mapOf(
                "capturing" to capturing,
                "uptime" to uptimeSec,
                "streamReady" to (capturing && manifestExists),
                "quality" to quality,
                "viewers" to getViewerCount(),
                "tailscaleUrl" to tailscale
            )
        )
    }

    // ─── API: Files ───────────────────────────────────────────────────────────

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

    // ─── API: Stream (with Range support) ─────────────────────────────────────

    private fun handleStream(session: IHTTPSession): Response {
        val filePath = session.parms["path"]
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing path"))

        val file = File(filePath)
        if (!MediaBrowser.isPathAllowed(filePath) || !MediaBrowser.isMediaFile(file.name)) {
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

    // ─── API: Download ────────────────────────────────────────────────────────

    private fun handleDownload(session: IHTTPSession): Response {
        val filePath = session.parms["path"]
            ?: return jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "Missing path"))

        val file = File(filePath)
        if (!MediaBrowser.isPathAllowed(filePath) || !MediaBrowser.isMediaFile(file.name)) {
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

    // ─── API: Download Folder (zip) ────────────────────────────────────────────

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

        // Zip in a background thread so NanoHTTPd can stream the response
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
            } else if (entry.isFile && MediaBrowser.isMediaFile(entry.name)) {
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

    // ─── API: Quality toggle ──────────────────────────────────────────────────

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

    // ─── HLS segment serving ──────────────────────────────────────────────────

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
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    // ─── Static asset serving ─────────────────────────────────────────────────

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

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun jsonResponse(status: Response.Status, data: Any): Response {
        val json = gson.toJson(data)
        val response = newFixedLengthResponse(status, "application/json", json)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }
}
