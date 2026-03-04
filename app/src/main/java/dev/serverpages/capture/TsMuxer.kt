package dev.serverpages.capture

import java.io.File
import java.io.FileOutputStream

/**
 * Lightweight MPEG-TS muxer for H.264 video.
 * Creates self-contained .ts segments for HLS streaming compatible with hls.js.
 */
class TsMuxer {

    companion object {
        const val TS_PACKET_SIZE = 188
        private const val PAT_PID = 0x0000
        private const val PMT_PID = 0x1000
        private const val VIDEO_PID = 0x0100
        private const val H264_STREAM_TYPE = 0x1B

        private val CRC_TABLE = IntArray(256).also { table ->
            for (i in 0..255) {
                var crc = i shl 24
                for (bit in 0..7) {
                    crc = if (crc and 0x80000000.toInt() != 0) {
                        (crc shl 1) xor 0x04C11DB7
                    } else {
                        crc shl 1
                    }
                }
                table[i] = crc
            }
        }
    }

    private var output: FileOutputStream? = null
    private var patCc = 0
    private var pmtCc = 0
    private var videoCc = 0

    fun start(file: File) {
        output = FileOutputStream(file)
        patCc = 0
        pmtCc = 0
        videoCc = 0
        writePat()
        writePmt()
    }

    fun writeSample(data: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean) {
        val pts90k = presentationTimeUs * 90 / 1000

        if (isKeyFrame) {
            writePat()
            writePmt()
        }

        val pesHeader = buildPesHeader(pts90k)
        val pesPacket = ByteArray(pesHeader.size + data.size)
        System.arraycopy(pesHeader, 0, pesPacket, 0, pesHeader.size)
        System.arraycopy(data, 0, pesPacket, pesHeader.size, data.size)

        var offset = 0
        var isFirst = true

        while (offset < pesPacket.size) {
            val remaining = pesPacket.size - offset
            val packet = ByteArray(TS_PACKET_SIZE)

            packet[0] = 0x47
            val pusi = if (isFirst) 0x40 else 0x00
            packet[1] = (pusi or ((VIDEO_PID shr 8) and 0x1F)).toByte()
            packet[2] = (VIDEO_PID and 0xFF).toByte()

            var pos: Int
            val payloadLen: Int

            if (isFirst && isKeyFrame) {
                // Adaptation field with PCR + random access
                val basePayload = 176
                payloadLen = remaining.coerceAtMost(basePayload)
                val stuffing = basePayload - payloadLen

                packet[3] = (0x30 or (videoCc and 0x0F)).toByte()
                pos = 4
                packet[pos++] = (7 + stuffing).toByte()
                packet[pos++] = 0x50.toByte() // random_access + PCR flag

                writePcrBytes(packet, pos, pts90k)
                pos += 6

                for (i in 0 until stuffing) {
                    packet[pos++] = 0xFF.toByte()
                }
            } else if (remaining >= 184) {
                payloadLen = 184
                packet[3] = (0x10 or (videoCc and 0x0F)).toByte()
                pos = 4
            } else {
                payloadLen = remaining
                val stuffing = 184 - payloadLen

                if (stuffing == 0) {
                    packet[3] = (0x10 or (videoCc and 0x0F)).toByte()
                    pos = 4
                } else {
                    packet[3] = (0x30 or (videoCc and 0x0F)).toByte()
                    pos = 4
                    if (stuffing == 1) {
                        packet[pos++] = 0x00
                    } else {
                        packet[pos++] = (stuffing - 1).toByte()
                        packet[pos++] = 0x00
                        for (i in 0 until stuffing - 2) {
                            packet[pos++] = 0xFF.toByte()
                        }
                    }
                }
            }

            System.arraycopy(pesPacket, offset, packet, pos, payloadLen)
            offset += payloadLen
            videoCc = (videoCc + 1) and 0x0F
            isFirst = false
            output?.write(packet)
        }
    }

    fun stop() {
        try {
            output?.flush()
            output?.close()
        } catch (_: Exception) {}
        output = null
    }

    private fun buildPesHeader(pts90k: Long): ByteArray {
        val header = ByteArray(14)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = 0xE0.toByte()
        header[4] = 0x00
        header[5] = 0x00
        header[6] = 0x80.toByte()
        header[7] = 0x80.toByte()
        header[8] = 0x05
        encodePts(pts90k, header, 9)
        return header
    }

