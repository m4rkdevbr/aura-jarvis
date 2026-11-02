package app.dzz.copilot.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import app.dzz.copilot.data.PreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Speaker-embedding model (WeSpeaker CAM++, about 29 MB) run with ONNX Runtime on the phone.
 * It is downloaded once, the first time the user records a voice print; nothing else leaves
 * the phone and the voice print stays in the app's encrypted settings.
 */
class SpeakerModel(context: Context) {
    private val file = File(context.filesDir, "models/speaker_campplus.onnx")
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    val downloaded: Boolean get() = file.length() > MIN_BYTES
    val ready: Boolean get() = session != null

    /** Downloads (if needed) and opens the model. [progress] gets 0..100. */
    fun ensure(progress: (Int) -> Unit = {}): String? {
        if (session != null) return null
        if (!downloaded) {
            val error = download(progress)
            if (error != null) return error
        }
        return runCatching {
            val e = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            session = e.createSession(file.absolutePath, options)
            env = e
            null
        }.getOrElse { "não abri o modelo de voz: ${it.message}" }
    }

    private fun download(progress: (Int) -> Unit): String? = runCatching {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        http.newCall(Request.Builder().url(URL).build()).execute().use { response ->
            if (!response.isSuccessful) return "download do modelo de voz falhou (HTTP ${response.code})"
            val body = response.body ?: return "download vazio"
            val total = body.contentLength().takeIf { it > 0 } ?: EXPECTED_BYTES
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        }
        if (part.length() < MIN_BYTES) {
            part.delete()
            return "o modelo de voz veio incompleto"
        }
        part.renameTo(file)
        null
    }.getOrElse { "sem internet para baixar o modelo de voz (${it.message})" }

    /** Unit-length embedding of 16 kHz audio, or null when too short or the model is not open. */
    fun embed(samples: ShortArray, count: Int = samples.size): FloatArray? {
        val s = session ?: return null
        val e = env ?: return null
        val feats = Fbank.compute(samples, count)
        if (feats.size < 30) return null
        return runCatching {
            OnnxTensor.createTensor(e, arrayOf(feats)).use { input ->
                s.run(mapOf(s.inputNames.first() to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = result[0].value as Array<FloatArray>
                    VoicePrint.normalize(out[0])
                }
            }
        }.onFailure { Log.w(TAG, "embed: ${it.message}") }.getOrNull()
    }

    companion object {
        private const val TAG = "SpeakerModel"
        const val URL =
            "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/wespeaker_en_voxceleb_CAM%2B%2B.onnx"
        const val EXPECTED_BYTES = 29_292_684L
        const val MIN_BYTES = 20_000_000L
    }
}

data class FocusStatus(
    val enrolled: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val lastScore: Float? = null,
)

/**
 * Everything that helps Aura hear only the user: noise reduction on every frame (phone or glasses
 * microphone), the voice-print gate during live conversations and the voice check on the
 * segments that go to Whisper. Also records the voice print.
 */
class VoiceFocus(context: Context, private val store: PreferencesStore) {
    val model = SpeakerModel(context)
    private val reducer = NoiseReducer()
    @Volatile private var print: FloatArray? = VoicePrint.decode(store.voicePrint.value)
    @Volatile private var enrollSink: MutableList<ShortArray>? = null
    private val _status = MutableStateFlow(FocusStatus(enrolled = print != null))
    val status: StateFlow<FocusStatus> = _status.asStateFlow()

    val gate = SpeakerGate(
        threshold = { VoicePrint.threshold(store.voiceStrictness.value) },
        similarity = { audio -> score(audio) },
    )

    /** True when the gate should filter voices (print recorded, model open, option on). */
    fun onlyMe(): Boolean = store.onlyMyVoice.value && print != null && model.ready

    /** Opens the model in the background when a print exists (call from an IO thread). */
    fun warmUp() {
        if (print != null && model.downloaded && !model.ready) model.ensure()
    }

    fun resetStream() {
        synchronized(reducer) { reducer.reset() }
        gate.reset()
    }

    /** Noise reduction for one frame; also feeds a voice-print recording in progress. */
    fun clean(frame: ShortArray, n: Int): ShortArray {
        val out = if (store.noiseClean.value) synchronized(reducer) { reducer.process(frame, n) } else frame.copyOf(n)
        enrollSink?.let { sink -> synchronized(sink) { sink.add(out) } }
        return out
    }

