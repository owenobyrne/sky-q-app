package ie.owen.skyq.data.htsp

import java.io.OutputStream

// PIDs
private const val PID_PAT   = 0x0000
private const val PID_PMT   = 0x0100
private const val PID_VIDEO = 0x0101
private const val PID_AUDIO = 0x0102

// MPEG-TS stream type bytes
private const val ST_H264  = 0x1B
private const val ST_HEVC  = 0x24
private const val ST_AAC   = 0x0F   // ADTS
private const val ST_MP2   = 0x04
private const val ST_AC3   = 0x81
private const val ST_EAC3  = 0x87

/**
 * Minimal MPEG-TS muxer that bridges raw HTSP codec frames to a byte stream
 * ExoPlayer can parse.
 *
 * 1. Call [init] with the streams list from subscriptionStart.
 * 2. Call [mux] for every muxpkt received.
 */
@Suppress("ArrayInDataClass")
class TsMuxer(private val out: OutputStream) {

    data class StreamInfo(
        val index: Int,
        val pid: Int,
        val streamType: Int,
        val isVideo: Boolean,
        val aacProfile: Int = 2,
        val aacSrIndex: Int = 3,   // 48 kHz
        val aacChannels: Int = 2
    )

    private val streams = mutableMapOf<Int, StreamInfo>()  // htsp stream index → info
    private val cc      = mutableMapOf<Int, Int>()          // pid → continuity counter
    private var patPmtWritten = false

    @Volatile var livePts:    Long = Long.MIN_VALUE
    @Volatile var currentPts: Long = Long.MIN_VALUE

    fun init(htspStreams: List<HtspMsg>): Boolean {
        streams.clear(); cc.clear(); patPmtWritten = false
        var videoTaken = false; var audioTaken = false
        for (s in htspStreams) {
            val idx  = s.int("index") ?: continue
            val type = s.str("type")?.uppercase() ?: continue
            when (type) {
                "H264" -> if (!videoTaken) {
                    streams[idx] = StreamInfo(idx, PID_VIDEO, ST_H264, true); videoTaken = true
                }
                "HEVC", "H265" -> if (!videoTaken) {
                    streams[idx] = StreamInfo(idx, PID_VIDEO, ST_HEVC, true); videoTaken = true
                }
                "AAC" -> if (!audioTaken) {
                    val (p, sr, ch) = parseAacConfig(s.bytes("meta"))
                    streams[idx] = StreamInfo(idx, PID_AUDIO, ST_AAC, false, p, sr, ch)
                    audioTaken = true
                }
                "MP2", "MPEG2AUDIO" -> if (!audioTaken) {
                    streams[idx] = StreamInfo(idx, PID_AUDIO, ST_MP2, false); audioTaken = true
                }
                "AC3" -> if (!audioTaken) {
                    streams[idx] = StreamInfo(idx, PID_AUDIO, ST_AC3, false); audioTaken = true
                }
                "EAC3" -> if (!audioTaken) {
                    streams[idx] = StreamInfo(idx, PID_AUDIO, ST_EAC3, false); audioTaken = true
                }
            }
        }
        for (pid in listOf(PID_PAT, PID_PMT, PID_VIDEO, PID_AUDIO)) cc[pid] = 0
        return streams.isNotEmpty()
    }

    fun mux(streamIndex: Int, pts: Long, dts: Long, payload: ByteArray, isKey: Boolean) {
        val info = streams[streamIndex] ?: return
        if (pts > livePts) livePts = pts
        currentPts = pts

        if (!patPmtWritten || (info.isVideo && isKey)) {
            writePat(); writePmt(); patPmtWritten = true
        }

        val data = if (info.streamType == ST_AAC && !hasAdtsHeader(payload)) adtsWrap(info, payload) else payload
        writePes(info, pts, dts, data, isKey)
    }

    // ── PAT ─────────────────────────────────────────────────────────────────

