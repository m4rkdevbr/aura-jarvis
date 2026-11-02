package app.dzz.copilot.voice

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.dzz.copilot.data.PreferencesStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Continuous listen on the **phone built-in mic only**.
 *
 * Google [android.speech.SpeechRecognizer] opens Bluetooth HFP/SCO when glasses are
 * connected as a headset — that is the mic LED on/off loop. This engine never uses
 * SpeechRecognizer or SCO; capture is pinned to [AudioDeviceInfo.TYPE_BUILTIN_MIC]
 * and text comes from Groq Whisper.
 */
class PhoneMicEngine(
    context: Context,
    private val store: PreferencesStore,
    private val onText: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    private val transcriber = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "aura-whisper")
    }
    private var worker: Thread? = null
    private var headsetProxy: BluetoothHeadset? = null

    /** True while speech comes from the glasses microphone instead of the phone's. */
    private val external = AtomicBoolean(false)
    private val externalSegmenter = SpeechSegmenter(START_RMS, END_RMS, END_SILENCE_FRAMES, MIN_SPEECH_FRAMES, MAX_SPEECH_FRAMES, MIN_BYTES)

    val usingGlassesMic: Boolean get() = external.get()

    /** Offline recognizer: hears "Aura" on the phone so idle speech is not sent to the cloud. */
    var localAsr: LocalAsr? = null

    /** True while Aura waits for a command (after "Aura", in a dialogue or live translation). */
    var commandOpen: () -> Boolean = { true }

    /**
     * Set during a Gemini Live conversation: every raw 20 ms frame goes here instead of the VAD and
     * Whisper path, even while Aura talks (so the user can cut in).
     */
    @Volatile
    var liveTap: ((ShortArray, Int) -> Unit)? = null

    /** Noise reduction and "only my voice" filter applied to every frame, from either microphone. */
    var focus: VoiceFocus? = null

    /** The glasses are open as a Bluetooth headset (SCO) for the conversation; see [startCallMic]. */
    @Volatile var callMode = false
        private set
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Opens the glasses as a Bluetooth headset (HFP/SCO) for a whole conversation: one continuous
     * microphone near the mouth, no beeps, no switching. False when no headset link is available.
     */
    fun startCallMic(): Boolean {
        if (callMode) return true
        val manager = audio ?: return false
        if (Build.VERSION.SDK_INT >= 31) {
            val sco = runCatching { manager.availableCommunicationDevices }.getOrNull().orEmpty()
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            if (sco == null) {
                Log.i(TAG, "chamada Bluetooth: os óculos não aparecem como fone (HFP)")
                return false
            }
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            val ok = runCatching { manager.setCommunicationDevice(sco) }.getOrDefault(false)
            if (!ok) {
                manager.mode = AudioManager.MODE_NORMAL
                Log.w(TAG, "chamada Bluetooth: o Android recusou ${sco.productName}")
                return false
            }
            Log.i(TAG, "chamada Bluetooth aberta com ${sco.productName} (tipo ${sco.type})")
        } else {
            @Suppress("DEPRECATION")
            if (!manager.isBluetoothScoAvailableOffCall) return false
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            runCatching { manager.startBluetoothSco(); manager.isBluetoothScoOn = true }
            Log.i(TAG, "chamada Bluetooth aberta (SCO)")
        }
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        runCatching { manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxOf(1, (max * 0.9f).toInt()), 0) }
        callMode = true
        synchronized(externalSegmenter) { externalSegmenter.reset() }
        focus?.resetStream()
        restartWorker()
        return true
    }

    /** Closes the headset link opened by [startCallMic] and goes back to the phone microphone. */
    fun stopCallMic() {
        if (!callMode) return
        callMode = false
        val manager = audio
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= 31) runCatching { manager.clearCommunicationDevice() }
            @Suppress("DEPRECATION")
            if (manager.isBluetoothScoOn) runCatching { manager.stopBluetoothSco(); manager.isBluetoothScoOn = false }
            manager.mode = AudioManager.MODE_NORMAL
        }
        focus?.resetStream()
        Log.i(TAG, "chamada Bluetooth fechada")
        restartWorker()
    }

    private fun restartWorker() {
        generation.incrementAndGet()
        if (running.get() && (!external.get() || callMode || liveTap != null)) startWorker()
    }

    private fun cleaned(frame: ShortArray, n: Int): ShortArray = focus?.clean(frame, n) ?: frame.copyOf(n)

    private fun toLive(tap: (ShortArray, Int) -> Unit, frame: ShortArray) {
        val f = focus
        if (f == null) tap(frame, frame.size) else f.live(frame) { out -> tap(out, out.size) }
    }

    fun start() {
        if (running.getAndSet(true)) return
        stopHeadsetVoiceRecognition()
        bindHeadsetProxy()
        startWorker()
        Log.i(TAG, if (external.get()) "PhoneMic ligado (microfone dos óculos)" else "PhoneMic ligado (builtin only)")
    }

    private fun startWorker() {
        // The old loop exits on its next read once the source switches; let it finish first.
        worker?.takeIf { it.isAlive && it !== Thread.currentThread() }?.join(300)
        if (worker?.isAlive == true) return
        worker = Thread({ loop() }, "aura-phone-mic").also { it.start() }
    }

    /**
     * Switches the speech source. With the glasses on, the phone's AudioRecord is released and
     * [acceptExternal] feeds the same VAD and Whisper path.
     */
    fun useGlassesMic(on: Boolean) {
        if (external.getAndSet(on) == on) return
        synchronized(externalSegmenter) { externalSegmenter.reset() }
        // In a live conversation the phone recorder keeps running underneath (no restart gaps).
        if (liveTap == null) focus?.resetStream()
        if (!on && running.get()) startWorker()
        Log.i(TAG, if (on) "fonte: microfone dos óculos" else "fonte: microfone do celular")
    }

    /** One 20 ms frame (16 kHz mono) decoded from the glasses. */
    fun acceptExternal(frame: ShortArray, n: Int = frame.size) {
        if (!external.get() || callMode) return
        val clean = cleaned(frame, n)
        liveTap?.let { tap ->
            if (running.get()) toLive(tap, clean)
            synchronized(externalSegmenter) { externalSegmenter.reset() }
            return
        }
        val segment = synchronized(externalSegmenter) {
            if (!running.get() || paused.get()) {
                externalSegmenter.reset()
                null
            } else {
                externalSegmenter.push(clean, clean.size)
            }
        }
        if (segment != null) transcribeAsync(segment)
    }

    fun stop() {
        stopCallMic()
        running.set(false)
        paused.set(false)
        worker = null
        unbindHeadsetProxy()
        Log.i(TAG, "PhoneMic desligado")
    }

    fun pauseCapture() {
        paused.set(true)
    }

    fun resumeCapture() {
        paused.set(false)
        stopHeadsetVoiceRecognition()
    }

    private fun loop() {
        val mine = generation.get()
        val call = callMode
        val record = (if (call) openCallRecord() else openBuiltinRecord()) ?: run {
            Log.e(TAG, if (call) "AudioRecord da chamada falhou" else "AudioRecord builtin falhou")
            if (!call) running.set(false)
            return
        }
        try {
            record.startRecording()
            val frame = ShortArray(FRAME)
            val segmenter = SpeechSegmenter(START_RMS, END_RMS, END_SILENCE_FRAMES, MIN_SPEECH_FRAMES, MAX_SPEECH_FRAMES, MIN_BYTES)
            var loud = 0
            var frames = 0
            // Keep reading while the glasses send audio only during a live talk (no restart gaps).
            while (running.get() && generation.get() == mine && (call || !external.get() || liveTap != null)) {
                val tap = liveTap
                if (tap != null) {
                    val n = record.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    if (call && ++frames % 250 == 0) {
                        Log.i(TAG, "chamada: ${loud * 100 / 250}% com voz nos últimos 5 s")
                        loud = 0
                    }
                    if (call && rms(frame, n) > START_RMS) loud++
                    // The glasses BLE microphone, when it is sending, wins over the phone microphone.
                    if (call || !external.get()) toLive(tap, cleaned(frame, n))
                    segmenter.reset()
                    continue
                }
                if (!call && external.get()) break
                if (paused.get()) {
                    // Drain so the buffer does not overrun while TTS plays.
                    record.read(frame, 0, frame.size)
                    segmenter.reset()
                    continue
                }
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                val clean = cleaned(frame, n)
                segmenter.push(clean, clean.size)?.let { transcribeAsync(it) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "loop mic: ${error.message}")
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    /**
     * Segments queue up on one worker instead of being dropped while the previous
     * one is still at Whisper. Up to [MAX_PENDING] segments wait; older ones win.
     */
    private fun transcribeAsync(pcm: ByteArray) {
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet()
            Log.w(TAG, "fila de transcrição cheia, segmento descartado")
            return
        }
        val queuedAt = android.os.SystemClock.elapsedRealtime()
        transcriber.execute {
            busy.set(true)
            try {
                // A phrase that waited in the queue while a live talk started belongs to the past.
                if (liveTap != null || android.os.SystemClock.elapsedRealtime() - queuedAt > STALE_MS) {
                    Log.i(TAG, "segmento antigo descartado")
                    return@execute
                }
                stopHeadsetVoiceRecognition()
                if (focus?.isUser(pcm) == false) {
                    Log.i(TAG, "voz de outra pessoa ignorada")
                    return@execute
                }
                val text = transcribe(pcm)?.trim().orEmpty()
                if (text.isNotBlank() && running.get() && !paused.get() && liveTap == null) {
                    Log.i(TAG, "ouvi: $text")
                    main.post { onText(text) }
                }
            } catch (error: Exception) {
                Log.w(TAG, "whisper: ${error.message}")
            } finally {
                busy.set(pending.decrementAndGet() > 0)
            }
        }
    }

    /**
     * With the local model ready: idle speech without "Aura" is dropped on the phone; speech with
     * "Aura" or during a command goes to Whisper (more accurate), and the local text covers the
     * cases without cloud. Without the local model it is Whisper as before.
     */
    private fun transcribe(pcm: ByteArray): String? {
        val local = localAsr?.takeIf { store.localWake.value && it.ready }?.transcribe(pcm)
            ?: return whisper(pcm)
        val wake = LocalAsr.hasWake(local)
        if (!wake && !commandOpen()) return null
        val cloudOk = !store.onlyLocal.value && store.groqKey.value.isNotBlank()
        val cloud = if (cloudOk) runCatching { whisper(pcm) }.getOrNull() else null
        val text = cloud?.takeIf { it.isNotBlank() } ?: local.takeIf { it.isNotBlank() } ?: return null
        // Whisper sometimes drops a short "Aura" at the start; keep the wake the phone heard.
        return if (wake && !LocalAsr.hasWake(text)) "Aura, $text" else text
    }

    private fun whisper(pcm: ByteArray): String? {
        val key = store.groqKey.value.trim()
        if (key.isEmpty()) {
            Log.w(TAG, "Sem chave Groq para Whisper")
            return null
        }
        val wav = pcmToWav(pcm, SAMPLE_RATE)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "aura.wav", wav.toRequestBody(WAV))
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("language", "pt")
            .addFormDataPart("response_format", "json")
            .build()
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        return http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "Whisper HTTP ${response.code}: ${raw.take(160)}")
                return null
            }
            JSONObject(raw).optString("text").trim().ifBlank { null }
        }
    }

    @SuppressLint("MissingPermission")
    /** Recorder on the Bluetooth headset link (voice-call source, with the phone's echo canceller). */
    private fun openCallRecord(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(min.coerceAtLeast(FRAME * 4) * 2)
                .build()
        } catch (error: Exception) {
            Log.e(TAG, "AudioRecord chamada: ${error.message}")
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return null
        }
        val input = audio?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        if (input != null) runCatching { record.setPreferredDevice(input) }
        if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            runCatching { android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true }
        }
        Log.i(TAG, "microfone da chamada: ${input?.productName ?: "padrão"}")
        return record
    }

    private fun openBuiltinRecord(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return null
        val buffer = min.coerceAtLeast(FRAME * 4) * 2
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(buffer)
                .build()
        } catch (error: Exception) {
            Log.e(TAG, "AudioRecord build: ${error.message}")
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return null
        }
        pinBuiltinMic(record)
        if (store.noiseClean.value && android.media.audiofx.NoiseSuppressor.isAvailable()) {
            runCatching { android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)?.enabled = true }
        }
        audio?.mode = AudioManager.MODE_NORMAL
        return record
    }

    private fun pinBuiltinMic(record: AudioRecord) {
        if (Build.VERSION.SDK_INT < 23) return
        val manager = audio ?: return
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val builtin = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: inputs.firstOrNull {
                it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    it.type != AudioDeviceInfo.TYPE_BLE_HEADSET &&
                    it.type != AudioDeviceInfo.TYPE_TELEPHONY
            }
        if (builtin != null) {
            val ok = runCatching { record.setPreferredDevice(builtin) }.getOrDefault(false)
            Log.i(TAG, "preferred mic=${builtin.productName} type=${builtin.type} ok=$ok")
        } else {
            Log.w(TAG, "Nenhum mic builtin listado")
        }
    }

    @SuppressLint("MissingPermission")
    private fun bindHeadsetProxy() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        runCatching {
            adapter.getProfileProxy(
                appContext,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                        if (profile == BluetoothProfile.HEADSET) {
                            headsetProxy = proxy as? BluetoothHeadset
                            stopHeadsetVoiceRecognition()
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.HEADSET) headsetProxy = null
                    }
                },
                BluetoothProfile.HEADSET,
            )
        }
    }

    private fun unbindHeadsetProxy() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val proxy = headsetProxy ?: return
        runCatching { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) }
        headsetProxy = null
    }

    @SuppressLint("MissingPermission")
    private fun stopHeadsetVoiceRecognition() {
        if (callMode) return
        val hs = headsetProxy ?: return
        val devices = runCatching { hs.connectedDevices }.getOrNull().orEmpty()
        for (device in devices) {
            // Only when a voice link (SCO) is really open: poking the glasses on every phrase makes them beep.
            if (runCatching { hs.isAudioConnected(device) }.getOrDefault(false)) {
                runCatching {
                    hs.stopVoiceRecognition(device)
                    Log.i(TAG, "stopVoiceRecognition ${device.address}")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val current = runCatching { audio?.communicationDevice }.getOrNull()
            if (current != null && (
                    current.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        current.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    )
            ) {
                runCatching { audio?.clearCommunicationDevice() }
            }
        }
        @Suppress("DEPRECATION")
        if (audio?.isBluetoothScoOn == true) {
            runCatching {
                @Suppress("DEPRECATION")
                audio?.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audio?.isBluetoothScoOn = false
            }
        }
    }

    companion object {
        private const val TAG = "PhoneMic"
        const val SAMPLE_RATE = 16_000
        const val FRAME = 320 // 20ms
        const val START_RMS = 380.0
        const val END_RMS = 220.0
        const val END_SILENCE_FRAMES = 28 // ~560ms
        const val MIN_SPEECH_FRAMES = 18 // ~360ms
        const val MAX_SPEECH_FRAMES = 400 // ~8s
        const val MIN_BYTES = 16_000 // ~0.5s
        const val MAX_PENDING = 3
        const val STALE_MS = 9_000L
        val WAV = "audio/wav".toMediaType()

        fun rms(frame: ShortArray, n: Int): Double {
            var sum = 0.0
            for (i in 0 until n) {
                val v = frame[i].toDouble()
                sum += v * v
            }
            return sqrt(sum / n.coerceAtLeast(1))
        }

        fun writePcm(out: ByteArrayOutputStream, frame: ShortArray, n: Int) {
            val bytes = ByteArray(n * 2)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) buf.putShort(frame[i])
            out.write(bytes)
        }

        fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val out = ByteArrayOutputStream(44 + pcm.size)
            val channels = 1
            val byteRate = sampleRate * channels * 2
            out.write("RIFF".toByteArray())
            writeInt(out, 36 + pcm.size)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            writeInt(out, 16)
            writeShort(out, 1)
            writeShort(out, channels)
            writeInt(out, sampleRate)
            writeInt(out, byteRate)
            writeShort(out, (channels * 2))
            writeShort(out, 16)
            out.write("data".toByteArray())
            writeInt(out, pcm.size)
            out.write(pcm)
            return out.toByteArray()
        }

        fun writeInt(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff)
            out.write((value shr 24) and 0xff)
        }

        fun writeShort(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
        }
    }
}
