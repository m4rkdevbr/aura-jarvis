package app.dzz.copilot.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class VoiceFocusTest {
    private fun rms(a: ShortArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i].toDouble() * a[i]
        return sqrt(s / (to - from))
    }

    private fun runReducer(input: ShortArray): ShortArray {
        val r = NoiseReducer()
        val out = ShortArray(input.size)
        var at = 0
        while (at < input.size) {
            val n = minOf(320, input.size - at)
            val chunk = input.copyOfRange(at, at + n)
            r.process(chunk, n).copyInto(out, at)
            at += n
        }
        return out
    }

    @Test
    fun fftRoundTripIsIdentity() {
        val re = DoubleArray(64) { sin(it * 0.3) * 100 }
        val im = DoubleArray(64)
        val orig = re.copyOf()
        Fft.transform(re, im)
        Fft.transform(re, im, inverse = true)
        for (i in orig.indices) assertEquals(orig[i], re[i], 1e-6)
    }

    @Test
    fun reducerCutsSteadyNoiseAndKeepsSpeechBursts() {
        val rnd = Random(7)
        val rate = 16_000
        // 3 s of hiss, with a loud 440 Hz "voice" burst from 2.0 s to 2.6 s.
        val input = ShortArray(rate * 3) { i ->
            val noise = rnd.nextDouble(-1.0, 1.0) * 600
            val voice = if (i in 32_000 until 41_600) sin(2 * PI * 440 * i / rate) * 6000 else 0.0
            (noise + voice).toInt().toShort()
        }
        val out = runReducer(input)
        assertEquals(input.size, out.size)
        val noiseBefore = rms(input, 16_000, 30_000)
        val noiseAfter = rms(out, 16_000, 30_000)
        assertTrue("noise $noiseBefore -> $noiseAfter", noiseAfter < noiseBefore * 0.45)
        val voiceBefore = rms(input, 34_000, 40_000)
        val voiceAfter = rms(out, 34_000, 40_000)
        assertTrue("voice $voiceBefore -> $voiceAfter", voiceAfter > voiceBefore * 0.8)
    }

    @Test
    fun fbankShapeAndMeanRemoval() {
        val audio = ShortArray(16_000) { (sin(it * 0.05) * 3000).toInt().toShort() }
        val feats = Fbank.compute(audio)
        assertEquals(98, feats.size)
        assertEquals(80, feats[0].size)
        var mean = 0.0
        for (f in feats) mean += f[10]
        assertEquals(0.0, mean / feats.size, 1e-3)
    }

    @Test
    fun voicePrintMath() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0.9f, 0.1f, 0f)
        assertTrue(VoicePrint.cosine(a, b) > 0.98f)
        assertEquals(0f, VoicePrint.cosine(a, floatArrayOf(0f, 1f, 0f)), 1e-6f)
        val v = FloatArray(192) { it / 192f }
        val back = VoicePrint.decode(VoicePrint.encode(v))!!
        assertEquals(v.size, back.size)
        assertTrue(VoicePrint.cosine(v, back) > 0.9999f)
        assertTrue(VoicePrint.threshold(0) < VoicePrint.threshold(1) && VoicePrint.threshold(1) < VoicePrint.threshold(2))
    }

    private fun tone(amp: Double, freq: Double = 300.0) = ShortArray(320) { (sin(2 * PI * freq * it / 16_000) * amp).toInt().toShort() }

    @Test
    fun gateReleasesTheUserAndMutesOthers() {
        // "User" is the 300 Hz voice, anyone else is 900 Hz; the fake similarity tells them apart.
        val gate = SpeakerGate(threshold = { 0.5f }, similarity = { audio ->
            var cross = 0
            for (i in 1 until audio.size) if ((audio[i - 1] < 0) != (audio[i] < 0)) cross++
            if (cross.toDouble() / audio.size < 0.06) 0.9f else 0.1f
        })
        val quiet = ShortArray(320)
        var passed = 0
        var voicedOut = 0
        repeat(20) { gate.push(quiet) { passed++ } }
        assertEquals(20, passed)

        // TV voice for 1.5 s: everything muted.
        repeat(75) { gate.push(tone(5000.0, 900.0)) { out -> if (rms(out) > 100) voicedOut++ } }
        assertEquals(0, voicedOut)
        assertEquals(SpeakerGate.State.Other, gate.state)

        // Silence closes the turn, then the user speaks: held audio is released.
        repeat(45) { gate.push(quiet) {} }
        assertEquals(SpeakerGate.State.Quiet, gate.state)
        repeat(60) { gate.push(tone(5000.0)) { out -> if (rms(out) > 100) voicedOut++ } }
        assertEquals(SpeakerGate.State.User, gate.state)
        assertTrue("user frames out: $voicedOut", voicedOut >= 55)
    }

    @Test
    fun liveSetupCalmAddsActivityDetection() {
        val calm = JSONObject(LiveProtocol.setup("m", "Leda", "s", org.json.JSONArray(), calm = true))
            .getJSONObject("setup").getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertEquals("START_SENSITIVITY_LOW", calm.getString("startOfSpeechSensitivity"))
        val normal = JSONObject(LiveProtocol.setup("m", "Leda", "s", org.json.JSONArray())).getJSONObject("setup")
        assertTrue(!normal.has("realtimeInputConfig"))
    }
}