    private fun writePat() {
        // section before CRC
        val s = bytes(
            0x00,                               // table_id
            0xB0, 0x0D,                         // syntax + section_length = 13
            0x00, 0x01,                         // transport_stream_id
            0xC1,                               // version 0, current
            0x00, 0x00,                         // section / last section number
            0x00, 0x01,                         // program_number 1
            0xE0 or (PID_PMT shr 8), PID_PMT and 0xFF
        )
        val payload = byteArrayOf(0x00) + s + crc32be(s)   // pointer_field=0 prepended
        writeTsPacket(PID_PAT, pusi = true, af = null, payload = payload)
    }

    // ── PMT ─────────────────────────────────────────────────────────────────

    private fun writePmt() {
        val video  = streams.values.firstOrNull { it.isVideo }
        val audio  = streams.values.firstOrNull { !it.isVideo }
        val pcrPid = video?.pid ?: audio?.pid ?: PID_VIDEO

        val streamEntries = mutableListOf<Byte>()
        for (s in listOfNotNull(video, audio)) {
            streamEntries += listOf(
                s.streamType.toByte(),
                (0xE0 or (s.pid shr 8)).toByte(), (s.pid and 0xFF).toByte(),
                0xF0.toByte(), 0x00.toByte()
            )
        }
        val entries = streamEntries.toByteArray()

        val secLen = 9 + entries.size + 4     // body before CRC + CRC
        val header = bytes(
            0x02,                               // table_id
            0xB0 or (secLen shr 8), secLen and 0xFF,
            0x00, 0x01, 0xC1, 0x00, 0x00,       // program + version + sections
            0xE0 or (pcrPid shr 8), pcrPid and 0xFF,
            0xF0, 0x00                           // program_info_length = 0
        )
        val s = header + entries
        val payload = byteArrayOf(0x00) + s + crc32be(s)
        writeTsPacket(PID_PMT, pusi = true, af = null, payload = payload)
    }

    // ── PES ─────────────────────────────────────────────────────────────────

    private fun writePes(info: StreamInfo, pts: Long, dts: Long, data: ByteArray, isKey: Boolean) {
        val streamId = if (info.isVideo) 0xE0 else 0xC0
        val hasDts   = info.isVideo && dts != pts && dts > 0L
        val ptsDtsFlags = if (hasDts) 0xC0 else 0x80
        val optLen   = if (hasDts) 10 else 5          // PTS [+ DTS] bytes

        // PES packet length: 0 for unbounded video, actual size for audio
        val pesBodyLen = 3 + optLen + data.size       // flags(1)+flags(1)+hdrLen(1) + opts + data
        val pesLen     = if (info.isVideo) 0 else pesBodyLen

        val ptsBytes = encodePts(pts, if (hasDts) 0x30 else 0x20)
        val dtsBytes = if (hasDts) encodePts(dts, 0x10) else ByteArray(0)

        val pesHeader = bytes(0x00, 0x00, 0x01, streamId,
            pesLen shr 8, pesLen and 0xFF,
            0x80, ptsDtsFlags, optLen
        ) + ptsBytes + dtsBytes

        val adaptation = if (isKey && info.isVideo) buildPcr(pts) else null
        // adaptation field in packet = length_byte(1) + af.size bytes
        val afBlockSize = if (adaptation != null) 1 + adaptation.size else 0

        val stream = pesHeader + data
        val firstSize = minOf(184 - afBlockSize, stream.size)
        writeTsPacket(info.pid, pusi = true, af = adaptation, payload = stream.copyOfRange(0, firstSize))

        var off = firstSize
        while (off < stream.size) {
            val end = minOf(off + 184, stream.size)
            writeTsPacket(info.pid, pusi = false, af = null, payload = stream.copyOfRange(off, end))
            off = end
        }
    }

    // ── TS packet framing ────────────────────────────────────────────────────

