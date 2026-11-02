package app.dzz.copilot.copilot

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays Gemini Live audio (24 kHz PCM) as it arrives, on the media route (A2DP to the glasses,
 * never SCO). [flush] drops what is queued when the user cuts in.
 */
class LivePlayer internal constructor(
    private val voice: AuraVoice,
    /** Play on the Bluetooth headset call link (the glasses as a headset) instead of A2DP. */
    private val call: Boolean = false,
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var framesWritten = 0L
    @Volatile private var generation = 0
    @Volatile private var lastWriteAt = 0L
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        if (!call) voice.prepareMediaOutput()
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val built = AudioTrack.Builder()
            .setAudioAttributes(if (call) voice.callAttributes() else voice.mediaAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min * 2, RATE / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (call) voice.preferCallOutput(built) else if (voice.glassesRoute) voice.preferA2dpOutput(built)
        built.play()
        track = built
        framesWritten = 0L
        worker = Thread({ loop() }, "aura-live-player").also { it.start() }
    }

    fun enqueue(pcm: ByteArray) {
        if (!running || pcm.isEmpty()) return
        queue.offer(voice.amplify(pcm, voice.boostGain()))
    }

    /** Stops what Aura is saying right now (barge-in or server "interrupted"). */
    fun flush() {
        generation++
        queue.clear()
        val t = track ?: return
        runCatching {
            t.pause()
            t.flush()
            t.play()
        }
        framesWritten = t.playbackHeadPosition.toLong().coerceAtLeast(0L)
    }

    /** True while queued or buffered audio is still being heard. */
    val playing: Boolean
        get() {
            if (queue.isNotEmpty()) return true
            val t = track ?: return false
            val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(framesWritten)
            // The A2DP delay after the head moves is covered by LiveDuplex's tail.
            return head < framesWritten
        }

    fun stop() {
        running = false
        queue.clear()
        worker?.interrupt()
        worker = null
        track?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
    }

    private fun loop() {
        while (running) {
            val chunk = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            val t = track ?: break
            val mine = generation
            var offset = 0
            while (offset < chunk.size && running && mine == generation) {
                val n = t.write(chunk, offset, chunk.size - offset)
                if (n <= 0) break
                offset += n
                framesWritten += n / 2
                lastWriteAt = SystemClock.elapsedRealtime()
            }
        }
    }

    private companion object {
        const val RATE = 24_000
    }
}
