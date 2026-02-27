package dev.serverpages.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes H.264 encoded buffers into standalone MP4 segments with a rolling m3u8 manifest.
 *
 * Each segment is a complete, self-contained MP4 file (~2 seconds).
 * MediaMuxer creates the moov atom when stop() is called, making each file playable.
 * The manifest uses EXT-X-VERSION:3 without EXT-X-MAP for maximum compatibility —
 * hls.js handles standalone MP4 segments natively.
 */
class HlsWriter(
    private val hlsDir: File,
    private val segmentDurationUs: Long = 2_000_000L,
    private val maxSegments: Int = 5
) {
    companion object {
        private const val TAG = "HlsWriter"
        private const val MANIFEST_NAME = "screen.m3u8"
    }

    private var muxer: MediaMuxer? = null
    private var trackIndex: Int = -1
    private var segmentIndex = AtomicInteger(0)
    private var segmentStartUs: Long = -1L
    private var segmentFirstPtsUs: Long = -1L
    private var mediaFormat: MediaFormat? = null
    private var mediaSequence: Int = 0
    private var firstSegmentDone: Boolean = false
    private val segmentDurations = mutableListOf<Double>() // seconds, for each active segment

    init {
        hlsDir.mkdirs()
        cleanAll()
    }

    fun setFormat(format: MediaFormat) {
        mediaFormat = format
        Log.d(TAG, "Format set: $format")
    }

    fun writeSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (mediaFormat == null) return

        // Initialize segment start time
        if (segmentStartUs < 0L) {
            segmentStartUs = info.presentationTimeUs
        }

        // Check if we should rotate to a new segment (on keyframes only)
        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val elapsed = info.presentationTimeUs - segmentStartUs

        if (muxer == null) {
            startNewSegment()
            segmentFirstPtsUs = info.presentationTimeUs
        } else if (isKeyFrame && elapsed >= segmentDurationUs) {
            // Record duration of completed segment
            val durationSec = elapsed / 1_000_000.0
            finalizeMuxer(durationSec)
            startNewSegment()
            segmentStartUs = info.presentationTimeUs
            segmentFirstPtsUs = info.presentationTimeUs
        }

        if (muxer != null && trackIndex >= 0) {
            // Adjust PTS to be segment-relative (starts at 0 for each segment)
            val adjustedInfo = MediaCodec.BufferInfo()
            adjustedInfo.set(
                info.offset,
                info.size,
                info.presentationTimeUs - segmentFirstPtsUs,
                info.flags
            )
            try {
                muxer!!.writeSampleData(trackIndex, buffer, adjustedInfo)
            } catch (e: Exception) {
                Log.w(TAG, "Error writing sample", e)
            }
        }

        // Force a short first segment (~1s) so the manifest appears quickly
        if (!firstSegmentDone && isKeyFrame && elapsed >= 1_000_000L) {
            val durationSec = elapsed / 1_000_000.0
            finalizeMuxer(durationSec)
            startNewSegment()
            segmentStartUs = info.presentationTimeUs
            segmentFirstPtsUs = info.presentationTimeUs
            firstSegmentDone = true
        }
    }

    private fun startNewSegment() {
        val idx = segmentIndex.getAndIncrement()
        val segFile = File(hlsDir, "seg${String.format("%05d", idx)}.mp4")

        try {
            muxer = MediaMuxer(segFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            trackIndex = muxer!!.addTrack(mediaFormat!!)
            muxer!!.start()
            Log.d(TAG, "Started segment: ${segFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start segment ${segFile.name}", e)
            muxer = null
            trackIndex = -1
        }
    }

    private fun finalizeMuxer(durationSec: Double = 2.0) {
        try {
            muxer?.stop()
            muxer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error finalizing muxer", e)
        }
        muxer = null
        trackIndex = -1

        synchronized(segmentDurations) {
            segmentDurations.add(durationSec)
        }

        cleanOldSegments()
        writeManifest()
    }

    private fun cleanOldSegments() {
        val segments = hlsDir.listFiles { f -> f.name.startsWith("seg") && f.name.endsWith(".mp4") }
            ?.sortedBy { it.name }
            ?: return

        if (segments.size > maxSegments + 1) { // +1 for currently writing segment
            val toDelete = segments.take(segments.size - maxSegments - 1)
            for (f in toDelete) {
                f.delete()
            }
            mediaSequence += toDelete.size

            synchronized(segmentDurations) {
                repeat(toDelete.size.coerceAtMost(segmentDurations.size)) {
                    segmentDurations.removeFirst()
                }
            }
        }
    }

    private fun writeManifest() {
        val allSegments = hlsDir.listFiles { f -> f.name.startsWith("seg") && f.name.endsWith(".mp4") }
            ?.sortedBy { it.name }
            ?: return

        // Only include completed segments — in-progress MP4 has no moov atom
        val completedSegments = if (muxer != null && allSegments.isNotEmpty()) {
            allSegments.dropLast(1)
        } else {
            allSegments.toList()
        }

        if (completedSegments.isEmpty()) return

        val durations = synchronized(segmentDurations) { segmentDurations.toList() }

        val targetDuration = synchronized(segmentDurations) {
            segmentDurations.maxOrNull()?.let { kotlin.math.ceil(it).toInt().coerceAtLeast(3) } ?: 3
        }

        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#EXT-X-VERSION:3")
        sb.appendLine("#EXT-X-TARGETDURATION:$targetDuration")
        sb.appendLine("#EXT-X-MEDIA-SEQUENCE:$mediaSequence")

        for ((i, seg) in completedSegments.withIndex()) {
            val dur = if (i < durations.size) durations[i] else 2.0
            sb.appendLine("#EXTINF:${String.format("%.3f", dur)},")
            sb.appendLine(seg.name)
        }

        File(hlsDir, MANIFEST_NAME).writeText(sb.toString())
        Log.d(TAG, "Manifest written: ${completedSegments.size} segments, seq=$mediaSequence")
    }

    fun stop() {
        if (muxer != null) {
            val elapsed = if (segmentStartUs >= 0) {
                (System.nanoTime() / 1000 - segmentStartUs).coerceAtLeast(0) / 1_000_000.0
            } else 2.0
            finalizeMuxer(elapsed)
        }
    }

    fun cleanAll() {
        hlsDir.listFiles()?.forEach { it.delete() }
        segmentIndex.set(0)
        mediaSequence = 0
        segmentStartUs = -1L
        segmentFirstPtsUs = -1L
        firstSegmentDone = false
        synchronized(segmentDurations) { segmentDurations.clear() }
    }
}
