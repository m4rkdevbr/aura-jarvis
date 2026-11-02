package app.dzz.copilot.voice

import java.io.ByteArrayOutputStream

/**
 * Energy VAD that cuts 20 ms PCM frames (16 kHz, mono, 16-bit) into speech segments.
 * Shared by the phone microphone and the glasses microphone, so both behave the same.
 */
class SpeechSegmenter(
    private val startRms: Double = 380.0,
    private val endRms: Double = 220.0,
    private val endSilenceFrames: Int = 28,
    private val minSpeechFrames: Int = 18,
    private val maxSpeechFrames: Int = 400,
    private val minBytes: Int = 16_000,
) {
    private val speech = ByteArrayOutputStream()
    private var inSpeech = false
    private var silentFrames = 0
    private var speechFrames = 0

    fun reset() {
        inSpeech = false
        speech.reset()
        silentFrames = 0
        speechFrames = 0
    }

    /** Returns a finished segment's PCM bytes (little endian), or null while still listening. */
    fun push(frame: ShortArray, n: Int): ByteArray? {
        if (n <= 0) return null
        val rms = rms(frame, n)
        if (!inSpeech) {
            if (rms >= startRms) {
                inSpeech = true
                silentFrames = 0
                speechFrames = 1
                speech.reset()
                writePcm(speech, frame, n)
            }
            return null
        }
        writePcm(speech, frame, n)
        speechFrames += 1
        if (rms < endRms) silentFrames += 1 else silentFrames = 0
        val tooLong = speechFrames >= maxSpeechFrames
        val ended = silentFrames >= endSilenceFrames && speechFrames >= minSpeechFrames
        if (!ended && !tooLong) return null
        val pcm = speech.toByteArray()
        reset()
        return pcm.takeIf { it.size >= minBytes }
    }

    companion object {
        fun rms(frame: ShortArray, n: Int): Double {
            var sum = 0.0
            for (i in 0 until n) {
                val v = frame[i].toDouble()
                sum += v * v
            }
            return kotlin.math.sqrt(sum / n.coerceAtLeast(1))
        }

        fun writePcm(out: ByteArrayOutputStream, frame: ShortArray, n: Int) {
            out.write(toBytes(frame, n))
        }

        /** 16-bit little-endian PCM bytes of the first [n] samples. */
        fun toBytes(frame: ShortArray, n: Int): ByteArray {
            val bytes = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = frame[i].toInt()
                bytes[2 * i] = (v and 0xFF).toByte()
                bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return bytes
        }
    }
}
