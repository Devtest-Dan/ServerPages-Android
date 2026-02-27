package dev.serverpages.server

import android.os.Environment
import java.io.File

/**
 * Provides filesystem access to known media directories on Android.
 */
object MediaBrowser {

    private val VIDEO_EXTS = setOf(
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm",
        ".m4v", ".mpg", ".mpeg", ".3gp", ".3g2", ".ts",
        ".mts", ".m2ts", ".vob", ".ogv", ".f4v", ".asf", ".rm", ".rmvb"
    )
    private val AUDIO_EXTS = setOf(
        ".mp3", ".wav", ".flac", ".aac", ".ogg", ".wma", ".m4a", ".opus"
    )
    private val IMAGE_EXTS = setOf(
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg",
        ".ico", ".tiff", ".tif", ".heic", ".heif", ".avif"
    )
    private val MEDIA_EXTS = VIDEO_EXTS + AUDIO_EXTS + IMAGE_EXTS

    val MIME_TYPES = mapOf(
        ".mp4" to "video/mp4", ".mkv" to "video/x-matroska", ".avi" to "video/x-msvideo",
        ".mov" to "video/quicktime", ".wmv" to "video/x-ms-wmv", ".flv" to "video/x-flv",
        ".webm" to "video/webm", ".m4v" to "video/x-m4v", ".mpg" to "video/mpeg",
        ".mpeg" to "video/mpeg", ".3gp" to "video/3gpp", ".3g2" to "video/3gpp2",
        ".ts" to "video/mp2t", ".mts" to "video/mp2t", ".m2ts" to "video/mp2t",
        ".vob" to "video/mpeg", ".ogv" to "video/ogg", ".f4v" to "video/mp4",
        ".asf" to "video/x-ms-asf", ".rm" to "application/vnd.rn-realmedia",
        ".rmvb" to "application/vnd.rn-realmedia-vbr",
        ".mp3" to "audio/mpeg", ".wav" to "audio/wav", ".flac" to "audio/flac",
        ".aac" to "audio/aac", ".ogg" to "audio/ogg", ".wma" to "audio/x-ms-wma",
        ".m4a" to "audio/mp4", ".opus" to "audio/opus",
        ".jpg" to "image/jpeg", ".jpeg" to "image/jpeg", ".png" to "image/png",
        ".gif" to "image/gif", ".bmp" to "image/bmp", ".webp" to "image/webp",
        ".svg" to "image/svg+xml", ".ico" to "image/x-icon", ".tiff" to "image/tiff",
        ".tif" to "image/tiff", ".heic" to "image/heic", ".heif" to "image/heif",
        ".avif" to "image/avif"
    )

    /** Root directories exposed to the media browser. */
    val ROOT_DIRS: List<Pair<String, File>> by lazy {
        val storage = Environment.getExternalStorageDirectory()
        listOf(
            "DCIM" to File(storage, "DCIM"),
            "Pictures" to File(storage, "Pictures"),
            "Videos" to File(storage, "Movies"),
            "Music" to File(storage, "Music"),
            "Downloads" to File(storage, "Download")
        ).filter { it.second.exists() }
    }

    private val allowedRoot: String by lazy {
        Environment.getExternalStorageDirectory().absolutePath
    }

    /** Short name → absolute path mapping for web UI convenience. */
    private val SHORT_NAMES = mapOf(
        "DCIM" to "DCIM",
        "Pictures" to "Pictures",
        "Videos" to "Movies",
        "Music" to "Music",
        "Downloads" to "Download"
    )

    fun resolveDir(dir: String): String {
        // If it's a short name like "DCIM", resolve to full path
        val subDir = SHORT_NAMES[dir]
        if (subDir != null) {
            return File(Environment.getExternalStorageDirectory(), subDir).absolutePath
        }
        return dir
    }

    fun isPathAllowed(path: String): Boolean {
        val resolved = File(path).canonicalPath
        return resolved.startsWith(allowedRoot)
    }

    fun isMediaFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ".$ext" in MEDIA_EXTS
    }

    fun getFileType(name: String): String {
        val ext = ".${name.substringAfterLast('.', "").lowercase()}"
        return when (ext) {
            in VIDEO_EXTS -> "video"
            in IMAGE_EXTS -> "image"
            in AUDIO_EXTS -> "audio"
            else -> "unknown"
        }
    }

    fun getMimeType(name: String): String {
        val ext = ".${name.substringAfterLast('.', "").lowercase()}"
        return MIME_TYPES[ext] ?: "application/octet-stream"
    }

    data class DirEntry(
        val name: String,
        val path: String,
        val type: String
    )

    data class FileEntry(
        val name: String,
        val path: String,
        val type: String,
        val size: Long,
        val modified: String
    )

    data class DirListing(
        val currentDir: String,
        val parent: String?,
        val dirs: List<DirEntry>,
        val files: List<FileEntry>
    )

    fun listDir(dirPath: String): DirListing {
        val dir = File(dirPath)
        val entries = dir.listFiles() ?: emptyArray()

        val dirs = mutableListOf<DirEntry>()
        val files = mutableListOf<FileEntry>()

        for (entry in entries) {
            try {
                if (entry.name.startsWith(".")) continue // Skip hidden

                if (entry.isDirectory) {
                    dirs.add(DirEntry(entry.name, entry.absolutePath, "directory"))
                } else if (entry.isFile && isMediaFile(entry.name)) {
                    files.add(
                        FileEntry(
                            name = entry.name,
                            path = entry.absolutePath,
                            type = getFileType(entry.name),
                            size = entry.length(),
                            modified = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                                .format(java.util.Date(entry.lastModified()))
                        )
                    )
                }
            } catch (_: Exception) {
                // Skip inaccessible files
            }
        }

        dirs.sortBy { it.name.lowercase() }
        files.sortBy { it.name.lowercase() }

        val parent = if (dir.absolutePath != allowedRoot && isPathAllowed(dir.parent ?: "")) {
            dir.parent
        } else null

        return DirListing(dir.absolutePath, parent, dirs, files)
    }
}
