package dev.serverpages.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import dev.serverpages.capture.QualityPreset
import java.io.File
import java.io.FileInputStream

/**
 * Embedded HTTP server on port 3333. Serves:
 * - Static web pages from assets/web/
 * - HLS segments from the cache directory
 * - API routes matching the Windows ServerPages version
 */
class WebServer(
    private val context: Context,
    private val hlsDir: File,
    port: Int = 3333
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "WebServer"
    }

    private val gson = Gson()
    private var startTimeMs = System.currentTimeMillis()

    // Callbacks from CaptureService
    var onQualityChange: ((String) -> Boolean)? = null
    var getCaptureState: (() -> Boolean)? = null
    var getCurrentQuality: (() -> String)? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        return try {
            when {
                // API routes
                uri == "/api/status" && method == Method.GET -> handleStatus()
                uri == "/api/files" && method == Method.GET -> handleFiles(session)
                uri == "/api/stream" && method == Method.GET -> handleStream(session)
                uri == "/api/download" && method == Method.GET -> handleDownload(session)
                uri == "/api/quality" && method == Method.POST -> handleQuality(session)

                // HLS segments
                uri.startsWith("/hls/") -> handleHls(uri.removePrefix("/hls/"))

                // Static web pages from assets
                uri == "/" || uri == "/index.html" -> serveAsset("web/index.html", "text/html")
                uri == "/live.html" -> serveAsset("web/live.html", "text/html")
                uri == "/media.html" -> serveAsset("web/media.html", "text/html")
                uri == "/style.css" -> serveAsset("web/style.css", "text/css")

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serving $uri", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf("error" to e.message))
        }
    }

    // ─── API: Status ─────────────────────────────────────────────────────────

    private fun handleStatus(): Response {
        val capturing = getCaptureState?.invoke() ?: false
        val quality = getCurrentQuality?.invoke() ?: "720p"
        val uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000.0
        val manifestExists = File(hlsDir, "screen.m3u8").exists()

        return jsonResponse(
            Response.Status.OK, mapOf(
                "capturing" to capturing,
                "uptime" to uptimeSec,
                "streamReady" to (capturing && manifestExists),
                "quality" to quality
            )
        )
    }

    // ─── API: Files ──────────────────────────────────────────────────────────

    private fun handleFiles(session: IHTTPSession): Response {
        val dirParam = session.parms["dir"] ?: return jsonResponse(
            Response.Status.BAD_REQUEST, mapOf("error" to "Missing dir parameter")
        )

        // Resolve short names (DCIM, Pictures, etc.) to absolute paths
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

    // ─── API: Stream (with Range support) ────────────────────────────────────

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

    // ─── API: Download ───────────────────────────────────────────────────────

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

    // ─── API: Quality toggle ─────────────────────────────────────────────────

    private fun handleQuality(session: IHTTPSession): Response {
        // Parse JSON body
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""

        val json = try { gson.fromJson(postData, JsonObject::class.java) } catch (e: Exception) { null }
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
        return jsonResponse(
            Response.Status.OK,
            mapOf("quality" to quality, "changed" to changed)
        )
    }

    // ─── HLS segment serving ─────────────────────────────────────────────────

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

    // ─── Static asset serving ────────────────────────────────────────────────

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

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun jsonResponse(status: Response.Status, data: Any): Response {
        val json = gson.toJson(data)
        val response = newFixedLengthResponse(status, "application/json", json)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }
}
