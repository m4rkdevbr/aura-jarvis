package app.dzz.copilot.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place radix-2 complex FFT. Pure Kotlin, used by the noise reducer and the voice print. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n and (n - 1) == 0) { "size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * if (inverse) 1 else -1
            val wr = cos(angle)
            val wi = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}

/**
 * Streaming noise reducer for 16 kHz speech: short-time spectrum with 50% overlap, a noise
 * estimate that follows the quietest level of each band (fans, street, hiss, TV murmur), and a
 * Wiener gain with a floor so voices never sound robotic. It cleans steady noise; other people's
 * voices are the job of the voice print ([SpeakerGate]). About 32 ms of delay.
 */
class NoiseReducer(private val floorGain: Double = 0.18) {
    private val n = 512
    private val hop = n / 2
    private val bins = n / 2 + 1
    private val window = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
    private val inBuf = DoubleArray(n)
    private var inFill = n - hop
    private val overlap = DoubleArray(n)
    private val outQueue = ArrayDeque<Short>()
    private val smooth = DoubleArray(bins)
    private val noise = DoubleArray(bins) { -1.0 }
    private val lastGain = DoubleArray(bins) { 1.0 }
    private val lastPost = DoubleArray(bins) { 1.0 }
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private var frames = 0

    init {
        prime()
    }

    // One hop of silence up front keeps the output exactly as long as the input.
    private fun prime() = repeat(hop) { outQueue.addLast(0) }

    fun reset() {
        inBuf.fill(0.0); overlap.fill(0.0); outQueue.clear(); smooth.fill(0.0)
        noise.fill(-1.0); lastGain.fill(1.0); lastPost.fill(1.0); inFill = n - hop; frames = 0
        prime()
    }

    /** Takes [count] samples and returns the same number of cleaned samples (delayed by one hop). */
    fun process(input: ShortArray, count: Int = input.size): ShortArray {
        for (i in 0 until count) {
            inBuf[inFill++] = input[i].toDouble()
            if (inFill == n) {
                block()
                System.arraycopy(inBuf, hop, inBuf, 0, n - hop)
                inFill = n - hop
            }
        }
        val out = ShortArray(count)
        for (i in 0 until count) out[i] = outQueue.removeFirstOrNull() ?: 0
        return out
    }

    private fun block() {
        for (i in 0 until n) { re[i] = inBuf[i] * window[i]; im[i] = 0.0 }
        Fft.transform(re, im)
        frames++
        for (k in 0 until bins) {
            val power = re[k] * re[k] + im[k] * im[k]
            smooth[k] = if (frames == 1) power else 0.7 * smooth[k] + 0.3 * power
            noise[k] = when {
                noise[k] < 0 -> smooth[k]
                smooth[k] < noise[k] -> smooth[k]
                else -> noise[k] * 1.004 + 1e-3
            }
            val post = power / max(noise[k], 1e-6)
            val prior = 0.98 * lastGain[k] * lastGain[k] * lastPost[k] + 0.02 * max(post - 1.0, 0.0)
            val gain = min(1.0, max(floorGain, prior / (1.0 + prior)))
            lastGain[k] = gain
            lastPost[k] = post
            re[k] *= gain; im[k] *= gain
            if (k in 1 until n / 2) { re[n - k] = re[k]; im[n - k] = -im[k] }
        }
        Fft.transform(re, im, inverse = true)
        for (i in 0 until n) overlap[i] += re[i] * window[i]
        for (i in 0 until hop) {
            outQueue.addLast(overlap[i].coerceIn(-32768.0, 32767.0).toInt().toShort())
        }
        System.arraycopy(overlap, hop, overlap, 0, n - hop)
        for (i in n - hop until n) overlap[i] = 0.0
    }
}
