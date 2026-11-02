package app.dzz.copilot.protocol

class QcFrame(val cmd: Int, val payload: ByteArray)

/**
 * Framing used by the QC / oudmon channel found in the DZZ app:
 * service de5bf728, notify de5bf729, write de5bf72a.
 *
 * CRC-16/MODBUS over the payload. A secondary write-up prints 0x5510 for the
 * photo payload; the standard vector ("123456789" -> 0x4B37) yields 0x5010.
 */
object QcProtocol {
    const val SYNC_TIME = 0x40
    const val CONTROL = 0x41
    const val BATTERY = 0x42
    const val DEVICE_INFO = 0x43
    const val HEARTBEAT = 0x45
    const val GPT_UPLOAD = 0x59
    const val DATA_REPORT = 0x73
    const val IP_NOTIFY = 0x08
    const val THUMBNAIL = 0xFD
    /** "Aura is speaking" for the glasses: [02, 1 start | 2 hold (every 2 s) | 3 stop]. */
    const val AI_SPEAK = 0x48

    fun aiSpeak(mode: Int) = byteArrayOf(0x02, mode.toByte())

    fun photo() = byteArrayOf(0x02, 0x01, 0x01)
    fun videoStart() = byteArrayOf(0x02, 0x01, 0x02)
    fun videoStop() = byteArrayOf(0x02, 0x01, 0x03)
    fun transfer() = byteArrayOf(0x02, 0x01, 0x04)
    /** AI photo as the DZZ app sends it: [02 01 06 quality quality] (quality 2 of 0..5). */
    fun aiPhoto() = byteArrayOf(0x02, 0x01, 0x06, 0x02, 0x02)
    fun speechStart() = byteArrayOf(0x02, 0x01, 0x07)
    fun audioStart() = byteArrayOf(0x02, 0x01, 0x08)
    fun transferStop() = byteArrayOf(0x02, 0x01, 0x09)
    fun speechStop() = byteArrayOf(0x02, 0x01, 0x0B)
    fun audioStop() = byteArrayOf(0x02, 0x01, 0x0C)
    fun find() = byteArrayOf(0x02, 0x01, 0x0D)
    fun translateStart() = byteArrayOf(0x02, 0x01, 0x12)
    fun translateStop() = byteArrayOf(0x02, 0x01, 0x13)

    fun timePayload(unixSeconds: Long = System.currentTimeMillis() / 1000L): ByteArray {
        return ByteArray(4) { index ->
            ((unixSeconds shr (8 * index)) and 0xFF).toByte()
        }
    }

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    fun build(cmd: Int, payload: ByteArray): ByteArray {
        val crc = crc16(payload)
        val out = ByteArray(6 + payload.size)
        out[0] = 0xBC.toByte()
        out[1] = (cmd and 0xFF).toByte()
        out[2] = (payload.size and 0xFF).toByte()
        out[3] = ((payload.size shr 8) and 0xFF).toByte()
        out[4] = (crc and 0xFF).toByte()
        out[5] = ((crc shr 8) and 0xFF).toByte()
        payload.copyInto(out, destinationOffset = 6)
        return out
    }
}

class FrameAssembler {
    private var buffer = ByteArray(0)

    fun clear() {
        buffer = ByteArray(0)
    }

    fun push(chunk: ByteArray): List<QcFrame> {
        if (chunk.isEmpty()) return emptyList()
        buffer += chunk
        if (buffer.size > 96_000) {
            buffer = buffer.copyOfRange(buffer.size - 48_000, buffer.size)
        }
        val frames = ArrayList<QcFrame>()
        var guard = 0
        while (buffer.size >= 6 && guard++ < 2_000) {
            val start = buffer.indexOfFirst { (it.toInt() and 0xFF) == 0xBC }
            if (start < 0) {
                buffer = ByteArray(0)
                break
            }
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            if (buffer.size < 6) break
            val length = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
            if (length > 48_000) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            val total = 6 + length
            if (buffer.size < total) break
            val payload = buffer.copyOfRange(6, total)
            val crc = (buffer[4].toInt() and 0xFF) or ((buffer[5].toInt() and 0xFF) shl 8)
            val cmd = buffer[1].toInt() and 0xFF
            // The DZZ app never checks CRC on replies; thumbnail chunks and device reports may not carry one.
            val lenient = cmd == QcProtocol.THUMBNAIL || cmd == QcProtocol.DATA_REPORT
            if (crc == QcProtocol.crc16(payload) || (lenient && (buffer.size == total || (buffer[total].toInt() and 0xFF) == 0xBC))) {
                frames += QcFrame(buffer[1].toInt() and 0xFF, payload)
                buffer = buffer.copyOfRange(total, buffer.size)
            } else {
                buffer = buffer.copyOfRange(1, buffer.size)
            }
        }
        return frames
    }
}

/**
 * AI-photo thumbnail over BLE, as the official DZZ app does it (QC SDK LargeDataHandler):
 * the phone asks for chunk N with cmd 0xFD payload [01, N lo, N hi]; each reply payload is
 * [?, total lo, total hi, index lo, index hi, jpeg bytes...]. The phone asks for the next index
 * until index + 1 == total, then joins the chunks in order.
 */
class ThumbnailChunks {
    private val chunks = LinkedHashMap<Int, ByteArray>()
    var total = 0
        private set

    val count: Int get() = chunks.size

    fun reset() {
        chunks.clear()
        total = 0
    }

    /** Index of the chunk in [payload], or -1 when the payload is not a thumbnail chunk. */
    fun add(payload: ByteArray): Int {
        if (payload.size < 6) return -1
        val all = u16(payload, 1)
        val index = u16(payload, 3)
        if (all <= 0 || index < 0 || index >= all) return -1
        // A different total means a new image; a repeated chunk (resend race) is simply ignored.
        if (total != all) {
            chunks.clear()
        }
        total = all
        chunks.putIfAbsent(index, payload.copyOfRange(5, payload.size))
        return index
    }

    /** Lowest chunk index still missing (0 before the first chunk), or -1 when all arrived. */
    fun firstMissing(): Int {
        if (total <= 0) return 0
        for (i in 0 until total) if (!chunks.containsKey(i)) return i
        return -1
    }

    /** The whole JPEG once every chunk arrived and it looks like a JPEG, else null. */
    fun jpeg(): ByteArray? {
        if (total <= 0 || chunks.size != total) return null
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until total) out.write(chunks[i] ?: return null)
        val bytes = out.toByteArray()
        val start = (0 until bytes.size - 1).firstOrNull {
            bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xD8.toByte()
        } ?: return null
        var end = bytes.size - 2
        while (end > start && !(bytes[end] == 0xFF.toByte() && bytes[end + 1] == 0xD9.toByte())) end--
        return if (end > start) bytes.copyOfRange(start, end + 2) else bytes.copyOfRange(start, bytes.size)
    }

    companion object {
        fun request(index: Int): ByteArray = byteArrayOf(0x01, (index and 0xFF).toByte(), ((index shr 8) and 0xFF).toByte())

        private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }
}
