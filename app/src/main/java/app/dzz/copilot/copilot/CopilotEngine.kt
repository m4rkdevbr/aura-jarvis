package app.dzz.copilot.copilot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.dzz.copilot.data.ChatLog
import app.dzz.copilot.data.PreferencesStore
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

data class LookResult(
    val speech: String,
    val labels: List<String>,
    val text: String,
    val bitmap: Bitmap?,
)

class CopilotEngine(
    context: Context,
    private val store: PreferencesStore,
    private val chat: ChatLog,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var meetingLoop = false
    private val cloud = CloudAnswers(store)
    private val images = ImageStudio(context, store)
    private val voice = AuraVoice(context, store)
    private var lastLook: LookResult? = null

    /** The 20 invisible reasoning engines (assets/frameworks.json). */
    val reasoning: app.dzz.copilot.brain.ReasoningEngine by lazy {
        val catalog = runCatching {
            appContext.assets.open("frameworks.json").bufferedReader().use { it.readText() }
        }.map { app.dzz.copilot.brain.FrameworkCatalog.parse(it) }
            .getOrElse { app.dzz.copilot.brain.FrameworkCatalog(emptyList()) }
        val brief = app.dzz.copilot.phone.PhoneBrief(appContext)
        app.dzz.copilot.brain.ReasoningEngine(
            catalog = catalog,
            llm = app.dzz.copilot.brain.Llm { system, user, maxTokens ->
                withContext(Dispatchers.IO) { cloud.deepText("$system\n\n$user", maxTokens)?.text }
            },
            store = analyses,
            context = { question ->
                listOf(runCatching { brief.agenda() }.getOrDefault(""), memory.promptBlock(question))
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            },
            onDone = { analysis ->
                memory.add(
                    "Análise de ${analysis.question.take(120)}: ${analysis.summary.take(240)}",
                    app.dzz.copilot.brain.MemoryStore.KIND_DECISION,
                )
            },
        )
    }

    /** Finished framework analyses (full JSON for the phone panel; the voice gets the summary). */
    val analyses = app.dzz.copilot.brain.AnalysisStore(File(appContext.filesDir, "analyses"))

    /** Long-term memory on this phone: facts the user kept and finished analyses. */
    val memory = app.dzz.copilot.brain.MemoryStore(File(appContext.filesDir, "memory.json"))

    /** The brain's memory: profile.md (context file) + the same facts as [memory]. */
    val mind = app.dzz.copilot.brain.Memory(File(appContext.filesDir, "brain"), memory)

    private val skillCatalog: app.dzz.copilot.brain.SkillCatalog by lazy {
        val names = runCatching { appContext.assets.list("skills") }.getOrNull().orEmpty().sorted()
        app.dzz.copilot.brain.SkillCatalog.parse(
            names.mapNotNull { name ->
                runCatching { appContext.assets.open("skills/$name").bufferedReader().use { it.readText() } }.getOrNull()
            },
        )
    }

    /** Reads a finished conversation and keeps what is worth remembering. Returns how many new items. */
    suspend fun learn(transcript: String): Int = withContext(Dispatchers.IO) {
        val known = mind.recent(40).map { it.text }
        val reply = cloud.text(app.dzz.copilot.brain.Learner.prompt(transcript, known)) ?: return@withContext 0
        mind.learn(app.dzz.copilot.brain.Learner.parse(reply.text))
    }

    /** Fast routines with trigger phrases (resumo do dia, viagem...), used without the agent. */
    fun skillFor(phrase: String): app.dzz.copilot.brain.AuraSkill? = skillCatalog.match(phrase)

    /** Runs one routine, with only the data it declares. Null when no model answered. */
    suspend fun runSkill(
        skill: app.dzz.copilot.brain.AuraSkill,
        request: String,
        data: Map<String, String>,
    ): CloudReply? = withContext(Dispatchers.IO) {
        cloud.text(app.dzz.copilot.brain.SkillCatalog.prompt(AuraMind.SPOKEN, skill, request, data))
    }

    /** Aura Skills: bundled in assets/skills plus the user's own in files/skills. */
    val userSkillsDir = File(appContext.filesDir, "skills").apply { mkdirs() }
    val skills = app.dzz.copilot.brain.SkillLibrary {
        val bundled = runCatching {
            appContext.assets.list("skills").orEmpty().filter { it.endsWith(".md") }.map { name ->
                name to appContext.assets.open("skills/$name").bufferedReader().use { it.readText() }
            }
        }.getOrDefault(emptyList())
        app.dzz.copilot.brain.SkillLibrary.folder(userSkillsDir) + bundled
    }

    /** What is happening right now (glasses link, battery, mode); set by AuraSession. */
    @Volatile
    var liveContext: () -> String = { "" }

    private val cloudModel = app.dzz.copilot.brain.OpenAiChatModel(providers = { brainProviders(cloud = true) })
    private val lfmModel = app.dzz.copilot.brain.OpenAiChatModel(providers = { brainProviders(cloud = false) })
    private val gemmaModel = GemmaChatModel { GemmaLocal.shared?.takeIf { store.localLfmEnabled.value && it.usable() } }
    @Volatile private var lastBrain = ""

    /** Cloud first (fast, smart); then Gemma 4 inside the app; the LFM3 server last. */
    private val chatModel = object : app.dzz.copilot.brain.ChatModel {
        override suspend fun turn(messages: org.json.JSONArray, tools: org.json.JSONArray): app.dzz.copilot.brain.ModelTurn? {
            cloudModel.turn(messages, tools)?.let { lastBrain = cloudModel.lastProvider; return it }
            gemmaModel.turn(messages, tools)?.let { lastBrain = "Gemma 4 local"; return it }
            lfmModel.turn(messages, tools)?.let { lastBrain = lfmModel.lastProvider; return it }
            return null
        }
    }

    /** The single brain with tools. Phone and glasses tools are registered by AuraSession. */
    val brain: app.dzz.copilot.brain.Brain by lazy {
        app.dzz.copilot.brain.Brain(
            model = chatModel,
            memory = mind,
            skills = skills,
            reasoning = reasoning,
            liveContext = { runCatching { liveContext() }.getOrDefault("") },
        )
    }

    /** Cloud providers, or the on-device LFM3 server ([cloud] false). */
    private fun brainProviders(cloud: Boolean): List<app.dzz.copilot.brain.Provider> = buildList {
        val local = store.localLfmUrl.value.trim()
        val localModel = store.localLfmModel.value.trim().ifBlank { "lfm3-omni" }
        if (cloud && !store.onlyLocal.value) {
            store.geminiKey.value.trim().takeIf { it.isNotEmpty() }?.let {
                add(app.dzz.copilot.brain.OpenAiChatModel.gemini(it))
            }
            store.groqKey.value.trim().takeIf { it.isNotEmpty() }?.let {
                add(app.dzz.copilot.brain.OpenAiChatModel.groq(it))
            }
            store.openRouterKey.value.trim().takeIf { it.isNotEmpty() }?.let {
                add(app.dzz.copilot.brain.OpenAiChatModel.openRouter(it, "openrouter/auto"))
            }
        }
        if (!cloud && store.localLfmEnabled.value && local.isNotEmpty()) {
            add(app.dzz.copilot.brain.OpenAiChatModel.local(local, localModel))
        }
    }

    /** One brain for every input. Null when no model answered (caller falls back). */
    suspend fun converse(text: String, onTool: (String) -> Unit = {}): String? =
        runCatching { brain.agent.run(text, onTool)?.text }.getOrNull()?.takeIf { it.isNotBlank() }

    fun brainProvider(): String = lastBrain

    /**
     * Runs a framework when the phrase is a dilemma, problem or decision.
     * Returns the spoken reply, or null when it is plain conversation.
     */
    suspend fun think(phrase: String): String? {
        return when (val outcome = reasoning.handle(phrase)) {
            is app.dzz.copilot.brain.Reasoning.Done -> outcome.analysis.spoken
            is app.dzz.copilot.brain.Reasoning.NeedsContext -> outcome.spoken
            else -> null
        }
    }

    /** Streaming speaker for Gemini Live (same A2DP route and volume as the spoken replies). */
    fun livePlayer(call: Boolean = false): LivePlayer = LivePlayer(voice, call)

    fun setGlassesAudioRoute(active: Boolean) {
        voice.glassesRoute = active
    }

    private val _meetingLines = MutableStateFlow<List<String>>(emptyList())
    val meetingLines: StateFlow<List<String>> = _meetingLines.asStateFlow()
    private val _meetingOn = MutableStateFlow(false)
    val meetingOn: StateFlow<Boolean> = _meetingOn.asStateFlow()
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    var onRecognizerBusy: ((Boolean) -> Unit)? = null

    /**
     * @param pauseListener when true (default for Copilot UI / meeting), pauses Aura STT
     * so playback is not heard as a command. Aura's own replies pass false — they already
     * hold the mic via `speaking`.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null, pauseListener: Boolean = true) {
        if (pauseListener) onRecognizerBusy?.invoke(true)
        voice.speak(text) {
            if (pauseListener) onRecognizerBusy?.invoke(false)
            onDone?.invoke()
        }
    }

    fun speakQuick(text: String, onDone: (() -> Unit)? = null, pauseListener: Boolean = true) {
        if (pauseListener) onRecognizerBusy?.invoke(true)
        voice.speakQuick(text) {
            if (pauseListener) onRecognizerBusy?.invoke(false)
            onDone?.invoke()
        }
    }

    suspend fun ask(question: String): CloudReply? = withContext(Dispatchers.IO) {
        cloud.converse(chat.recent().ifEmpty { listOf("voce" to question) }, memory.promptBlock(question))
    }

    suspend fun imagine(prompt: String): String = withContext(Dispatchers.IO) {
        images.create(prompt)
    }

    suspend fun describeFile(file: File): LookResult = withContext(Dispatchers.IO) {
        val image = runCatching { InputImage.fromFilePath(appContext, Uri.fromFile(file)) }.getOrNull()
        val bitmap = decodeFile(file)
        if (image == null || bitmap == null) {
            return@withContext LookResult("Não consegui ler essa imagem.", emptyList(), "", bitmap)
        }
        return@withContext analyze(image, bitmap).also { lastLook = it }
    }

    suspend fun describeBytes(bytes: ByteArray): LookResult = withContext(Dispatchers.IO) {
        val bitmap = decodeBytes(bytes)
            ?: return@withContext LookResult("A miniatura dos óculos não chegou.", emptyList(), "", null)
        val image = InputImage.fromBitmap(bitmap, 0)
        return@withContext analyze(image, bitmap).also { lastLook = it }
    }

    fun rememberedLook(): LookResult? = lastLook

    suspend fun explain(bitmap: Bitmap?, ocr: String, labels: List<String>): CloudReply? {
        if (bitmap == null) return null
        val jpeg = jpegBytes(scale(bitmap, 768))
        val prompt = AuraMind.scene(labels, ocr)
        return withContext(Dispatchers.IO) { cloud.vision(prompt, jpeg) }
    }

    suspend fun identify(bitmap: Bitmap?, ocr: String, labels: List<String>, focus: String = ""): CloudReply? {
        if (bitmap == null) return null
        val jpeg = jpegBytes(scale(bitmap, 768))
        val prompt = AuraMind.identify(labels, ocr, focus)
        return withContext(Dispatchers.IO) { cloud.vision(prompt, jpeg) }
    }

    suspend fun explainText(text: String): CloudReply? {
        if (text.isBlank()) return null
        val prompt = AuraMind.reading(text)
        return withContext(Dispatchers.IO) { cloud.text(prompt) }
    }

    suspend fun search(question: String): CloudReply? = withContext(Dispatchers.IO) {
        cloud.search(question)
    }

    suspend fun summarizeMeeting(transcript: String): MeetingBrief? {
        if (transcript.isBlank()) return null
        val prompt = "Resuma esta transcrição em português do Brasil. " +
            "Primeiro um parágrafo curto. Depois uma linha TAREFAS: e cada pendência em uma linha começando com - . " +
            "Se não houver pendência, não invente.\n\n$transcript"
        val reply = withContext(Dispatchers.IO) { cloud.text(prompt) } ?: return null
        return MeetingNotes.parse(reply.text)
    }

    suspend fun translate(text: String, from: String, to: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        if (from == to) return@withContext text
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlCode(from))
            .setTargetLanguage(mlCode(to))
            .build()
        val translator = Translation.getClient(options)
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            translator.translate(text).await()
        } finally {
            translator.close()
        }
    }

    fun listenOnce(onText: (String) -> Unit, onError: (String) -> Unit) {
        meetingLoop = false
        startRecognizer(loop = false, onText = onText, onError = onError)
    }

    fun startMeeting() {
        _meetingLines.value = emptyList()
        _meetingOn.value = true
        meetingLoop = true
        startRecognizer(loop = true, onText = { line ->
            if (line.isNotBlank()) _meetingLines.value = _meetingLines.value + line
        }, onError = {})
    }

    fun stopMeeting(): String {
        meetingLoop = false
        _meetingOn.value = false
        stopRecognizer()
        onRecognizerBusy?.invoke(false)
        return _meetingLines.value.joinToString("\n")
    }

    private suspend fun analyze(image: InputImage, bitmap: Bitmap): LookResult {
        val labels = readLabels(image)
        val text = readText(image)
        val speech = buildString {
            if (labels.isEmpty()) append("Não identifiquei um objeto com segurança. ")
            else append("Eu vejo ${labels.joinToString(", ")}. ")
            if (text.isBlank()) append("Não há texto legível.")
            else append("O texto diz: $text")
        }.trim()
        return LookResult(speech, labels, text, bitmap)
    }

    private suspend fun readLabels(image: InputImage): List<String> {
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build(),
        )
        return try {
            labeler.process(image).await()
                .sortedByDescending { it.confidence }
                .take(4)
                .map { it.text.replace('_', ' ') }
        } catch (_: Exception) {
            emptyList()
        } finally {
            labeler.close()
        }
    }

    private suspend fun readText(image: InputImage): String {
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val first = runCatching { latin.process(image).await().text }.getOrDefault("")
            val second = runCatching { chinese.process(image).await().text }.getOrDefault("")
            listOf(first, second).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
        } finally {
            latin.close()
            chinese.close()
        }
    }

    private fun startRecognizer(loop: Boolean, onText: (String) -> Unit, onError: (String) -> Unit) {
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                onError("O reconhecimento de voz não está disponível neste celular.")
                return@post
            }
            stopRecognizer()
            onRecognizerBusy?.invoke(true)
            val speech = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = speech
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) {
                    _partial.value = ""
                    if (loop && meetingLoop && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        main.postDelayed({ if (meetingLoop) startRecognizer(true, onText, onError) }, 500)
                    } else if (!loop) {
                        onRecognizerBusy?.invoke(false)
                        onError(speechError(error))
                    }
                }

                override fun onResults(results: android.os.Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    _partial.value = ""
                    if (text.isNotBlank()) onText(text)
                    if (loop && meetingLoop) {
                        main.postDelayed({ if (meetingLoop) startRecognizer(true, onText, onError) }, 250)
                    } else if (!loop) {
                        onRecognizerBusy?.invoke(false)
                    }
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    _partial.value = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            })
            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speech.startListening(intent)
        }
    }

    private fun stopRecognizer() {
        recognizer?.stopListening()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun speechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sem permissão de microfone."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "A escuta precisa de rede neste celular."
        SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi. Tente de novo."
        SpeechRecognizer.ERROR_AUDIO -> "O microfone não respondeu."
        else -> "A escuta falhou ($code)."
    }

    private fun decodeFile(file: File): Bitmap? = decodeStream { BitmapFactory.decodeFile(file.absolutePath, it) }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        return decodeStream { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
    }

    private fun decodeStream(decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 && bounds.outWidth > 0) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun scale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / largest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun jpegBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        return stream.toByteArray()
    }

    private fun mlCode(code: String): String = when (code) {
        "en" -> TranslateLanguage.ENGLISH
        "es" -> TranslateLanguage.SPANISH
        "fr" -> TranslateLanguage.FRENCH
        "zh" -> TranslateLanguage.CHINESE
        "de" -> TranslateLanguage.GERMAN
        "it" -> TranslateLanguage.ITALIAN
        else -> TranslateLanguage.PORTUGUESE
    }
}
