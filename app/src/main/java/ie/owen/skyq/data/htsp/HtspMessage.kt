package ie.owen.skyq.data.htsp

import java.io.ByteArrayOutputStream

typealias HtspMsg = HashMap<String, Any?>

fun HtspMsg.str(key: String) = this[key] as? String
fun HtspMsg.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Int  -> v.toLong()
    else    -> null
}
fun HtspMsg.int(key: String) = long(key)?.toInt()
fun HtspMsg.bytes(key: String) = this[key] as? ByteArray
@Suppress("UNCHECKED_CAST")
fun HtspMsg.list(key: String) = this[key] as? List<HtspMsg>

fun htspMsg(vararg pairs: Pair<String, Any?>) = hashMapOf(*pairs)

/**
 * HMF (HTSP Message Format) codec matching TVHeadend's htsmsg_binary.c exactly.
 *
 * Wire layout per field:
 *   [type : 1 byte]
 *   [namelen : 1 byte]
 *   [datalen : 4 bytes big-endian]  ← comes BEFORE the name
 *   [name : namelen bytes]
 *   [data : datalen bytes]
 *
 * S64 encoding: variable-length little-endian (0 bytes for value 0).
 * Message frame: [body-length : 4 bytes BE] [fields…]
 */
object HtspMessage {

    private const val TYPE_MAP:  Int = 1
    private const val TYPE_S64:  Int = 2
    private const val TYPE_STR:  Int = 3
    private const val TYPE_BIN:  Int = 4
    private const val TYPE_LIST: Int = 5

    // ── Parsing ───────────────────────────────────────────────────────────────

    fun parse(buf: ByteArray): HtspMsg = parseFields(buf, 0, buf.size)

    private fun parseFields(buf: ByteArray, start: Int, length: Int): HtspMsg {
        val msg = HtspMsg()
        var pos = start
        val end = start + length

        while (pos + 6 <= end) {
            val type    = buf[pos    ].toInt() and 0xFF
            val nameLen = buf[pos + 1].toInt() and 0xFF
            val dataLen = ((buf[pos + 2].toInt() and 0xFF) shl 24) or
                          ((buf[pos + 3].toInt() and 0xFF) shl 16) or
                          ((buf[pos + 4].toInt() and 0xFF) shl 8)  or
                           (buf[pos + 5].toInt() and 0xFF)
            pos += 6

            if (pos + nameLen + dataLen > end) break

            val name = if (nameLen > 0) String(buf, pos, nameLen, Charsets.UTF_8) else ""
            pos += nameLen

            if (name.isNotEmpty()) {
                when (type) {
                    TYPE_S64 -> {
                        // variable-length little-endian
                        var u64 = 0L
                        for (i in dataLen - 1 downTo 0)
                            u64 = (u64 shl 8) or (buf[pos + i].toLong() and 0xFF)
                        msg[name] = u64
                    }
                    TYPE_STR  -> msg[name] = String(buf, pos, dataLen, Charsets.UTF_8)
                    TYPE_BIN  -> msg[name] = buf.copyOfRange(pos, pos + dataLen)
                    TYPE_MAP  -> msg[name] = parseFields(buf, pos, dataLen)
                    TYPE_LIST -> msg[name] = parseList(buf, pos, dataLen)
                }
            }

            pos += dataLen
        }
        return msg
    }

    private fun parseList(buf: ByteArray, start: Int, length: Int): List<HtspMsg> {
        val items = mutableListOf<HtspMsg>()
        var pos = start
        val end = start + length

        while (pos + 6 <= end) {
            val type    = buf[pos    ].toInt() and 0xFF
            val nameLen = buf[pos + 1].toInt() and 0xFF
            val dataLen = ((buf[pos + 2].toInt() and 0xFF) shl 24) or
                          ((buf[pos + 3].toInt() and 0xFF) shl 16) or
                          ((buf[pos + 4].toInt() and 0xFF) shl 8)  or
                           (buf[pos + 5].toInt() and 0xFF)
            pos += 6 + nameLen  // list entries have namelen=0 by convention

            if (pos + dataLen > end) break
            if (type == TYPE_MAP) items.add(parseFields(buf, pos, dataLen))
            pos += dataLen
        }
        return items
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    fun serialize(msg: HtspMsg): ByteArray {
        val body = ByteArrayOutputStream()
        serializeFields(msg, body)
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream(4 + bodyBytes.size)
        writeInt(out, bodyBytes.size)
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun serializeFields(msg: HtspMsg, out: ByteArrayOutputStream) {
        for ((name, value) in msg) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            when (value) {
                is Long    -> serializeS64(out, nameBytes, value)
                is Int     -> serializeS64(out, nameBytes, value.toLong())
                is String  -> {
                    val data = value.toByteArray(Charsets.UTF_8)
                    writeHeader(out, TYPE_STR, nameBytes, data.size)
                    out.write(data)
                }
                is ByteArray -> {
                    writeHeader(out, TYPE_BIN, nameBytes, value.size)
                    out.write(value)
                }
            }
        }
    }

    private fun serializeS64(out: ByteArrayOutputStream, nameBytes: ByteArray, value: Long) {
        // Count bytes needed (strip leading zero bytes from big-end)
        var u = value
        val data = ByteArrayOutputStream(8)
        while (u != 0L) {
            data.write((u and 0xFF).toInt())
            u = u ushr 8
        }
        val bytes = data.toByteArray()  // little-endian bytes, length = minimum needed
        writeHeader(out, TYPE_S64, nameBytes, bytes.size)
        out.write(bytes)
    }

    private fun writeHeader(out: ByteArrayOutputStream, type: Int, nameBytes: ByteArray, dataLen: Int) {
        out.write(type)
        out.write(nameBytes.size)
        writeInt(out, dataLen)
        out.write(nameBytes)
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v ushr 24)
        out.write(v ushr 16)
        out.write(v ushr 8)
        out.write(v)
    }
}
