package app.dzz.copilot.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailChunksTest {
    private fun chunk(index: Int, total: Int, data: ByteArray): ByteArray =
        byteArrayOf(0x01, total.toByte(), (total shr 8).toByte(), index.toByte(), (index shr 8).toByte()) + data

    @Test
    fun requestMatchesTheDzzApp() {
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0x00), ThumbnailChunks.request(0))
        assertArrayEquals(byteArrayOf(0x01, 0x2C, 0x01), ThumbnailChunks.request(300))
        // Header as LargeDataHandler.addHeader builds it: BC FD len(LE) crc(LE) payload.
        val packet = QcProtocol.build(QcProtocol.THUMBNAIL, ThumbnailChunks.request(1))
        assertEquals(0xBC, packet[0].toInt() and 0xFF)
        assertEquals(0xFD, packet[1].toInt() and 0xFF)
        assertEquals(3, packet[2].toInt())
    }

    @Test
    fun joinsChunksInOrderIntoAJpeg() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5, 6, 0xFF.toByte(), 0xD9.toByte())
        val c = ThumbnailChunks()
        assertEquals(0, c.add(chunk(0, 3, jpeg.copyOfRange(0, 4))))
        assertNull(c.jpeg())
        assertEquals(1, c.add(chunk(1, 3, jpeg.copyOfRange(4, 7))))
        assertEquals(1, c.add(chunk(1, 3, jpeg.copyOfRange(4, 7)))) // duplicate is ignored
        assertEquals(2, c.add(chunk(2, 3, jpeg.copyOfRange(7, 10))))
        assertArrayEquals(jpeg, c.jpeg())
    }

    @Test
    fun rejectsGarbage() {
        val c = ThumbnailChunks()
        assertEquals(-1, c.add(byteArrayOf(1, 2)))
        assertEquals(-1, c.add(chunk(5, 3, byteArrayOf(1))))
    }

    @Test
    fun assemblerAcceptsThumbnailFramesWithoutCrc() {
        val payload = chunk(0, 1, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 0xFF.toByte(), 0xD9.toByte()))
        val frame = QcProtocol.build(QcProtocol.THUMBNAIL, payload)
        frame[4] = 0
        frame[5] = 0 // the glasses may leave the CRC empty
        val a = FrameAssembler()
        val got = a.push(frame.copyOfRange(0, 7)) + a.push(frame.copyOfRange(7, frame.size))
        assertEquals(1, got.size)
        assertEquals(QcProtocol.THUMBNAIL, got[0].cmd)
        assertArrayEquals(payload, got[0].payload)
    }

    @Test
    fun firstMissingFindsTheGapAndRepeatsAreHarmless() {
        val c = ThumbnailChunks()
        assertEquals(0, c.firstMissing())
        c.add(chunk(0, 3, byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        c.add(chunk(2, 3, byteArrayOf(0xFF.toByte(), 0xD9.toByte())))
        assertEquals(1, c.firstMissing())
        c.add(chunk(0, 3, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))) // resend race
        assertEquals(2, c.count)
        c.add(chunk(1, 3, byteArrayOf(7)))
        assertEquals(-1, c.firstMissing())
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 0xFF.toByte(), 0xD9.toByte()), c.jpeg())
    }

    @Test
    fun aiPhotoIsTheFiveByteCommand() {
        assertArrayEquals(byteArrayOf(2, 1, 6, 2, 2), QcProtocol.aiPhoto())
        assertArrayEquals(byteArrayOf(2, 1), QcProtocol.aiSpeak(1))
    }
}