    /**
     * Live conversation path. Every frame reaches Gemini: muting frames by voice print cut the
     * user's own words when the microphone changed (glasses, phone, headset) and the conversation
     * died of "silence". The voice print only decides interruptions while Aura talks
     * ([isUserVoice]) and the idle Whisper path ([isUser]).
     */
    fun live(frame: ShortArray, emit: (ShortArray) -> Unit) {
        emit(frame)
    }

    /** Is this audio the user's voice? True when the filter is off or unsure. */
    fun isUserVoice(samples: ShortArray): Boolean {
        if (!onlyMe()) return true
        val s = score(samples) ?: return true
        return s >= VoicePrint.threshold(store.voiceStrictness.value)
    }

    /** Whisper path: is this finished segment (16-bit little-endian PCM) the user's voice? */
    fun isUser(pcm: ByteArray): Boolean {
        if (!onlyMe()) return true
        val samples = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val s = score(samples) ?: return true
        _status.value = _status.value.copy(lastScore = s)
        // Lenient: only clearly different voices are dropped; the same person on another
        // microphone scores lower than at enrollment.
        val limit = VoicePrint.threshold(store.voiceStrictness.value) * LENIENT
        val ok = s >= limit
        Log.i(TAG, "voz: semelhança ${"%.2f".format(s)} (mínimo ${"%.2f".format(limit)}) -> ${if (ok) "usuário" else "outra pessoa"}")
        return ok
    }

    private fun score(samples: ShortArray): Float? {
        val p = print ?: return null
        val e = model.embed(samples) ?: return null
        return VoicePrint.cosine(p, e)
    }

    /**
     * Records [seconds] of the user's voice from whatever microphone Aura is using and returns its
     * embedding. The microphone must be on (Aura switched on).
     */
    suspend fun recordSample(seconds: Int = 6): Result<FloatArray> = withContext(Dispatchers.IO) {
        _status.value = _status.value.copy(busy = true, message = "Preparando o modelo de voz…")
        val error = model.ensure { pct -> _status.value = _status.value.copy(message = "Baixando o modelo de voz: $pct%") }
        if (error != null) {
            _status.value = _status.value.copy(busy = false, message = error)
            return@withContext Result.failure(IllegalStateException(error))
        }
        val sink = ArrayList<ShortArray>()
        enrollSink = sink
        _status.value = _status.value.copy(message = "Gravando… fale normalmente")
        try {
            delay(1_000)
            if (synchronized(sink) { sink.isEmpty() }) {
                val msg = "O microfone não está ativo. Ligue a Aura e tente de novo."
                _status.value = _status.value.copy(busy = false, message = msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }
            delay(seconds * 1_000L - 1_000L)
        } finally {
            enrollSink = null
        }
        val frames = synchronized(sink) { sink.toList() }
        val voiced = frames.filter { VoicePrint.rms(it) > 300 }
        if (voiced.size < 75) {
            val msg = "Ouvi pouca voz. Fale mais perto do microfone e repita."
            _status.value = _status.value.copy(busy = false, message = msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        val joined = ShortArray(voiced.sumOf { it.size })
        var at = 0
        voiced.forEach { System.arraycopy(it, 0, joined, at, it.size); at += it.size }
        val emb = model.embed(joined)
        if (emb == null) {
            _status.value = _status.value.copy(busy = false, message = "Não consegui analisar a voz.")
            return@withContext Result.failure(IllegalStateException("embed"))
        }
        _status.value = _status.value.copy(busy = false, message = "Trecho gravado.")
        Result.success(emb)
    }

    /** Saves the print from the recorded samples; returns how consistent they were (0..1). */
    fun saveEnrollment(samples: List<FloatArray>): Float {
        val avg = VoicePrint.average(samples)
        var min = 1f
        for (i in samples.indices) for (j in i + 1 until samples.size) {
            min = minOf(min, VoicePrint.cosine(samples[i], samples[j]))
        }
        print = avg
        store.setVoicePrint(VoicePrint.encode(avg))
        store.setOnlyMyVoice(true)
        gate.reset()
        _status.value = FocusStatus(enrolled = true, message = "Voz cadastrada.")
        return min
    }

    private companion object {
        const val TAG = "VoiceFocus"
        const val LENIENT = 0.7f
    }

    fun clearEnrollment() {
        print = null
        store.setVoicePrint("")
        store.setOnlyMyVoice(false)
        gate.reset()
        _status.value = FocusStatus(enrolled = false, message = "Voz apagada.")
    }
}
