package dev.serverpages.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes H.264 encoded buffers into MPEG-TS segments with a rolling m3u8 manifest.
 *
 * Each segment is a self-contained .ts file (~2 seconds) created by TsMuxer.
 * TS segments are natively supported by hls.js for live HLS streaming.
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

    private var tsMuxer: TsMuxer? = null
    private var segmentIndex = AtomicInteger(0)
    private var segmentStartUs: Long = -1L
    private var segmentFirstPtsUs: Long = -1L
    private var mediaFormat: MediaFormat? = null
    private var mediaSequence: Int = 0
    private var firstSegmentDone: Boolean = false
    private val segmentDurations = mutableListOf<Double>()

    // SPS/PPS extracted from encoder format — prepended to keyframes for TS
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    init {
        hlsDir.mkdirs()
        cleanAll()
    }

    fun setFormat(format: MediaFormat) {
        mediaFormat = format

        val csd0 = format.getByteBuffer("csd-0")
        val csd1 = format.getByteBuffer("csd-1")
        if (csd0 != null) {
            spsData = ByteArray(csd0.remaining())
            csd0.get(spsData!!)
            csd0.rewind()
        }
        if (csd1 != null) {
            ppsData = ByteArray(csd1.remaining())
            csd1.get(ppsData!!)
            csd1.rewind()
        }

        Log.d(TAG, "Format set — SPS: ${spsData?.size} bytes, PPS: ${ppsData?.size} bytes")
    }

    fun writeSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (mediaFormat == null) return

        if (segmentStartUs < 0L) {
            segmentStartUs = info.presentationTimeUs
        }

        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val elapsed = info.presentationTimeUs - segmentStartUs

        if (tsMuxer == null) {
            startNewSegment()
            segmentFirstPtsUs = info.presentationTimeUs
        } else if (isKeyFrame && elapsed >= segmentDurationUs) {
            val durationSec = elapsed / 1_000_000.0
            finalizeMuxer(durationSec)
            startNewSegment()
            segmentStartUs = info.presentationTimeUs
            segmentFirstPtsUs = info.presentationTimeUs
        }

        if (tsMuxer != null) {
            // Extract raw H.264 data from ByteBuffer
            val rawData = ByteArray(info.size)
            buffer.get(rawData)

            // Prepend SPS/PPS before keyframes (required for TS — no moov atom)
            val nalData = if (isKeyFrame && spsData != null && ppsData != null) {
                val combined = ByteArray(spsData!!.size + ppsData!!.size + rawData.size)
                System.arraycopy(spsData!!, 0, combined, 0, spsData!!.size)
                System.arraycopy(ppsData!!, 0, combined, spsData!!.size, ppsData!!.size)
                System.arraycopy(rawData, 0, combined, spsData!!.size + ppsData!!.size, rawData.size)
                combined
            } else {
                rawData
            }

            val pts = info.presentationTimeUs - segmentFirstPtsUs
            try {
                tsMuxer!!.writeSample(nalData, pts, isKeyFrame)
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
        val segFile = File(hlsDir, "seg${String.format("%05d", idx)}.ts")

        try {
            tsMuxer = TsMuxer()
            tsMuxer!!.start(segFile)
            Log.d(TAG, "Started segment: ${segFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start segment ${segFile.name}", e)
            tsMuxer = null
        }
    }

    private fun finalizeMuxer(durationSec: Double = 2.0) {
        try {
            tsMuxer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error finalizing muxer", e)
        }
        tsMuxer = null

        synchronized(segmentDurations) {
            segmentDurations.add(durationSec)
        }

        cleanOldSegments()
        writeManifest()
    }

    private fun cleanOldSegments() {
        val segments = hlsDir.listFiles { f -> f.name.startsWith("seg") && f.name.endsWith(".ts") }
            ?.sortedBy { it.name }
            ?: return

        if (segments.size > maxSegments + 1) {
            val toDelete = segments.take(segments.size - maxSegments - 1)
            for (f in toDelete) {
                f.delete()
            }
            mediaSequence += toDelete.size

            synchronized(segmentDurations) {
                repeat(toDelete.size.coerceAtMost(segmentDurations.size)) {
                    segmentDurations.removeAt(0)
                }
            }
        }
    }

    private fun writeManifest() {
        val allSegments = hlsDir.listFiles { f -> f.name.startsWith("seg") && f.name.endsWith(".ts") }
            ?.sortedBy { it.name }
            ?: return

        val completedSegments = if (tsMuxer != null && allSegments.isNotEmpty()) {
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
        if (tsMuxer != null) {
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
