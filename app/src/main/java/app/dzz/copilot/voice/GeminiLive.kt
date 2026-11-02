package app.dzz.copilot.voice

import android.os.SystemClock
import android.util.Log
import app.dzz.copilot.brain.Schema
import app.dzz.copilot.brain.SimpleTool
import app.dzz.copilot.brain.Tool
import app.dzz.copilot.copilot.CopilotEngine
import app.dzz.copilot.copilot.LivePlayer
import app.dzz.copilot.data.PreferencesStore
import app.dzz.copilot.glasses.GlassesClient
import app.dzz.copilot.glasses.LinkPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A natural voice conversation with Gemini Live: the microphone (phone or glasses) streams in,
 * Aura's voice streams out on A2DP, the user can cut in, and the brain's tools (agenda,
 * camera, memory, Telegram...) are function calls on the same socket. Ends on goodbye, after
 * [IDLE_MS] of silence, or when the server closes. The caller falls back to the old pipeline
 * when it never opens.
 */
class GeminiLive(
    private val store: PreferencesStore,
    private val copilot: CopilotEngine,
    private val glasses: GlassesClient,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onUser(text: String)
        fun onReply(text: String)
        fun onTool(name: String)
        /** A tool finished ([ok] false on timeout or error), with how long it took. */
        fun onToolDone(name: String, ms: Long, ok: Boolean) {}
        /** The model finished a turn, even an audio-only one with no transcript. */
        fun onTurnDone() {}
        /** [opened] is false when no model accepted the session (use the old path). */
        fun onEnd(reason: String, opened: Boolean)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Optional "is this the user's voice?" check for interruptions (voice print). */
    @Volatile var voiceCheck: ((ShortArray) -> Boolean)? = null
    private val duplex = LiveDuplex(isUser = { audio -> voiceCheck?.invoke(audio) ?: true })
    private val pending = ArrayList<ByteArray>()
    private val userText = StringBuilder()
    private val modelText = StringBuilder()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var player: LivePlayer? = null
    @Volatile var active = false
        private set

    /** Aura's voice is coming out right now. */
    val playing: Boolean get() = player?.playing == true
    /** True when the conversation runs on the glasses' Bluetooth headset link (play there too). */
    @Volatile var callAudio: () -> Boolean = { false }
    /** Called when Aura starts or stops talking (the glasses are told, like the DZZ app does). */
    @Volatile var onSpeaking: (Boolean) -> Unit = {}
    @Volatile private var wasPlaying = false
    @Volatile private var ready = false
    @Volatile private var opened = false
    @Volatile private var lastActivity = 0L
    @Volatile private var endAfterTurn = false
    private val toolsRunning = java.util.concurrent.atomic.AtomicInteger(0)
    private var models: List<String> = emptyList()
    private var modelIndex = 0
    private var firstText: String? = null
    private var idleJob: Job? = null
    private var watchJob: Job? = null

    val available: Boolean
        get() = store.liveVoice.value && !store.onlyLocal.value && store.geminiKey.value.isNotBlank()

    fun start(first: String?) {
        if (active) {
            first?.takeIf { it.isNotBlank() }?.let { send(LiveProtocol.text(it)) }
            return
        }
        active = true
        ready = false
        opened = false
        endAfterTurn = false
        firstText = first?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(pending) { pending.clear() }
        synchronized(duplex) { duplex.reset() }
        userText.clear()
        modelText.clear()
        models = (listOf(store.liveModel.value.trim()) + FALLBACK_MODELS).filter { it.isNotBlank() }.distinct()
        modelIndex = 0
        nativeSearch = !store.onlyLocal.value
        connect()
    }

    /** Gemini's own Google Search inside the live session; turned off if a model refuses the setup. */
    @Volatile private var nativeSearch = true

    private fun connect() {
        val model = models.getOrNull(modelIndex) ?: return end("nenhum modelo ao vivo respondeu")
        val key = store.geminiKey.value.trim()
        val request = Request.Builder().url("${LiveProtocol.URL}?key=$key").build()
        Log.i(TAG, "abrindo Live com $model")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val search = nativeSearch
                val functions = tools().filter { !(search && it.name == "web_search") }
                webSocket.send(LiveProtocol.setup(model, store.voiceName.value, systemPrompt(), LiveProtocol.functions(functions), store.liveCalm.value, search))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onServer(webSocket, text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onServer(webSocket, bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closed(webSocket, "fechado ($code) $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed(webSocket, "falha: ${t.message ?: response?.code}")
            }
        })
    }

    private fun closed(webSocket: WebSocket, why: String) {
        if (webSocket !== socket || !active) return
        Log.w(TAG, "Live $why")
        if (!ready && nativeSearch) {
            Log.w(TAG, "Live recusou a busca nativa; tentando de novo sem ela")
            nativeSearch = false
            connect()
            return
        }
        if (!ready && modelIndex + 1 < models.size) {
            modelIndex++
            connect()
            return
        }
        end(if (ready) "a conexão caiu" else why)
    }

    private fun onServer(webSocket: WebSocket, message: String) {
        if (webSocket !== socket || !active) return
        for (event in LiveProtocol.parse(message)) {
            when (event) {
                LiveEvent.SetupDone -> onReady()
                is LiveEvent.Audio -> {
                    lastActivity = SystemClock.elapsedRealtime()
                    player?.enqueue(event.pcm)
                }
                is LiveEvent.UserText -> {
                    lastActivity = SystemClock.elapsedRealtime()
                    synchronized(userText) { userText.append(event.text) }
                }
                is LiveEvent.ModelText -> synchronized(modelText) { modelText.append(event.text) }
                LiveEvent.Interrupted -> {
                    Log.i(TAG, "servidor interrompeu a fala (ouviu voz no microfone)")
                    player?.flush()
                }
                LiveEvent.TurnDone -> onTurnDone()
                is LiveEvent.ToolCall -> runTool(event)
                is LiveEvent.ToolCancel -> Unit
                is LiveEvent.GoAway -> end("a sessão ao vivo chegou ao limite")
            }
        }
    }

    private fun onReady() {
        ready = true
        opened = true
        lastActivity = SystemClock.elapsedRealtime()
        player = copilot.livePlayer(callAudio()).also { it.start() }
        Log.i(TAG, "Live pronto (${models[modelIndex]}${if (callAudio()) ", chamada Bluetooth" else ""}${if (nativeSearch) ", busca Google nativa" else ""})")
        listener.onOpen()
        // With only "Aura" said, a short greeting tells the user she is listening.
        send(LiveProtocol.text(firstText ?: GREETING))
        firstText = null
        val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
        queued.forEach { send(LiveProtocol.audio(it)) }
        idleJob?.cancel()
        idleJob = scope.launch {
            while (isActive && active) {
                delay(1_000)
                val quiet = SystemClock.elapsedRealtime() - lastActivity
                val talking = player?.playing == true
                if (endAfterTurn && !talking && quiet > 1_200) end("tchau")
                else if (!talking && toolsRunning.get() == 0 && quiet > IDLE_MS) end("silêncio")
            }
        }
    }

    private fun onTurnDone() {
        val heard = synchronized(userText) { userText.toString().trim().also { userText.clear() } }
        val said = synchronized(modelText) { modelText.toString().trim().also { modelText.clear() } }
        Log.i(TAG, "turno: ouviu ${heard.length} letras, respondeu ${said.length} letras")
        if (heard.isNotEmpty()) listener.onUser(heard)
        if (said.isNotEmpty()) listener.onReply(said)
        listener.onTurnDone()
        lastActivity = SystemClock.elapsedRealtime()
    }

    /** One 20 ms frame (16 kHz mono) from whichever microphone is in use. */
    fun feed(frame: ShortArray, n: Int) {
        if (!active) return
        val talking = player?.playing == true
        if (talking != wasPlaying) {
            wasPlaying = talking
            runCatching { onSpeaking(talking) }
        }
        val out = synchronized(duplex) { duplex.push(frame, n, talking) }
        if (SpeechSegmenter.rms(frame, n) >= VOICE_RMS) lastActivity = SystemClock.elapsedRealtime()
        val pcm = when (out) {
            LiveDuplex.Out.Hold -> return
            is LiveDuplex.Out.Send -> out.pcm
            is LiveDuplex.Out.BargeIn -> {
                Log.i(TAG, "usuário interrompeu (limite ${duplex.threshold.toInt()})")
                player?.flush()
                out.pcm
            }
        }
        if (!ready) {
            synchronized(pending) {
                pending.add(pcm)
                while (pending.size > MAX_PENDING_CHUNKS) pending.removeAt(0)
            }
            return
        }
        send(LiveProtocol.audio(pcm))
    }

    private fun send(message: String) {
        socket?.takeIf { active }?.send(message)
    }

    private fun runTool(call: LiveEvent.ToolCall) {
        listener.onTool(call.name)
        toolsRunning.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        Log.i(TAG, "ferramenta ${call.name} começou")
        scope.launch {
            var ok = true
            val result = try {
                when (call.name) {
                    END_TOOL -> {
                        endAfterTurn = true
                        "Ok. Despeça-se em poucas palavras; a conversa termina depois."
                    }
                    WATCH_TOOL -> watch(call.args.optBoolean("on", true))
                    else -> {
                        val tool = copilot.brain.tools.find(call.name)
                        if (tool == null) {
                            "Ferramenta ${call.name} não existe."
                        } else {
                            withTimeoutOrNull(toolTimeout(call.name)) { tool.run(call.args) }
                                ?: run {
                                    ok = false
                                    "A ferramenta demorou demais e não respondeu. Avise o usuário em poucas palavras e ofereça tentar de novo."
                                }
                        }
                    }
                }
            } catch (error: Exception) {
                ok = false
                "Falhou: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                toolsRunning.decrementAndGet()
                lastActivity = SystemClock.elapsedRealtime()
            }
            val ms = SystemClock.elapsedRealtime() - started
            Log.i(TAG, "ferramenta ${call.name} terminou em $ms ms (${if (ok) "ok" else "falhou"})")
            runCatching { listener.onToolDone(call.name, ms, ok) }
            send(LiveProtocol.toolResponse(call.id, call.name, result.take(MAX_TOOL_RESULT)))
        }
    }

    /** "Modo olhar": glasses camera thumbnails go into the video stream every few seconds. */
    private fun watch(on: Boolean): String {
        watchJob?.cancel()
        watchJob = null
        if (!on) return "Modo olhar desligado."
        if (glasses.snapshot.value.phase != LinkPhase.Ready) return "Os óculos não estão conectados, não dá para olhar."
        watchJob = scope.launch {
            val until = SystemClock.elapsedRealtime() + WATCH_MS
            while (isActive && active && SystemClock.elapsedRealtime() < until) {
                glasses.captureThumbnail()?.let { send(LiveProtocol.image(it)) }
                delay(WATCH_EVERY_MS)
            }
        }
        return "Modo olhar ligado: imagens da câmera dos óculos chegam no vídeo a cada poucos segundos, por até dois minutos."
    }

    fun stop() = end("desligada")

    /** Shows Gemini a camera frame (the "look" tool during a live talk). */
    fun showImage(jpeg: ByteArray) {
        if (active && ready) send(LiveProtocol.image(jpeg))
    }

    @Synchronized
    private fun end(reason: String) {
        if (!active) return
        active = false
        ready = false
        idleJob?.cancel()
        watchJob?.cancel()
        socket?.close(1000, null)
        socket = null
        player?.stop()
        player = null
        if (wasPlaying) {
            wasPlaying = false
            runCatching { onSpeaking(false) }
        }
        synchronized(duplex) { duplex.reset() }
        Log.i(TAG, "Live terminou: $reason")
        listener.onEnd(reason, opened)
    }

    private fun systemPrompt(): String = copilot.brain.systemPrompt() + """


        Esta é uma conversa de voz AO VIVO pelos óculos. Fale como numa conversa de verdade: frases curtas,
        tom caloroso e natural, sem ler listas. O usuário pode te interromper; se isso acontecer, pare e escute.
        Para ver o que está na frente do usuário, use look (uma foto) ou $WATCH_TOOL (imagens contínuas).
        Quando o usuário se despedir, agradecer encerrando ou disser que é só isso, chame $END_TOOL.
        Antes de uma ferramenta que demora (look, web_search, analyze_problem, create_image), diga uma frase
        bem curta como "deixa eu ver" ou "um instante" e só então chame a ferramenta. Nunca fique em silêncio
        esperando. Responda direto do que você já sabe sempre que não precisar de ferramenta.
        Se uma ferramenta falhar, diga isso em poucas palavras e ofereça tentar de novo; não invente o resultado.
    """.trimIndent()

    private fun tools(): List<Tool> = copilot.brain.tools.all() + listOf(
        SimpleTool(END_TOOL, "Encerrar a conversa ao vivo quando o usuário se despedir ou terminar.") { "" },
        SimpleTool(
            WATCH_TOOL,
            "Ligar ou desligar o modo olhar: imagens da câmera dos óculos a cada poucos segundos no vídeo.",
            Schema.obj(
                "on" to JSONObject().put("type", "boolean").put("description", "true liga, false desliga"),
                required = listOf("on"),
            ),
        ) { "" },
    )

    companion object {
        private const val TAG = "AuraLive"
        const val DEFAULT_MODEL = "gemini-3.8-live"
        val FALLBACK_MODELS = listOf("gemini-live-2.5-flash-preview", "gemini-2.5-flash-native-audio-preview-09-2025")
        const val GREETING = "(O usuário acabou de te chamar dizendo Aura. Responda só com uma saudação curtíssima, como: Oi, pode falar.)"
        const val END_TOOL = "end_conversation"
        const val WATCH_TOOL = "watch_mode"
        const val IDLE_MS = 30_000L
        const val VOICE_RMS = 900.0
        const val TOOL_TIMEOUT_MS = 25_000L
        private val SLOW_TOOLS = setOf("look", "analyze_problem", "create_image", "web_search", "deep_think")

        fun toolTimeout(name: String): Long = if (name in SLOW_TOOLS) 40_000L else TOOL_TIMEOUT_MS
        const val MAX_TOOL_RESULT = 6_000
        const val MAX_PENDING_CHUNKS = 40
        const val WATCH_MS = 120_000L
        const val WATCH_EVERY_MS = 3_000L
    }
}
