package app.dzz.copilot.voice

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The glasses microphone, as the original DZZ app reads it: command 0x59 frames carry a raw
 * Opus stream (16 kHz, mono, no Ogg/Opus header) cut into fixed 40-byte packets (20 ms each).
 */
object GlassesAudio {
    const val SAMPLE_RATE = 16_000
    const val OPUS_PACKET = 40
    const val FRAME = 320

    /** Joins 0x59 payloads and hands out whole Opus packets; a partial tail waits for the next payload. */
    class OpusPackets(private val size: Int = OPUS_PACKET) {
        private var tail = ByteArray(0)

        fun push(payload: ByteArray): List<ByteArray> {
            val data = if (tail.isEmpty()) payload else tail + payload
            val out = ArrayList<ByteArray>(data.size / size)
            var at = 0
            while (data.size - at >= size) {
                out += data.copyOfRange(at, at + size)
                at += size
            }
            tail = data.copyOfRange(at, data.size)
            return out
        }

        fun reset() {
            tail = ByteArray(0)
        }
    }

    /** Re-cuts decoded PCM into 20 ms frames at 16 kHz, decimating 48/32 kHz decoder output. */
    class Framer(private val frame: Int = FRAME) {
        private val buffer = ShortArray(frame)
        private var filled = 0

        fun push(pcm: ShortArray, inputRate: Int, channels: Int, emit: (ShortArray) -> Unit) {
            val step = (inputRate / SAMPLE_RATE).coerceAtLeast(1) * channels.coerceAtLeast(1)
            var i = 0
            while (i + step - 1 < pcm.size) {
                var sum = 0
                for (k in 0 until step) sum += pcm[i + k]
                buffer[filled++] = (sum / step).toShort()
                if (filled == frame) {
                    emit(buffer.copyOf())
                    filled = 0
                }
                i += step
            }
        }

        fun reset() {
            filled = 0
        }
    }

    /** OpusHead for MediaCodec's csd-0: version 1, mono, 312 samples of pre-skip, 16 kHz. */
    fun opusHead(channels: Int = 1, rate: Int = SAMPLE_RATE, preSkip: Int = 312): ByteArray {
        val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OpusHead".toByteArray(Charsets.US_ASCII))
        b.put(1)
        b.put(channels.toByte())
        b.putShort(preSkip.toShort())
        b.putInt(rate)
        b.putShort(0)
        b.put(0)
        return b.array()
    }

    private fun longLe(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    /** Android's own Opus decoder (API 29+). Not thread safe: feed it from one thread. */
    class Decoder : AutoCloseable {
        private val codec: MediaCodec
        private val info = MediaCodec.BufferInfo()
        private var outRate = SAMPLE_RATE
        private var outChannels = 1
        private var pts = 0L

        init {
            require(Build.VERSION.SDK_INT >= 29) { "Opus precisa de Android 10+" }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(longLe(0L)))
                setByteBuffer("csd-2", ByteBuffer.wrap(longLe(80_000_000L)))
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(format, null, null, 0)
            codec.start()
        }

        /** Decodes one packet; [onPcm] gets interleaved samples plus the decoder's rate and channels. */
        fun decode(packet: ByteArray, onPcm: (ShortArray, Int, Int) -> Unit) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val input = codec.getInputBuffer(index) ?: return
                input.clear()
                input.put(packet)
                codec.queueInputBuffer(index, 0, packet.size, pts, 0)
                pts += 20_000
            }
            drain(onPcm)
        }

        private fun drain(onPcm: (ShortArray, Int, Int) -> Unit) {
            while (true) {
                val out = codec.dequeueOutputBuffer(info, 0)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        Log.i("GlassesAudio", "Opus decoder out ${outRate}Hz x$outChannels")
                    }
                    out >= 0 -> {
                        val buf = codec.getOutputBuffer(out)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            onPcm(shorts, outRate, outChannels)
                        }
                        codec.releaseOutputBuffer(out, false)
                    }
                    else -> return
                }
            }
        }

        override fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}
