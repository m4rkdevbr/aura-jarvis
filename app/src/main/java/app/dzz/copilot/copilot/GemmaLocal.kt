package app.dzz.copilot.copilot

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import app.dzz.copilot.brain.LocalTurns
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class GemmaStatus(
    val phase: String = "off",
    val detail: String = "",
    val progress: Float = 0f,
    val installed: Boolean = false,
    val loaded: Boolean = false,
    val backend: String = "",
    val speed: String = "",
)

/**
 * Gemma 4 E2B running inside the app with LiteRT-LM: no server process, GPU when the phone allows
 * it, CPU otherwise. Answers the brain's turns with function calling, so the only-local mode keeps
 * every tool. The model (~2.6 GB) is downloaded once; the engine loads on first use and unloads
 * after a while unused so it does not hold memory all day.
 */
class GemmaLocal(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "gemma").apply { mkdirs() }
    val modelFile = File(dir, MODEL_FILE)

    private val _status = MutableStateFlow(GemmaStatus(installed = isInstalled(), detail = if (isInstalled()) "Baixado. Carrega no primeiro uso." else "Não baixado."))
    val status: StateFlow<GemmaStatus> = _status.asStateFlow()

    /** The runtime is not thread safe: every engine call goes through this one thread. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gemma").apply { priority = Thread.MAX_PRIORITY } }
    @Volatile private var engine: Engine? = null
    @Volatile private var lastUse = 0L
    @Volatile private var failedAt = 0L
    @Volatile private var downloading = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Download in the app's own scope, so leaving the settings screen does not stop it. */
    fun startInstall() {
        scope.launch { install() }
    }

    /** Speed test in the background; the result lands in [status]. */
    fun startBenchmark(done: (String) -> Unit = {}) {
        scope.launch { done(benchmark()) }
    }

    fun isInstalled(): Boolean = modelFile.length() >= MODEL_BYTES * 99 / 100

    /** Ready to answer (or to load in a few seconds). */
    fun usable(): Boolean = isInstalled() && SystemClock.elapsedRealtime() - failedAt > RETRY_AFTER_FAIL_MS

    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            update { it.copy(phase = "ready", installed = true, progress = 1f, detail = "Já baixado.") }
            return@withContext true
        }
        if (downloading) return@withContext false
        downloading = true
        try {
            val part = File(dir, "$MODEL_FILE.part")
            val free = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (free < MODEL_BYTES - part.length() + 300_000_000L) {
                update { it.copy(phase = "error", detail = "Falta espaço: precisa de ${gb(MODEL_BYTES)} livres (há ${gb(free)}).") }
                return@withContext false
            }
            var attempt = 0
            while (part.length() < MODEL_BYTES) {
                val ok = runCatching { downloadChunk(part) }.onFailure { Log.w(TAG, "download parou", it) }.isSuccess
                if (!ok && ++attempt > 5) error("A conexão caiu várias vezes. Toque em baixar de novo para continuar de onde parou.")
                if (!ok) Thread.sleep(3_000L * attempt)
            }
            if (!part.renameTo(modelFile)) error("Não deu para gravar o modelo.")
            update { GemmaStatus(phase = "ready", installed = true, progress = 1f, detail = "Gemma 4 baixado. Carrega no primeiro uso.") }
            true
        } catch (error: Exception) {
            update { it.copy(phase = "error", detail = error.message ?: "Falha no download.") }
            false
        } finally {
            downloading = false
        }
    }

    /** Resumes where it stopped (HTTP Range), so a dropped Wi-Fi does not restart 2.6 GB. */
    private fun downloadChunk(part: File) {
        val from = part.length()
        val request = Request.Builder().url(MODEL_URL).header("Range", "bytes=$from-").build()
        http.newCall(request).execute().use { response ->
            if (response.code != 206 && response.code != 200) error("HTTP ${response.code}")
            val append = response.code == 206
            RandomAccessFile(part, "rw").use { out ->
                if (append) out.seek(from) else out.setLength(0)
                var done = if (append) from else 0L
                var lastShown = 0L
                val buffer = ByteArray(512 * 1024)
                response.body!!.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - lastShown > 8_000_000L) {
                            lastShown = done
                            val p = (done.toDouble() / MODEL_BYTES).toFloat().coerceIn(0f, 1f)
                            update { it.copy(phase = "download", progress = p, detail = "Baixando Gemma 4: ${gb(done)} de ${gb(MODEL_BYTES)}") }
                        }
                    }
                }
            }
        }
    }

    fun delete() {
        unload()
        modelFile.delete()
        File(dir, "$MODEL_FILE.part").delete()
        update { GemmaStatus(detail = "Modelo apagado.") }
    }

    /** One brain turn. Null when the model is missing, failed to load, or produced nothing. */
    fun turn(plan: LocalTurns.Plan, tools: List<Pair<String, String>>, maxChars: Int = MAX_REPLY_CHARS): Pair<String, List<LocalTurns.Call>>? =
        onEngine(TURN_TIMEOUT_MS) { engine ->
            val providers = tools.map { (_, json) ->
                tool(object : OpenApiTool {
                    override fun getToolDescriptionJsonString(): String = json
                    // Never runs: automatic tool calling is off and the agent runs the tools itself.
                    override fun execute(paramsJsonString: String): String = ""
                })
            }
            val config = ConversationConfig(
                systemInstruction = Contents.of(plan.system),
                initialMessages = plan.history.map(::message),
                tools = providers,
                samplerConfig = SamplerConfig(TOP_K, TOP_P, TEMPERATURE, 0),
                automaticToolCalling = false,
            )
            engine.createConversation(config).use { conversation ->
                val reply = conversation.sendMessage(message(plan.last))
                val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                val calls = reply.toolCalls.map { LocalTurns.Call(it.name, it.arguments) }
                text.take(maxChars) to calls
            }
        }

    /** Plain text answer for the old paths (chat, summaries) with no tools. */
    fun text(system: String, history: List<Pair<String, String>>, prompt: String): String? {
        val turns = history.map { (role, text) -> LocalTurns.Turn(if (role == "aura" || role == "assistant") LocalTurns.Role.MODEL else LocalTurns.Role.USER, text) }
        return turn(LocalTurns.Plan(system, turns, LocalTurns.Turn(LocalTurns.Role.USER, prompt)), emptyList())
            ?.first?.let(LocalTurns::clean)?.takeIf { it.isNotBlank() }
    }

    /** Loads the engine and times a short answer, for the settings screen. */
    fun benchmark(): String {
        val started = SystemClock.elapsedRealtime()
        val loadedBefore = engine != null
        val answer = text("Responda em português do Brasil, em uma frase.", emptyList(), "Diga em uma frase o que você consegue fazer offline.")
            ?: return _status.value.detail.ifBlank { "Não respondeu." }
        val ms = SystemClock.elapsedRealtime() - started
        val loadMs = loadMs.takeIf { !loadedBefore } ?: 0L
        val genMs = (ms - loadMs).coerceAtLeast(1)
        // Roughly 4 characters per token in Portuguese.
        val tps = answer.length / 4.0 / (genMs / 1000.0)
        val speed = "%.1f tokens/s no %s%s".format(tps, _status.value.backend, if (loadMs > 0) ", carregou em %.1f s".format(loadMs / 1000.0) else "")
        update { it.copy(speed = speed) }
        Log.i(TAG, "teste: $speed ($answer)")
        return "$speed. Resposta: $answer"
    }

    @Volatile private var loadMs = 0L

    private fun <T> onEngine(timeoutMs: Long, block: (Engine) -> T?): T? {
        if (!usable()) return null
        val future = worker.submit(Callable {
            val ready = engine ?: load() ?: return@Callable null
            lastUse = SystemClock.elapsedRealtime()
            try {
                block(ready)
            } finally {
                lastUse = SystemClock.elapsedRealtime()
                scheduleUnload()
            }
        })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            Log.w(TAG, "Gemma não respondeu", error)
            future.cancel(true)
            null
        }
    }

    /** GPU first (much faster), CPU when the GPU delegate is not available on this phone. */
    private fun load(): Engine? {
        val started = SystemClock.elapsedRealtime()
        for ((name, backend) in listOf("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })) {
            update { it.copy(phase = "loading", detail = "Carregando o Gemma 4 ($name)…") }
            val candidate = runCatching {
                Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = backend(), maxNumTokens = MAX_TOKENS, cacheDir = app.cacheDir.absolutePath))
                    .also { it.initialize() }
            }.onFailure { Log.w(TAG, "Gemma no $name falhou", it) }.getOrNull() ?: continue
            loadMs = SystemClock.elapsedRealtime() - started
            engine = candidate
            Log.i(TAG, "Gemma 4 carregado no $name em $loadMs ms")
            update { it.copy(phase = "ready", loaded = true, backend = name, detail = "Gemma 4 pronto ($name).") }
            return candidate
        }
        failedAt = SystemClock.elapsedRealtime()
        update { it.copy(phase = "error", loaded = false, detail = "O Gemma 4 não carregou neste celular. A Aura usa o LFM3 no lugar.") }
        return null
    }

    private val unloader = Executors.newSingleThreadScheduledExecutor()

    private fun scheduleUnload() {
        unloader.schedule({
            if (engine != null && SystemClock.elapsedRealtime() - lastUse >= IDLE_UNLOAD_MS) worker.execute { if (SystemClock.elapsedRealtime() - lastUse >= IDLE_UNLOAD_MS) unloadNow() }
        }, IDLE_UNLOAD_MS + 1_000, TimeUnit.MILLISECONDS)
    }

    fun unload() {
        worker.execute { unloadNow() }
    }

    private fun unloadNow() {
        val current = engine ?: return
        engine = null
        runCatching { current.close() }
        Log.i(TAG, "Gemma 4 descarregado para liberar memória")
        update { it.copy(loaded = false, phase = "ready", detail = "Baixado. Carrega no próximo uso.") }
    }

    private fun message(turn: LocalTurns.Turn): Message = when (turn.role) {
        LocalTurns.Role.USER -> Message.user(turn.text)
        LocalTurns.Role.MODEL -> if (turn.calls.isEmpty()) Message.model(turn.text) else Message.model(
            Contents.of(turn.text),
            turn.calls.map { call -> ToolCall(call.name, call.args.entries.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()) },
            emptyMap(),
        )
        LocalTurns.Role.TOOL -> Message.tool(Contents.of(Content.ToolResponse(turn.toolName, turn.text)))
    }

    private fun update(change: (GemmaStatus) -> GemmaStatus) {
        _status.value = change(_status.value).copy(installed = isInstalled())
    }

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_000_000_000.0)

    companion object {
        /** One engine per process (the model takes about 1 GB of memory once loaded). */
        @Volatile var shared: GemmaLocal? = null
            private set

        fun get(context: Context): GemmaLocal = shared ?: synchronized(this) {
            shared ?: GemmaLocal(context).also { shared = it }
        }

        private const val TAG = "AuraGemma"
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_FILE"
        const val MODEL_BYTES = 2_588_147_712L
        const val MAX_TOKENS = 4096
        const val MAX_REPLY_CHARS = 1_200
        const val TOP_K = 40
        const val TOP_P = 0.95
        const val TEMPERATURE = 0.4
        const val TURN_TIMEOUT_MS = 45_000L
        const val IDLE_UNLOAD_MS = 10L * 60_000
        const val RETRY_AFTER_FAIL_MS = 10L * 60_000
    }
}