    private fun writeTsPacket(pid: Int, pusi: Boolean, af: ByteArray?, payload: ByteArray) {
        val pkt = ByteArray(188)
        var p   = 0

        pkt[p++] = 0x47.toByte()
        pkt[p++] = ((if (pusi) 0x40 else 0x00) or ((pid shr 8) and 0x1F)).toByte()
        pkt[p++] = (pid and 0xFF).toByte()

        val c  = (cc[pid] ?: 0) and 0x0F
        cc[pid] = (c + 1) and 0x0F

        val afc = if (af != null) if (payload.isNotEmpty()) 0x30 else 0x20 else 0x10
        pkt[p++] = (afc or c).toByte()

        if (af != null) {
            pkt[p++] = af.size.toByte()          // adaptation_field_length
            af.copyInto(pkt, p); p += af.size
        }

        val payLen = minOf(payload.size, 188 - p)
        payload.copyInto(pkt, p, 0, payLen); p += payLen

        while (p < 188) pkt[p++] = 0xFF.toByte()  // stuffing
        out.write(pkt)
    }

    // ── PCR adaptation field body (without length byte) ─────────────────────

    private fun buildPcr(pts: Long): ByteArray {
        val base = pts and 0x1FF_FFFF_FFL
        return byteArrayOf(
            0x50.toByte(),                                       // random_access_indicator (0x40) + PCR_flag (0x10)
            (base shr 25).toByte(),
            (base shr 17).toByte(),
            (base shr 9).toByte(),
            (base shr 1).toByte(),
            (((base and 1L) shl 7) or 0x7EL).toByte(),          // 6 reserved = 1, ext MSB = 0
            0x00.toByte()                                        // PCR extension = 0
        )
    }

    // ── PTS/DTS encoding ────────────────────────────────────────────────────

    // marker nibble: 0x20 = PTS-only, 0x30 = PTS when DTS follows, 0x10 = DTS
    private fun encodePts(v: Long, marker: Int) = byteArrayOf(
        (marker or ((v shr 29).toInt() and 0x0E) or 0x01).toByte(),
        ((v shr 22).toInt() and 0xFF).toByte(),
        (((v shr 14).toInt() and 0xFE) or 0x01).toByte(),
        ((v shr 7).toInt() and 0xFF).toByte(),
        (((v shl 1).toInt() and 0xFE) or 0x01).toByte()
    )

    // ── ADTS wrapping for AAC frames ─────────────────────────────────────────

    // TVHeadend's transcoded AAC arrives already ADTS-framed; raw AAC does not.
    private fun hasAdtsHeader(p: ByteArray): Boolean =
        p.size >= 2 && p[0] == 0xFF.toByte() && (p[1].toInt() and 0xF6) == 0xF0

    private fun adtsWrap(info: StreamInfo, payload: ByteArray): ByteArray {
        val total = payload.size + 7
        val p = info.aacProfile; val s = info.aacSrIndex; val c = info.aacChannels
        val header = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((p - 1) and 0x3) shl 6 or ((s and 0xF) shl 2) or ((c shr 2) and 0x1)).toByte(),
            (((c and 0x3) shl 6) or ((total shr 11) and 0x3)).toByte(),
            ((total shr 3) and 0xFF).toByte(),
            (((total and 0x7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
        return header + payload
    }

    // Parses AudioSpecificConfig — only the first 3 fields matter.
    private fun parseAacConfig(meta: ByteArray?): Triple<Int, Int, Int> {
        if (meta == null || meta.isEmpty()) return Triple(2, 3, 2)
        val b0 = meta[0].toInt() and 0xFF
        val b1 = if (meta.size > 1) meta[1].toInt() and 0xFF else 0
        return Triple(
            ((b0 shr 3) and 0x1F).coerceAtLeast(2),
            (((b0 and 7) shl 1) or (b1 shr 7)).coerceIn(0, 12),
            ((b1 shr 3) and 0xF).coerceAtLeast(1)
        )
    }

    // ── CRC-32/MPEG-2 ────────────────────────────────────────────────────────

    private fun crc32be(data: ByteArray): ByteArray {
        var crc = -1  // 0xFFFF_FFFF
        for (b in data) {
            val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor TABLE[idx]
        }
        return byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }

    companion object {
        private val TABLE = IntArray(256) { i ->
            var c = i shl 24
            repeat(8) { c = if (c < 0) (c shl 1) xor 0x04C11DB7.toInt() else c shl 1 }
            c
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private operator fun MutableList<Byte>.plusAssign(list: List<Byte>) { addAll(list) }

private fun bytes(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }
