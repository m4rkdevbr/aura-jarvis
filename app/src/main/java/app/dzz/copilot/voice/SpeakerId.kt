package app.dzz.copilot.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Kaldi-style log mel filterbank (25 ms window, 10 ms step, 80 bands, Hamming, pre-emphasis 0.97,
 * mean removed over time), the input the speaker-embedding models expect. Samples stay in the
 * 16-bit range, as in WeSpeaker.
 */
object Fbank {
    const val BINS = 80
    private const val RATE = 16_000
    private const val WIN = 400
    private const val STEP = 160
    private const val FFT = 512
    private val hamming = DoubleArray(WIN) { 0.54 - 0.46 * cos(2 * PI * it / (WIN - 1)) }
    private val mel: Array<DoubleArray> by lazy { melBanks() }

    private fun hz2mel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)

    private fun melBanks(): Array<DoubleArray> {
        val low = hz2mel(20.0)
        val high = hz2mel(RATE / 2.0)
        val delta = (high - low) / (BINS + 1)
        return Array(BINS) { b ->
            val left = low + b * delta
            val center = left + delta
            val right = center + delta
            DoubleArray(FFT / 2 + 1) { k ->
                val m = hz2mel(k * RATE.toDouble() / FFT)
                when {
                    m <= left || m >= right -> 0.0
                    m <= center -> (m - left) / (center - left)
                    else -> (right - m) / (right - center)
                }
            }
        }
    }

    /** Frames x 80 features, or an empty array when the audio is shorter than one window. */
    fun compute(samples: ShortArray, count: Int = samples.size): Array<FloatArray> {
        if (count < WIN) return emptyArray()
        val frames = 1 + (count - WIN) / STEP
        val out = Array(frames) { FloatArray(BINS) }
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        val frame = DoubleArray(WIN)
        for (f in 0 until frames) {
            val start = f * STEP
            var mean = 0.0
            for (i in 0 until WIN) { frame[i] = samples[start + i].toDouble(); mean += frame[i] }
            mean /= WIN
            for (i in 0 until WIN) frame[i] -= mean
            for (i in WIN - 1 downTo 1) frame[i] -= 0.97 * frame[i - 1]
            frame[0] -= 0.97 * frame[0]
            re.fill(0.0); im.fill(0.0)
            for (i in 0 until WIN) re[i] = frame[i] * hamming[i]
            Fft.transform(re, im)
            val row = out[f]
            for (b in 0 until BINS) {
                val bank = mel[b]
                var energy = 0.0
                for (k in 0..FFT / 2) {
                    val w = bank[k]
                    if (w != 0.0) energy += w * (re[k] * re[k] + im[k] * im[k])
                }
                row[b] = ln(max(energy, 1.1920929e-7)).toFloat()
            }
        }
        for (b in 0 until BINS) {
            var mean = 0.0
            for (f in 0 until frames) mean += out[f][b]
            mean /= frames
            for (f in 0 until frames) out[f][b] -= mean.toFloat()
        }
        return out
    }
}

/** Voice-print math: embeddings are compared by cosine similarity. */
object VoicePrint {
    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum).takeIf { it > 1e-9 } ?: return v.copyOf()
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = sqrt(na) * sqrt(nb)
        return if (d < 1e-9) 0f else (dot / d).toFloat()
    }

    /** The user's print: the mean of the normalized enrollment embeddings. */
    fun average(prints: List<FloatArray>): FloatArray {
        require(prints.isNotEmpty())
        val out = FloatArray(prints[0].size)
        prints.map(::normalize).forEach { p -> for (i in out.indices) out[i] += p[i] }
        return normalize(out)
    }

    fun encode(v: FloatArray): String = v.joinToString(",") { "%.5f".format(java.util.Locale.US, it) }

    fun decode(text: String): FloatArray? =
        text.split(',').mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size >= 64 }?.toFloatArray()

    /** Similarity needed to accept a voice, by strictness 0 (light), 1 (normal), 2 (strong). */
    fun threshold(strictness: Int): Float = when (strictness) {
        0 -> 0.32f
        2 -> 0.55f
        else -> 0.44f
    }

    /** Fraction of 20 ms frames in [frames] louder than [level]. */
    fun voicedShare(frames: List<ShortArray>, level: Double): Double {
        if (frames.isEmpty()) return 0.0
        return frames.count { rms(it, it.size) > level }.toDouble() / frames.size
    }

    fun rms(frame: ShortArray, n: Int = frame.size): Double {
        var sum = 0.0
        for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
        return sqrt(sum / n.coerceAtLeast(1))
    }
}

