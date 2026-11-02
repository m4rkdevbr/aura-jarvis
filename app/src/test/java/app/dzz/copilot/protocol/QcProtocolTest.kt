package app.dzz.copilot.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class QcProtocolTest {
    @Test
    fun photoPacketUsesModbusCrc() {
        val packet = QcProtocol.build(QcProtocol.CONTROL, byteArrayOf(0x02, 0x01, 0x01))
        assertArrayEquals(
            byteArrayOf(0xBC.toByte(), 0x41, 0x03, 0x00, 0x10, 0x50, 0x02, 0x01, 0x01),
            packet,
        )
    }

    @Test
    fun knownModbusVector() {
        assertEquals(0x4B37, QcProtocol.crc16("123456789".toByteArray()))
    }

    @Test
    fun splitNotificationReassembles() {
        val packet = QcProtocol.build(QcProtocol.BATTERY, byteArrayOf(0x40, 0x00))
        val assembler = FrameAssembler()
        assertEquals(0, assembler.push(packet.copyOfRange(0, 4)).size)
        val frames = assembler.push(packet.copyOfRange(4, packet.size))
        assertEquals(1, frames.size)
        assertEquals(QcProtocol.BATTERY, frames[0].cmd)
        assertArrayEquals(byteArrayOf(0x40, 0x00), frames[0].payload)
    }
}