    private fun encodePts(pts: Long, buf: ByteArray, offset: Int) {
        buf[offset] = (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte()
        buf[offset + 1] = ((pts shr 22) and 0xFF).toByte()
        buf[offset + 2] = (((pts shr 14) and 0xFE).toInt() or 0x01).toByte()
        buf[offset + 3] = ((pts shr 7) and 0xFF).toByte()
        buf[offset + 4] = (((pts shl 1) and 0xFE).toInt() or 0x01).toByte()
    }

    private fun writePcrBytes(packet: ByteArray, offset: Int, pts90k: Long) {
        val pcrBase = pts90k
        packet[offset] = ((pcrBase shr 25) and 0xFF).toByte()
        packet[offset + 1] = ((pcrBase shr 17) and 0xFF).toByte()
        packet[offset + 2] = ((pcrBase shr 9) and 0xFF).toByte()
        packet[offset + 3] = ((pcrBase shr 1) and 0xFF).toByte()
        packet[offset + 4] = (((pcrBase.toInt() and 0x01) shl 7) or 0x7E).toByte()
        packet[offset + 5] = 0x00
    }

    private fun writePat() {
        val packet = ByteArray(TS_PACKET_SIZE)
        packet[0] = 0x47
        packet[1] = 0x40
        packet[2] = 0x00
        packet[3] = (0x10 or (patCc and 0x0F)).toByte()

        var pos = 4
        packet[pos++] = 0x00 // Pointer field

        val tableStart = pos
        packet[pos++] = 0x00 // Table ID
        val slPos = pos
        pos += 2
        packet[pos++] = 0x00; packet[pos++] = 0x01 // TS ID
        packet[pos++] = 0xC1.toByte()
        packet[pos++] = 0x00; packet[pos++] = 0x00

        packet[pos++] = 0x00; packet[pos++] = 0x01 // Program 1
        packet[pos++] = (0xE0 or ((PMT_PID shr 8) and 0x1F)).toByte()
        packet[pos++] = (PMT_PID and 0xFF).toByte()

        val sectionLen = pos - slPos - 2 + 4
        packet[slPos] = (0xB0 or ((sectionLen shr 8) and 0x0F)).toByte()
        packet[slPos + 1] = (sectionLen and 0xFF).toByte()

        val crc = crc32(packet, tableStart, pos - tableStart)
        packet[pos++] = ((crc shr 24) and 0xFF).toByte()
        packet[pos++] = ((crc shr 16) and 0xFF).toByte()
        packet[pos++] = ((crc shr 8) and 0xFF).toByte()
        packet[pos++] = (crc and 0xFF).toByte()

        while (pos < TS_PACKET_SIZE) packet[pos++] = 0xFF.toByte()
        patCc = (patCc + 1) and 0x0F
        output?.write(packet)
    }

    private fun writePmt() {
        val packet = ByteArray(TS_PACKET_SIZE)
        packet[0] = 0x47
        packet[1] = (0x40 or ((PMT_PID shr 8) and 0x1F)).toByte()
        packet[2] = (PMT_PID and 0xFF).toByte()
        packet[3] = (0x10 or (pmtCc and 0x0F)).toByte()

        var pos = 4
        packet[pos++] = 0x00

        val tableStart = pos
        packet[pos++] = 0x02 // PMT table ID
        val slPos = pos
        pos += 2
        packet[pos++] = 0x00; packet[pos++] = 0x01 // Program number
        packet[pos++] = 0xC1.toByte()
        packet[pos++] = 0x00; packet[pos++] = 0x00

        // PCR PID
        packet[pos++] = (0xE0 or ((VIDEO_PID shr 8) and 0x1F)).toByte()
        packet[pos++] = (VIDEO_PID and 0xFF).toByte()
        packet[pos++] = 0xF0.toByte(); packet[pos++] = 0x00 // Program info length

        // H.264 stream entry
        packet[pos++] = H264_STREAM_TYPE.toByte()
        packet[pos++] = (0xE0 or ((VIDEO_PID shr 8) and 0x1F)).toByte()
        packet[pos++] = (VIDEO_PID and 0xFF).toByte()
        packet[pos++] = 0xF0.toByte(); packet[pos++] = 0x00 // ES info length

        val sectionLen = pos - slPos - 2 + 4
        packet[slPos] = (0xB0 or ((sectionLen shr 8) and 0x0F)).toByte()
        packet[slPos + 1] = (sectionLen and 0xFF).toByte()

        val crc = crc32(packet, tableStart, pos - tableStart)
        packet[pos++] = ((crc shr 24) and 0xFF).toByte()
        packet[pos++] = ((crc shr 16) and 0xFF).toByte()
        packet[pos++] = ((crc shr 8) and 0xFF).toByte()
        packet[pos++] = (crc and 0xFF).toByte()

        while (pos < TS_PACKET_SIZE) packet[pos++] = 0xFF.toByte()
        pmtCc = (pmtCc + 1) and 0x0F
        output?.write(packet)
    }

    private fun crc32(data: ByteArray, offset: Int, length: Int): Int {
        var crc = -1
        for (i in offset until offset + length) {
            val idx = ((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor CRC_TABLE[idx]
        }
        return crc
    }
}