/**
 * Lets through only the enrolled user's voice during a live conversation. Frames are held while a
 * new voice starts; every half second the last 1.2 s are compared with the user's print. When it is
 * the user, the held audio is released and the rest flows; any other voice (TV, people nearby, Aura
 * herself leaking from the speaker) is replaced by silence. Quiet background passes untouched so
 * Gemini's own end-of-turn detection keeps working.
 *
 * [similarity] returns the cosine between the audio and the user's print (or null if unknown).
 */
class SpeakerGate(
    private val threshold: () -> Float,
    private val similarity: (ShortArray) -> Float?,
    private val voiceLevel: Double = 420.0,
) {
    enum class State { Quiet, Checking, User, Other }

    var state = State.Quiet
        private set
    var lastScore: Float? = null
        private set

    private val window = ArrayDeque<ShortArray>()
    private var sinceCheck = 0
    private var quietRun = 0
    private var misses = 0
    private var noise = 200.0

    fun reset() {
        window.clear(); state = State.Quiet; sinceCheck = 0; quietRun = 0; misses = 0; lastScore = null
    }

    /** Pushes one 20 ms frame; [emit] receives what should go to the model (maybe several frames). */
    fun push(frame: ShortArray, emit: (ShortArray) -> Unit) {
        window.addLast(frame)
        while (window.size > WINDOW) window.removeFirst()
        val level = VoicePrint.rms(frame)
        val loudEnough = max(voiceLevel, noise * 2.5)
        val voiced = level > loudEnough
        if (!voiced) noise = 0.97 * noise + 0.03 * level
        if (voiced) quietRun = 0 else quietRun++
        sinceCheck++

        when (state) {
            State.Quiet -> {
                if (voiced) {
                    state = State.Checking
                    sinceCheck = 0
                    emit(ShortArray(frame.size))
                } else {
                    emit(frame)
                }
            }
            State.Checking -> {
                if (quietRun >= QUIET_END) {
                    state = State.Quiet
                    emit(frame)
                } else if (sinceCheck >= FIRST_CHECK && VoicePrint.voicedShare(window.toList(), loudEnough) >= 0.3) {
                    if (check()) {
                        state = State.User
                        window.forEach(emit)
                    } else {
                        state = State.Other
                        emit(ShortArray(frame.size))
                    }
                } else {
                    emit(ShortArray(frame.size))
                }
            }
            State.User -> {
                emit(frame)
                if (quietRun >= QUIET_END) {
                    state = State.Quiet
                } else if (sinceCheck >= RECHECK && VoicePrint.voicedShare(window.toList(), loudEnough) >= 0.4) {
                    if (check()) misses = 0 else if (++misses >= 2) {
                        state = State.Other
                        misses = 0
                    }
                }
            }
            State.Other -> {
                if (quietRun >= QUIET_END) {
                    state = State.Quiet
                    emit(frame)
                } else if (sinceCheck >= RECHECK && VoicePrint.voicedShare(window.toList(), loudEnough) >= 0.3 && check()) {
                    state = State.User
                    // Release the last stretch so the start of the user's sentence is not lost.
                    window.forEach(emit)
                } else {
                    emit(ShortArray(frame.size))
                }
            }
        }
    }

    private fun check(): Boolean {
        sinceCheck = 0
        val joined = ShortArray(window.sumOf { it.size })
        var at = 0
        window.forEach { System.arraycopy(it, 0, joined, at, it.size); at += it.size }
        val score = similarity(joined) ?: return true
        lastScore = score
        return score >= threshold()
    }

    companion object {
        const val WINDOW = 60 // 1.2 s of 20 ms frames
        const val FIRST_CHECK = 25 // decide after 0.5 s
        const val RECHECK = 25
        const val QUIET_END = 40 // 0.8 s of quiet closes a turn
    }
}
