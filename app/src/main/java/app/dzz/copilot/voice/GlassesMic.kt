package app.dzz.copilot.voice

import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.dzz.copilot.data.PreferencesStore
import app.dzz.copilot.glasses.GlassesClient
import app.dzz.copilot.glasses.LinkPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Listens through the glasses microphone. The glasses open their microphone with a beep when
 * asked (speechStart) and close it by themselves after a short while, so asking again and again
 * makes them beep on and off. Rules:
 * - While waiting for "Aura", the phone microphone listens and the glasses stay quiet, unless the
 *   user chose "always" in Ajustes.
 * - During a conversation (live voice or a command), the glasses microphone is opened, and when it
 *   closes by itself it is reopened only when it is the user's turn (never while Aura talks).
 * - If the glasses start sending audio on their own (their button), that opens a conversation.
 * Between uploads the phone microphone covers, so the conversation is never deaf.
 */
class GlassesMic(
    private val store: PreferencesStore,
    private val glasses: GlassesClient,
    private val phoneMic: PhoneMicEngine,
) {
    private val audioThread = Executors.newSingleThreadExecutor { r -> Thread(r, "aura-glasses-mic") }
    private val scope = CoroutineScope(SupervisorJob() + audioThread.asCoroutineDispatcher())
    private var watch: Job? = null
    private var stream: Job? = null
    private var decoder: GlassesAudio.Decoder? = null
    private val packets = GlassesAudio.OpusPackets()
    private val framer = GlassesAudio.Framer()

    /** A conversation is open (live voice or waiting for a command). Set by AuraSession. */
    @Volatile var conversation: () -> Boolean = { false }
    /** Aura's voice is playing. Set by AuraSession. */
    @Volatile var auraSpeaking: () -> Boolean = { false }
    /** The glasses started sending audio by themselves (their AI button). */
    @Volatile var onGlassesWake: () -> Unit = {}

    @Volatile private var lastPacketAt = 0L
    @Volatile private var linked = false
    @Volatile private var streaming = false
    private var requested = false
    private var requestedAt = 0L
    private var uploadStart = 0L
    private var uploadPackets = 0
    private var quietSince = 0L
    private var misses = 0
    private var backoffUntil = 0L
    /** Uploads opened in this user turn; a silent one ends the glasses' part of the turn. */
    private var turnUploads = 0
    @Volatile private var lastVoiceAt = 0L
    private var keepTurn = true

    private val _status = MutableStateFlow("Desligado")
    val status: StateFlow<String> = _status.asStateFlow()

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 29

    fun start() {
        if (watch != null) return
        watch = scope.launch {
            launch {
                combine(store.glassesMic, glasses.snapshot) { on, snap -> on && snap.phase == LinkPhase.Ready }
                    .distinctUntilChanged()
                    .collect { wanted -> if (wanted) link() else unlink("óculos desligados ou opção desligada") }
            }
            while (isActive) {
                delay(CHECK_MS)
                runCatching { tick() }.onFailure { Log.w(TAG, "tick: ${it.message}") }
            }
        }
    }

    fun stop() {
        watch?.cancel()
        watch = null
        scope.launch { unlink("Aura parada") }
    }

    /** Glasses connected and option on: listen for packets, but do not open the microphone yet. */
    private fun link() {
        if (linked) return
        if (!supported) {
            _status.value = "Precisa de Android 10 ou mais novo"
            return
        }
        val codec = runCatching { GlassesAudio.Decoder() }.getOrElse {
            Log.w(TAG, "decoder: ${it.message}")
            _status.value = "Este celular não decodifica Opus"
            return
        }
        decoder = codec
        packets.reset()
        framer.reset()
        misses = 0
        requested = false
        streaming = false
        linked = true
        stream = scope.launch { glasses.micOpus.collect { payload -> feed(payload) } }
        _status.value = IDLE
        Log.i(TAG, "microfone dos óculos pronto (abre só na conversa)")
    }

    private fun feed(payload: ByteArray) {
        val codec = decoder ?: return
        val now = SystemClock.elapsedRealtime()
        lastPacketAt = now
        if (!streaming) {
            streaming = true
            uploadStart = now
            uploadPackets = 0
            misses = 0
            val ours = requested
            requested = false
            packets.reset()
            framer.reset()
            phoneMic.useGlassesMic(true)
            _status.value = LISTENING
            Log.i(TAG, if (ours) "óculos mandando áudio" else "óculos abriram o microfone sozinhos (botão)")
            if (!ours && !conversation()) onGlassesWake()
        }
        uploadPackets++
        for (packet in packets.push(payload)) {
            runCatching {
                codec.decode(packet) { pcm, rate, channels ->
                    framer.push(pcm, rate, channels) { frame ->
                        if (PhoneMicEngine.rms(frame, frame.size) > VOICE_RMS) lastVoiceAt = SystemClock.elapsedRealtime()
                        phoneMic.acceptExternal(frame)
                    }
                }
            }.onFailure { Log.w(TAG, "decode: ${it.message}") }
        }
    }

    private fun tick() {
        if (!linked) return
        val now = SystemClock.elapsedRealtime()
        if (streaming && now - lastPacketAt > GAP_MS) {
            streaming = false
            phoneMic.useGlassesMic(false)
            _status.value = IDLE
            // The glasses end every upload after ~5 s. Reopen (one more beep) only when the user was
            // still talking at the end; after a silent upload the phone microphone covers the turn.
            keepTurn = lastPacketAt - lastVoiceAt < VOICE_TAIL_MS
            Log.i(TAG, "óculos fecharam o microfone após ${(lastPacketAt - uploadStart) / 1000.0}s ($uploadPackets pacotes, ${if (keepTurn) "ainda com voz" else "em silêncio"})")
        }
        val talking = auraSpeaking()
        if (talking) {
            quietSince = 0L
            turnUploads = 0
            keepTurn = true
        } else if (quietSince == 0L) {
            quietSince = now
        }
        val want = store.glassesMicAlways.value || conversation()
        if (!want) {
            turnUploads = 0
            keepTurn = true
        }
        if (requested && !streaming && now - requestedAt > NO_AUDIO_MS) {
            requested = false
            if (++misses >= MAX_MISSES) {
                backoffUntil = now + BACKOFF_MS
                misses = 0
                _status.value = "Os óculos não mandaram áudio; usando o celular por 1 min"
                Log.i(TAG, "sem áudio dos óculos; pausa de 1 min")
            }
        }
        if (!want || streaming || requested || talking) return
        if (!store.glassesMicAlways.value && (turnUploads >= MAX_TURN_UPLOADS || !keepTurn)) return
        if (now < backoffUntil || now - requestedAt < MIN_REOPEN_MS) return
        // Wait a moment after Aura stops so her last words do not open a turn.
        if (quietSince == 0L || now - quietSince < AFTER_SPEECH_MS) return
        requested = true
        requestedAt = now
        turnUploads++
        glasses.startSpeech()
        Log.i(TAG, "abrindo o microfone dos óculos (vez do usuário)")
    }

    private fun unlink(reason: String) {
        if (!linked) return
        linked = false
        stream?.cancel()
        stream = null
        if (streaming || requested) runCatching { glasses.stopSpeech() }
        streaming = false
        requested = false
        decoder?.close()
        decoder = null
        phoneMic.useGlassesMic(false)
        _status.value = "Usando o microfone do celular"
        Log.i(TAG, "microfone dos óculos fechado: $reason")
    }

    companion object {
        private const val TAG = "GlassesMic"
        private const val LISTENING = "Ouvindo pelos óculos"
        private const val IDLE = "Óculos prontos; o celular espera o \"Aura\""
        private const val CHECK_MS = 200L
        private const val GAP_MS = 900L
        private const val NO_AUDIO_MS = 2_500L
        private const val MIN_REOPEN_MS = 1_500L
        private const val AFTER_SPEECH_MS = 400L
        private const val MAX_MISSES = 3
        private const val BACKOFF_MS = 60_000L
        private const val MAX_TURN_UPLOADS = 3
        private const val VOICE_TAIL_MS = 700L
        private const val VOICE_RMS = 500.0
    }
}
