package app.dzz.copilot.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesAudioTest {
    @Test fun opusPacketsAreCutAt40BytesAcrossPayloads() {
        val p = GlassesAudio.OpusPackets()
        assertEquals(0, p.push(ByteArray(30) { 1 }).size)
        val two = p.push(ByteArray(55) { 2 })
        assertEquals(2, two.size)
        assertEquals(1, two[0][0].toInt())
        assertEquals(2, two[0][39].toInt())
        assertEquals(5, p.push(ByteArray(0)).size + 5)
    }

    @Test fun opusHeadHasTheRightLayout() {
        val h = GlassesAudio.opusHead()
        assertEquals(19, h.size)
        assertArrayEquals("OpusHead".toByteArray(), h.copyOfRange(0, 8))
        assertEquals(1, h[9].toInt())
        assertEquals(16_000, (h[12].toInt() and 0xFF) or ((h[13].toInt() and 0xFF) shl 8))
    }

    @Test fun framerDecimates48kTo16kFrames() {
        val f = GlassesAudio.Framer()
        val frames = ArrayList<ShortArray>()
        f.push(ShortArray(960 * 2) { 300 }, 48_000, 1) { frames += it }
        assertEquals(2, frames.size)
        assertEquals(320, frames[0].size)
        assertEquals(300, frames[0][0].toInt())
        frames.clear()
        f.push(ShortArray(320) { 5 }, 16_000, 1) { frames += it }
        assertEquals(1, frames.size)
    }

    @Test fun segmenterCutsSpeechAfterSilence() {
        val s = SpeechSegmenter()
        val loud = ShortArray(320) { if (it % 2 == 0) 2000 else -2000 }
        val quiet = ShortArray(320)
        repeat(5) { assertNull(s.push(quiet, 320)) }
        repeat(40) { assertNull(s.push(loud, 320)) }
        var out: ByteArray? = null
        repeat(28) { out = s.push(quiet, 320) ?: out }
        assertNotNull(out)
        assertEquals((1 + 39 + 28) * 640, out!!.size)
    }
}
