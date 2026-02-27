package dev.serverpages.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.serverpages.server.MediaBrowser
import java.io.File

/**
 * Full-screen media player that cycles through images and videos from
 * the phone's media folders. Keeps the screen on so MediaProjection
 * captures actual content instead of a black/locked screen.
 *
 * Tap anywhere to exit.
 */
class ContentPlayerActivity : ComponentActivity() {

    companion object {
        private const val IMAGE_DISPLAY_MS = 8000L
    }

    private lateinit var imageView: ImageView
    private lateinit var videoView: VideoView
    private val handler = Handler(Looper.getMainLooper())
    private var mediaFiles = mutableListOf<File>()
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Full-screen immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Black background
        window.decorView.setBackgroundColor(0xFF000000.toInt())

        // Layout: ImageView + VideoView stacked, tap to exit
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        videoView = VideoView(this).apply {
            visibility = View.GONE
        }

        val root = android.widget.FrameLayout(this).apply {
            addView(videoView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(imageView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            setOnClickListener { finish() }
        }
        setContentView(root)

        // Collect all media files
        collectMediaFiles()

        if (mediaFiles.isEmpty()) {
            finish()
            return
        }

        // Shuffle for variety
        mediaFiles.shuffle()
        playNext()
    }

    private fun collectMediaFiles() {
        val dirs = listOf("DCIM", "Pictures", "Movies", "Music", "Download")
        val storage = android.os.Environment.getExternalStorageDirectory()
        for (dirName in dirs) {
            val dir = File(storage, dirName)
            if (dir.exists() && dir.isDirectory) {
                scanDir(dir)
            }
        }
    }

    private fun scanDir(dir: File) {
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (entry.name.startsWith(".")) continue
            if (entry.isDirectory) {
                scanDir(entry)
            } else if (entry.isFile && MediaBrowser.isMediaFile(entry.name)) {
                mediaFiles.add(entry)
            }
        }
    }

    private fun playNext() {
        if (mediaFiles.isEmpty()) return

        // Loop
        if (currentIndex >= mediaFiles.size) {
            currentIndex = 0
            mediaFiles.shuffle()
        }

        val file = mediaFiles[currentIndex]
        currentIndex++

        val type = MediaBrowser.getFileType(file.name)
        when (type) {
            "video" -> playVideo(file)
            "image" -> showImage(file)
            "audio" -> {
                // Skip audio files — nothing visual to show
                playNext()
            }
            else -> playNext()
        }
    }

    private fun showImage(file: File) {
        videoView.stopPlayback()
        videoView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        imageView.setImageURI(Uri.fromFile(file))

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ playNext() }, IMAGE_DISPLAY_MS)
    }

    private fun playVideo(file: File) {
        imageView.visibility = View.GONE
        videoView.visibility = View.VISIBLE

        videoView.setVideoURI(Uri.fromFile(file))
        videoView.setOnCompletionListener { playNext() }
        videoView.setOnErrorListener { _, _, _ ->
            // Skip unplayable videos
            playNext()
            true
        }
        videoView.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        videoView.stopPlayback()
        super.onDestroy()
    }
}
