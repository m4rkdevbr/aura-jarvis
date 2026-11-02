package app.dzz.copilot.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log
import app.dzz.copilot.agenda.TaskText
import app.dzz.copilot.brain.SkillCatalog
import app.dzz.copilot.copilot.CopilotEngine
import app.dzz.copilot.data.ChatLog
import app.dzz.copilot.data.PreferencesStore
import app.dzz.copilot.data.Vault
import app.dzz.copilot.glasses.GlassesClient
import app.dzz.copilot.glasses.LinkPhase
import app.dzz.copilot.glasses.MediaSync
import app.dzz.copilot.phone.PhoneBrief
import app.dzz.copilot.phone.PhoneStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class AuraPhase { Off, Listening, Awake, Running }

data class AuraState(
    val phase: AuraPhase = AuraPhase.Off,
    val heard: String = "",
    val reply: String = "Diga Aura e o comando.",
    val glassesMic: Boolean = false,
)

class AuraSession(
    context: Context,
    private val store: PreferencesStore,
    private val glasses: GlassesClient,
    private val media: MediaSync,
    private val vault: Vault,
    private val copilot: CopilotEngine,
    private val chat: ChatLog,
    private val stage: PhoneStage,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val brief = PhoneBrief(appContext)
    private val brain = CommandBrain(store)
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private var speaking = false
    private var consumed = false
    private var wakeAnnounced = false
    private var armedUntil = 0L
    private var dialogueUntil = 0L
    private var translateLive = false
    private var noMatchStreak = 0
    private var glassesOutput = false
    /** Google SpeechRecognizer left unused — it opens BT HFP and flaps the glasses mic LED. */
    private var sessionOpen = false

    private val phoneMic = PhoneMicEngine(appContext, store) { text -> onHeard(text) }

    /** Noise reduction and the "only my voice" filter, for both microphones. */
    val focus = VoiceFocus(appContext, store).also { f ->
        phoneMic.focus = f
        main.post { live.voiceCheck = { audio -> f.isUserVoice(audio) } }
        Thread({ runCatching { f.warmUp() } }, "aura-voice-model").start()
    }

    /** Glasses microphone (Opus over BLE) feeding the same VAD and Whisper path. */
    val glassesMic = GlassesMic(store, glasses, phoneMic).also { g ->
        // With the glasses open as a Bluetooth headset (or the phone chosen) the BLE mic stays shut.
        g.conversation = {
            (live.active || listeningOpen()) && !phoneMic.callMode &&
                store.conversationMic.value != app.dzz.copilot.data.MIC_PHONE
        }
        g.auraSpeaking = { speaking || live.playing }
        g.onGlassesWake = { main.post { if (running && !paused && !live.active) talk() } }
    }

    /** Offline "Aura" detector and fallback transcript. */
    val localAsr = LocalAsr(appContext).also { asr ->
        phoneMic.localAsr = asr
        phoneMic.commandOpen = { translateLive || listeningOpen() }
        Thread({ asr.warmUp() }, "aura-local-asr").start()
    }

    /** Natural voice conversation (Gemini Live) after "Aura"; the old pipeline is the fallback. */
    private var liveBlockedUntil = 0L
    private var liveFirst: String? = null
    private var liveFallback: Pair<String, AuraCommand>? = null
    /** Self-diagnosis and self-repair (glasses link, Bluetooth drops, photos, internet, tools). */
    val health = app.dzz.copilot.health.HealthMonitor(appContext, store, glasses)

    val live = GeminiLive(store, copilot, glasses, object : GeminiLive.Listener {
        override fun onOpen() = main.post {
            liveFirst?.let { chat.add("voce", it) }
            liveFirst = null
            liveFallback = null
            _state.value = _state.value.copy(phase = AuraPhase.Awake, reply = "Conversa ao vivo. Pode falar e me interromper.")
        }.let { }

        override fun onUser(text: String) = main.post {
            chat.add("voce", text)
            _state.value = _state.value.copy(heard = text)
        }.let { }

        override fun onReply(text: String) = main.post {
            chat.add("aura", text)
            scheduleLearn(LEARN_IDLE_MS)
            _state.value = _state.value.copy(phase = AuraPhase.Awake, reply = text)
        }.let { }

        override fun onTool(name: String) = main.post { note(toolNote(name)) }.let { }

        override fun onToolDone(name: String, ms: Long, ok: Boolean) {
            health.toolDone(name, ms, ok)
        }

        // Audio-only turns carry no transcript, so this is what takes the screen out of "Pensando".
        override fun onTurnDone() = main.post {
            if (_state.value.phase == AuraPhase.Running) {
                _state.value = _state.value.copy(phase = AuraPhase.Awake)
            }
        }.let { }

        override fun onEnd(reason: String, opened: Boolean) = main.post { liveEnded(reason, opened) }.let { }
    })

    init {
        live.callAudio = { phoneMic.callMode }
        live.onSpeaking = { on -> glasses.aiSpeaking(on) }
    }

    /** Phone + glasses tools for the single brain (agent with function calling). */
    private val tools = AuraTools(store, glasses, media, vault, copilot, stage, brief) { captureScene() }.also { t ->
        copilot.liveContext = { t.liveContext() }
        t.liveSeesImage = { live.active }
        t.all().forEach { copilot.brain.tools.register(it) }
        health.tools().forEach { copilot.brain.tools.register(it) }
    }

    private val _state = MutableStateFlow(AuraState())
    val state: StateFlow<AuraState> = _state.asStateFlow()

    fun start() {
        if (!store.auraEnabled.value) {
            stop()
            return
        }
        if (running) return
        running = true
        paused = false
        translateLive = false
        dialogueUntil = 0L
        noMatchStreak = 0
        sessionOpen = false
        consumed = false
        wakeAnnounced = false
        // Kill any leftover Google STT session that could still open SCO/HFP.
        destroyRecognizer()
        _state.value = AuraState(
            phase = AuraPhase.Listening,
            reply = listenHint(),
            glassesMic = false,
        )
        Log.i(TAG, _state.value.reply)
        if (glasses.snapshot.value.phase == LinkPhase.Ready) preferGlassesMic()
        ensurePhoneAudioPath()
        phoneMic.start()
        glassesMic.start()
    }

    fun setLiveListen(enabled: Boolean) {
        store.setLiveListen(enabled)
        if (!enabled) {
            dialogueUntil = 0L
            noMatchStreak = 0
        }
        if (!running && store.auraEnabled.value) start()
        _state.value = _state.value.copy(
            phase = if (running && !paused) AuraPhase.Listening else _state.value.phase,
            reply = listenHint(),
        )
    }

    fun preferGlassesMic() {
        // Output only via A2DP/media — never SCO/HFP (that flaps the glasses mic LED).
        // STT always uses the phone microphone.
        if (!running || glassesOutput) return
        glassesOutput = true
        ensurePhoneAudioPath()
        copilot.setGlassesAudioRoute(true)
        _state.value = _state.value.copy(
            glassesMic = false,
            reply = "Ouvindo pelo celular. Respostas saem nos óculos (som A2DP, sem microfone dos óculos).",
        )
        Log.i(TAG, _state.value.reply)
    }

    fun stop() {
        running = false
        paused = false
        speaking = false
        sessionOpen = false
        translateLive = false
        glassesOutput = false
        armedUntil = 0L
        dialogueUntil = 0L
        noMatchStreak = 0
        copilot.setGlassesAudioRoute(false)
        main.removeCallbacks(speakWatchdog)
        phoneMic.liveTap = null
        live.stop()
        phoneMic.stopCallMic()
        glassesMic.stop()
        phoneMic.stop()
        main.post { destroyRecognizer() }
        ensurePhoneAudioPath()
        _state.value = AuraState(phase = AuraPhase.Off, reply = "Aura está desligada.")
    }

    fun pause() {
        if (!running || paused) return
        paused = true
        live.stop()
        phoneMic.pauseCapture()
        _state.value = _state.value.copy(phase = AuraPhase.Off, reply = "Aura pausada.")
    }

    fun resume() {
        if (!running || !paused) return
        paused = false
        _state.value = _state.value.copy(phase = AuraPhase.Listening, reply = "Ouvindo Aura.")
        if (!speaking) phoneMic.resumeCapture()
    }

    /** The orb / mic button: opens a voice conversation, or ends the one in progress. */
    fun talk() {
        main.post {
            if (live.active) {
                live.stop()
                return@post
            }
            if (!running) start()
            if (paused) resume()
            handle("aura", AuraCommand(AuraAction.Wake))
        }
    }

    /** True while handling typed chat: typed text gets a typed answer, never a voice session. */
    private var typed = false

    fun runPhrase(raw: String) {
        typed = true
        try {
            runTyped(raw)
        } finally {
            typed = false
        }
    }

    private fun runTyped(raw: String) {
        if (!running) start()
        val command = AuraCommands.parse(raw, armed = true) ?: AuraCommands.parse("Aura $raw", armed = false)
        if (command == null) {
            handle(raw, AuraCommand(AuraAction.Chat, raw.trim()))
            return
        }
        handle(raw, command)
    }

    private val speakWatchdog = Runnable {
        if (!running || !speaking) return@Runnable
        Log.w(TAG, "speaking travado — liberando escuta")
        ensurePhoneAudioPath()
        speaking = false
        sessionOpen = false
        openDialogue()
        if (!paused) phoneMic.resumeCapture()
    }

    /** Text from phone-builtin AudioRecord + Whisper — never Google SpeechRecognizer. */
    private fun onHeard(text: String) {
        // During a live talk Gemini hears everything; the old command path must stay quiet.
        if (!running || paused || speaking || live.active) return
        val spoken = text.trim()
        if (spoken.isBlank()) return
        _state.value = _state.value.copy(heard = spoken)
        if (consumed) {
            consumed = false
            return
        }
        noMatchStreak = 0
        if (translateLive) {
            val stop = AuraCommands.parse(spoken, armed = true)
            when (stop?.action) {
                AuraAction.TranslateStop, AuraAction.Stop -> {
                    handle(spoken, AuraCommand(AuraAction.TranslateStop))
                    return
                }
                AuraAction.Wake -> return
                else -> Unit
            }
            val phrase = AuraCommands.spokenPhrase(spoken, armed = true)
            if (phrase.isBlank() || AuraCommands.normalize(phrase) in setOf("aura", "aurea", "aurinha")) {
                return
            }
            handle(spoken, AuraCommand(AuraAction.Translate, phrase))
            return
        }
        val command = AuraCommands.parse(spoken, listeningOpen())
        if (command != null) {
            if (command.action == AuraAction.Wake) {
                if (!wakeAnnounced) {
                    if (startLive(spoken, null)) return
                    wakeAnnounced = true
                    openDialogue()
                    chime()
                    _state.value = _state.value.copy(phase = AuraPhase.Awake, reply = "Pode falar.", heard = spoken)
                }
                return
            }
            consumed = true
            handle(spoken, command)
            return
        }
        val body = AuraCommands.remainder(spoken, listeningOpen())
        if (body != null) {
            if (body.isBlank()) handle(spoken, AuraCommand(AuraAction.Wake))
            else handle(spoken, AuraCommand(AuraAction.Ask, body))
            return
        }
        if (store.liveListen.value && inDialogue()) {
            handle(spoken, AuraCommand(AuraAction.Chat, spoken))
        }
    }

    private fun handle(heard: String, command: AuraCommand, fromAsk: Boolean = false) {
        if (command.action != AuraAction.Ask) {
            Log.i(TAG, "Comando ${command.action} em \"$heard\"")
        }
        speaking = true
        phoneMic.pauseCapture()
        destroyRecognizer()
        if (!fromAsk &&
            command.action != AuraAction.Wake &&
            command.action != AuraAction.Ask &&
            command.action != AuraAction.Chat
        ) {
            chat.add("voce", heard)
        }
        when (command.action) {
            AuraAction.Wake -> {
                if (startLive(heard, null)) return
                speaking = false
                openDialogue()
                chime()
                wakeAnnounced = true
                _state.value = _state.value.copy(phase = AuraPhase.Awake, reply = "Pode falar.", heard = heard)
                if (!paused) phoneMic.resumeCapture()
            }
            AuraAction.Photo -> onGlasses {
                glasses.takePhoto()
                reply("Tirando a foto.")
            }
            AuraAction.VideoStart -> onGlasses {
                glasses.startVideo()
                reply("Gravando vídeo.")
            }
            AuraAction.VideoStop -> onGlasses {
                glasses.stopVideo()
                reply("Vídeo parado.")
            }
            AuraAction.AudioStart -> onGlasses {
                glasses.startAudio()
                reply("Gravando áudio.")
            }
            AuraAction.AudioStop -> onGlasses {
                glasses.stopAudio()
                reply("Áudio parado.")
            }
            AuraAction.Battery -> onGlasses {
                glasses.refreshBattery()
                launchAura {
                    delay(700)
                    val level = glasses.snapshot.value.battery
                    reply(if (level == null) "Ainda não li a bateria." else "Bateria em $level por cento.")
                }
            }
            AuraAction.Time -> onGlasses {
                glasses.syncTime()
                reply("Hora sincronizada. Pode pedir outra coisa.")
            }
            AuraAction.Find -> onGlasses {
                glasses.findGlasses()
                reply("Chamando os óculos.")
            }
            AuraAction.Import -> onGlasses {
                media.import(manualNetwork = false)
                reply("Pedindo a importação.")
            }
            AuraAction.Look -> onGlasses {
                note("Olhando pela câmera dos óculos.")
                launchAura {
                    val bytes = captureScene()
                    if (bytes == null) {
                        reply("A câmera dos óculos não respondeu. Confira a conexão QC e tente de novo.")
                        return@launchAura
                    }
                    val look = copilot.describeBytes(bytes)
                    // Prefer LFM/cloud vision — ML Kit alone is only a fallback.
                    val cloud = copilot.explain(look.bitmap, look.text, look.labels)
                    val spoken = cloud?.text?.takeIf { it.isNotBlank() } ?: look.speech
                    replyVision(spoken, bytes, look.bitmap)
                }
            }
            AuraAction.Identify -> onGlasses {
                note("Identificando o que os óculos veem.")
                launchAura {
                    val bytes = captureScene()
                    val look = if (bytes != null) copilot.describeBytes(bytes) else copilot.rememberedLook()
                    if (look?.bitmap == null) {
                        reply("Não consegui ver a cena pelos óculos. Confira a conexão e tente de novo.")
                        return@launchAura
                    }
                    val cloud = copilot.identify(look.bitmap, look.text, look.labels, command.detail)
                    val spoken = cloud?.text?.takeIf { it.isNotBlank() } ?: look.speech
                    replyVision(spoken, bytes, look.bitmap)
                }
            }
            AuraAction.Explain -> onGlasses {
                note("Vou explicar o que os óculos estão vendo.")
                launchAura {
                    val bytes = captureScene()
                    val look = if (bytes != null) copilot.describeBytes(bytes) else copilot.rememberedLook()
                    if (look?.bitmap == null) {
                        reply("Não consegui ver a cena pelos óculos. Confira a conexão e tente de novo.")
                        return@launchAura
                    }
                    val cloud = copilot.explain(look.bitmap, look.text, look.labels)
                    val spoken = cloud?.text?.takeIf { it.isNotBlank() } ?: look.speech
                    replyVision(spoken, bytes, look.bitmap)
                }
            }
            AuraAction.ExplainText -> {
                val written = copilot.rememberedLook()?.text.orEmpty()
                if (written.isBlank()) {
                    reply("Ainda não li um texto. Diga o que está acontecendo, e depois peça para explicar o texto.")
                } else {
                    note("Explicando o texto.")
                    launchAura {
                        val cloud = copilot.explainText(written)
                        reply(
                            cloud?.text?.takeIf { it.isNotBlank() }
                                ?: "A nuvem não respondeu. Confira a chave do Gemini em Ajustes.",
                        )
                    }
                }
            }
            AuraAction.Translate -> {
                if (command.detail.isNotBlank()) {
                    note("Traduzindo.")
                    launchAura {
                        val out = runCatching {
                            copilot.translate(command.detail, store.translateFrom.value, store.translateTo.value)
                        }.getOrDefault("")
                        if (translateLive) openDialogue()
                        reply(out.ifBlank { "Não consegui traduzir." })
                    }
                } else {
                    // Phone ML Kit only — glasses QC translate would double-speak over TTS.
                    translateLive = true
                    reply(
                        "Modo tradução ligado, de ${store.translateFrom.value} para ${store.translateTo.value}. " +
                            "Fale a frase. Diga parar tradução para sair.",
                    )
                }
            }
            AuraAction.TranslateStop -> {
                translateLive = false
                if (glasses.snapshot.value.phase == LinkPhase.Ready) {
                    runCatching { glasses.stopTranslate() }
                }
                reply("Modo tradução desligado.")
            }
            AuraAction.Search -> {
                if (command.detail.isBlank()) {
                    reply("Diga o que pesquisar.")
                } else {
                    note("Pesquisando ${command.detail}.")
                    launchAura {
                        val cloud = copilot.search(command.detail)
                        reply(
                            cloud?.text?.takeIf { it.isNotBlank() }
                                ?: "A busca não respondeu. Confira a chave do Gemini em Ajustes.",
                        )
                    }
                }
            }
            AuraAction.Agenda -> reply(brief.agenda())
            AuraAction.Messages -> reply(brief.messages())
            AuraAction.Stop -> {
                translateLive = false
                dialogueUntil = 0L
                if (glasses.snapshot.value.phase == LinkPhase.Ready) {
                    glasses.stopVideo()
                    glasses.stopAudio()
                    glasses.stopSpeech()
                    runCatching { glasses.stopTranslate() }
                }
                reply("Parei.")
            }
            AuraAction.Help -> reply(
                "Diga Aura e o pedido: foto, o que é isso, traduz, explica, pesquisa, agenda, mensagens, bateria, me lembre, lembra que, ou resumo do dia. " +
                    "Sem escuta ao vivo, você tem cerca de 25 segundos para o próximo pedido. Na Home, Ao vivo mantém o diálogo aberto.",
            )
            AuraAction.Task -> {
                val parsed = TaskText.parse(command.detail)
                if (parsed == null) {
                    reply("Não entendi a tarefa. Tente: me lembre de beber água em dez minutos.")
                } else {
                    vault.addTask(parsed.title, parsed.dueAt)
                    val whenText = TaskText.formatWhen(parsed.dueAt)
                    reply("Anotei ${parsed.title}. $whenText".trim())
                }
            }
            AuraAction.Ask -> {
                if (!fromAsk && liveFits(command.detail.ifBlank { heard }) && startLive(heard, command.detail.ifBlank { heard })) return
                chat.add("voce", command.detail.ifBlank { heard })
                note("Deixa eu entender.")
                launchAura {
                    val detail = command.detail.ifBlank { heard }
                    if (copilot.reasoning.hasPendingQuestion() || copilot.skillFor(detail) != null) {
                        reply(thinkOrChat(detail))
                        return@launchAura
                    }
                    // Fast path: plain commands ("tira foto", "bateria") skip the model.
                    val direct = AuraCommands.parse(detail, armed = true)
                    if (direct != null && direct.action != AuraAction.Ask && direct.action != AuraAction.Wake &&
                        direct.action != AuraAction.Chat
                    ) {
                        handle(heard, direct, fromAsk = true)
                        return@launchAura
                    }
                    // One brain: the agent picks tools (agenda, câmera, pesquisa, memória, frameworks).
                    val answer = withTimeoutOrNull(45_000) { copilot.converse(detail) { note(toolNote(it)) } }
                    if (!answer.isNullOrBlank()) {
                        reply(answer)
                        return@launchAura
                    }
                    val routed = withTimeoutOrNull(12_000) {
                        withContext(Dispatchers.IO) {
                            runCatching { brain.route(detail) }.getOrElse { AuraCommand(AuraAction.Chat, detail) }
                        }
                    } ?: AuraCommand(AuraAction.Chat, detail)
                    if (routed.action == AuraAction.Ask || routed.action == AuraAction.Chat) {
                        reply(thinkOrChat(detail))
                    } else {
                        handle(heard, routed, fromAsk = true)
                    }
                }
            }
            AuraAction.Chat -> {
                if (!fromAsk && liveFits(command.detail.ifBlank { heard }) && startLive(heard, command.detail.ifBlank { heard })) return
                chat.add("voce", command.detail.ifBlank { heard })
                note("Pensando.")
                launchAura {
                    val detail = command.detail.ifBlank { heard }
                    val answer = if (copilot.reasoning.hasPendingQuestion() || copilot.skillFor(detail) != null) {
                        null
                    } else {
                        withTimeoutOrNull(45_000) { copilot.converse(detail) { note(toolNote(it)) } }
                    }
                    reply(answer?.takeIf { it.isNotBlank() } ?: thinkOrChat(detail))
                }
            }
            AuraAction.Remember -> {
                val fact = copilot.memory.add(AuraCommands.rememberedText(heard) ?: command.detail)
                reply(if (fact == null) "Não entendi o que guardar." else "Guardei: ${fact.text}.")
            }
            AuraAction.Recall -> {
                // Read straight from memory: instant, and a model here could only add invention.
                val found = copilot.memory.search(command.detail, RECALL_FACTS)
                reply(
                    when {
                        found.isNotEmpty() -> "Guardei isto: " + found.joinToString(". ") { it.text.take(160).trimEnd('.') } + "."
                        command.detail.isBlank() -> "Ainda não guardei nada. Diga: Aura, lembra que, e o fato."
                        else -> "Não tenho nada guardado sobre ${command.detail}."
                    },
                )
            }
            AuraAction.Forget -> {
                val gone = copilot.memory.forget(command.detail)
                reply(
                    if (gone != null) {
                        "Esqueci: ${gone.text.take(160)}."
                    } else {
                        "Não apaguei nada, porque não achei um único fato com essas palavras. Veja a lista em Ajustes."
                    },
                )
            }
            AuraAction.Open -> {
                if (command.detail.isBlank()) {
                    reply("Diga o que abrir.")
                } else {
                    stage.openSearch(command.detail)
                    reply("Abri no celular. A página não aparece nos óculos. Se quiser, peça para eu pesquisar e ler.")
                }
            }
            AuraAction.Imagine -> {
                if (command.detail.isBlank()) {
                    reply("Diga o que criar.")
                } else {
                    note("Criando a imagem no celular.")
                    launchAura {
                        reply(copilot.imagine(command.detail).ifBlank { "Não consegui criar a imagem." })
                    }
                }
            }
        }
    }

    /** Plain commands ("tira foto") and pending framework questions stay on the fast local path. */
    private fun liveFits(detail: String): Boolean {
        if (copilot.reasoning.hasPendingQuestion()) return false
        val direct = AuraCommands.parse(detail, armed = true) ?: return true
        return direct.action == AuraAction.Ask || direct.action == AuraAction.Wake || direct.action == AuraAction.Chat
    }

    /** Hands the microphone to Gemini Live. False when Live is off or failed recently. */
    private fun startLive(heard: String, detail: String?): Boolean {
        if (typed || !running || paused || translateLive || !live.available) return false
        if (System.currentTimeMillis() < liveBlockedUntil) return false
        liveFirst = detail?.trim()?.takeIf { it.isNotEmpty() }
        liveFallback = heard to (liveFirst?.let { AuraCommand(AuraAction.Ask, it) } ?: AuraCommand(AuraAction.Wake))
        main.removeCallbacks(speakWatchdog)
        speaking = false
        consumed = false
        wakeAnnounced = true
        ensurePhoneAudioPath()
        focus.gate.reset()
        phoneMic.liveTap = { frame, n -> live.feed(frame, n) }
        openConversationMic()
        phoneMic.resumeCapture()
        if (liveFirst == null) chime()
        _state.value = _state.value.copy(phase = AuraPhase.Running, reply = "Abrindo a conversa ao vivo.", heard = heard)
        live.start(liveFirst)
        return true
    }

    private val announcements = ArrayDeque<String>()
    private val announceRetry = Runnable { flushAnnouncements() }

    /** Aura speaking on her own (reminders, messages). Waits while she is busy or in a live talk. */
    fun announce(text: String) {
        main.post {
            if (!running || paused || text.isBlank()) return@post
            if (announcements.size < 5) announcements.addLast(text)
            flushAnnouncements()
        }
    }

    private fun flushAnnouncements() {
        main.removeCallbacks(announceRetry)
        if (!running || paused) {
            announcements.clear()
            return
        }
        val next = announcements.firstOrNull() ?: return
        if (live.active || speaking) {
            main.postDelayed(announceRetry, 3_000)
            return
        }
        announcements.removeFirst()
        speaking = true
        phoneMic.pauseCapture()
        // After it, the short follow-up window lets the user answer without saying "Aura".
        reply(next)
        if (announcements.isNotEmpty()) main.postDelayed(announceRetry, 3_000)
    }

    /**
     * The microphone for the whole conversation. Default: the glasses as a Bluetooth headset
     * (continuous, near the mouth, no beeps). Without that link: the glasses' BLE microphone once
     * per turn (one beep), with the phone microphone always underneath.
     */
    private fun openConversationMic() {
        val choice = store.conversationMic.value
        if (choice != app.dzz.copilot.data.MIC_CALL) {
            Log.i(TAG, "microfone da conversa: ${if (choice == app.dzz.copilot.data.MIC_PHONE) "celular" else "óculos por vez"}")
            return
        }
        if (phoneMic.startCallMic()) {
            Log.i(TAG, "microfone da conversa: óculos como fone Bluetooth (sem bipes)")
        } else {
            Log.i(TAG, "microfone da conversa: sem chamada Bluetooth, usando os óculos por vez")
        }
    }

    private fun liveEnded(reason: String, opened: Boolean) {
        health.liveEnded(reason, opened)
        if (opened) scheduleLearn(5_000)
        phoneMic.liveTap = null
        phoneMic.stopCallMic()
        speaking = false
        consumed = false
        wakeAnnounced = false
        armedUntil = 0L
        dialogueUntil = 0L
        val fallback = liveFallback
        liveFallback = null
        liveFirst = null
        if (!running) return
        if (!opened) {
            // Live did not open (key, model or network): use the old path for a while.
            liveBlockedUntil = System.currentTimeMillis() + LIVE_RETRY_MS
            Log.w(TAG, "Live indisponível ($reason); usando o caminho antigo")
            if (fallback != null && !paused) {
                handle(fallback.first, fallback.second)
                return
            }
        } else {
            _state.value = _state.value.copy(phase = AuraPhase.Listening, reply = "Conversa encerrada. Diga Aura para chamar de novo.")
        }
        ensurePhoneAudioPath()
        if (!paused) phoneMic.resumeCapture()
    }

    /** Reasoning engines first (dilemmas, problems, decisions), plain conversation otherwise. */
    private suspend fun thinkOrChat(detail: String): String {
        if (!copilot.reasoning.hasPendingQuestion()) {
            copilot.skillFor(detail)?.let { skill -> return runSkill(skill, detail) }
        }
        val thought = withTimeoutOrNull(THINK_TIMEOUT_MS) {
            runCatching { copilot.think(detail) }.getOrNull()
        }
        if (!thought.isNullOrBlank()) return thought
        val cloud = withTimeoutOrNull(28_000) { copilot.ask(detail) }
        return cloud?.text?.takeIf { it.isNotBlank() }
            ?: "Sem resposta da nuvem agora. Confira as chaves do Groq ou do Gemini em Ajustes."
    }

    private fun toolNote(tool: String): String = when (tool) {
        "look" -> "Olhando pela câmera dos óculos."
        "agenda" -> "Lendo a agenda."
        "notifications" -> "Lendo as notificações."
        "web_search" -> "Pesquisando."
        "analyze_problem" -> "Analisando com calma."
        "remember" -> "Guardando na memória."
        "recall" -> "Lembrando."
        "create_image" -> "Criando a imagem."
        "send_sms", "reply_message", "telegram_send" -> "Preparando a mensagem."
        "phone_call" -> "Preparando a ligação."
        "contacts_find" -> "Procurando o contato."
        "calendar_add" -> "Anotando na agenda."
        "files_find", "file_read" -> "Procurando o arquivo."
        "screen_read" -> "Lendo a tela."
        "email_inbox", "email_read" -> "Lendo os e-mails."
        "email_send", "media_send", "whatsapp_message" -> "Preparando o envio."
        else -> "Cuidando disso."
    }

    private suspend fun runSkill(skill: app.dzz.copilot.brain.AuraSkill, request: String): String {
        val data = withContext(Dispatchers.IO) {
            buildMap {
                if (SkillCatalog.NEED_AGENDA in skill.needs) put("agenda", runCatching { brief.agenda() }.getOrDefault(""))
                if (SkillCatalog.NEED_MESSAGES in skill.needs) put("mensagens", runCatching { brief.messages() }.getOrDefault(""))
                if (SkillCatalog.NEED_TASKS in skill.needs) {
                    put(
                        "tarefas",
                        vault.tasks.value.filterNot { it.done }.take(8).joinToString("\n") { task ->
                            task.title + task.dueAt?.let { " (${TaskText.formatWhen(it)})" }.orEmpty()
                        },
                    )
                }
                if (SkillCatalog.NEED_MEMORY in skill.needs) put("memoria", copilot.memory.promptBlock(request))
            }
        }
        val answer = withTimeoutOrNull(SKILL_TIMEOUT_MS) { copilot.runSkill(skill, request, data) }
        return answer?.text?.takeIf { it.isNotBlank() }
            ?: "Não consegui preparar isso agora. Confira o cérebro local ou uma chave de nuvem em Ajustes."
    }

    private fun launchAura(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (error: Exception) {
                Log.w(TAG, "Trabalho da Aura falhou", error)
                if (speaking) reply("Tive um problema. Pode repetir?")
            }
        }
    }

    private fun onGlasses(block: () -> Unit) {
        if (glasses.snapshot.value.phase != LinkPhase.Ready) {
            reply("O canal dos óculos ainda não está pronto.")
            return
        }
        block()
    }

    private fun note(text: String) {
        _state.value = _state.value.copy(phase = AuraPhase.Running, reply = text)
    }

    private suspend fun captureScene(): ByteArray? {
        // In a live conversation the microphone stays with Gemini Live, and Gemini sees the frame too.
        if (live.active) return glasses.captureThumbnail()?.also { live.showImage(it) }
        speaking = true
        phoneMic.pauseCapture()
        main.post { destroyRecognizer() }
        main.removeCallbacks(speakWatchdog)
        main.postDelayed(speakWatchdog, 18_000)
        delay(200)
        return glasses.captureThumbnail()
    }

    private var learnedUpTo = System.currentTimeMillis()
    private var learnJob: kotlinx.coroutines.Job? = null

    /** Learns from the conversation once it goes quiet for [afterMs] (or right after a live talk). */
    private fun scheduleLearn(afterMs: Long) {
        learnJob?.cancel()
        learnJob = scope.launch {
            delay(afterMs)
            val from = learnedUpTo
            val lines = chat.lines.value.filter { it.at > from && (it.role == "voce" || it.role == "aura") }
            val transcript = app.dzz.copilot.brain.Learner.transcript(lines.map { it.role to it.text }) ?: return@launch
            learnedUpTo = lines.maxOf { it.at }
            val learned = runCatching { copilot.learn(transcript) }.getOrDefault(0)
            Log.i(TAG, "aprendizado: $learned itens novos de ${lines.size} falas")
            if (learned > 0) health.note("Aprendizado", "Guardei $learned coisa(s) nova(s) sobre você e seu jeito de usar.")
        }
    }

    private fun reply(text: String, phase: AuraPhase = AuraPhase.Running) {
        scheduleLearn(LEARN_IDLE_MS)
        val spoken = text.trim().ifBlank { "Não consegui responder." }
        speaking = true
        openDialogue()
        _state.value = _state.value.copy(phase = phase, reply = spoken)
        chat.add("aura", spoken)
        finishAfterSpeak(spoken)
    }

    private fun replyVision(text: String, jpeg: ByteArray?, bitmap: android.graphics.Bitmap?) {
        val spoken = text.trim().ifBlank { "Não consegui descrever a cena." }
        speaking = true
        openDialogue()
        _state.value = _state.value.copy(phase = AuraPhase.Running, reply = spoken)
        chat.addVision("aura", spoken, jpeg, bitmap)
        finishAfterSpeak(spoken)
    }

    /** Drop SCO after TTS, then reopen phone STT — avoids mic LED / ERROR_CLIENT flaps. */
    private fun finishAfterSpeak(spoken: String) {
        main.removeCallbacks(speakWatchdog)
        // Soft deadline before the periodic watch hard-caps at 28s.
        main.postDelayed(speakWatchdog, 22_000)
        val done = {
            main.removeCallbacks(speakWatchdog)
            ensurePhoneAudioPath()
            speaking = false
            consumed = false
            wakeAnnounced = false
            openDialogue()
            if (running && !paused) phoneMic.resumeCapture()
        }
        if (spoken.length > 80) {
            copilot.speak(spoken, done, pauseListener = false)
        } else {
            copilot.speakQuick(spoken, done, pauseListener = false)
        }
    }

    private fun chime() {
        runCatching {
            // Always MUSIC — VOICE_CALL would pull HFP/SCO and blink the glasses mic.
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 90)
            main.postDelayed({ runCatching { tone.release() } }, 200)
        }
    }

    private fun openDialogue() {
        val now = System.currentTimeMillis()
        armedUntil = now + FOLLOW_UP_MS
        dialogueUntil = if (store.liveListen.value) now + DIALOGUE_MS else 0L
    }

    private fun armed(): Boolean = System.currentTimeMillis() < armedUntil

    private fun inDialogue(): Boolean =
        translateLive || (store.liveListen.value && System.currentTimeMillis() < dialogueUntil)

    private fun listeningOpen(): Boolean = armed() || inDialogue()

    private fun listenHint(): String {
        return if (store.liveListen.value) {
            "Escuta ao vivo: mic do celular (sem SCO). Diga Aura e continue sem repetir."
        } else {
            "Diga Aura no mic do celular. O mic dos óculos fica desligado de propósito."
        }
    }

    /**
     * Keep the audio path away from HFP/SCO forever.
     * Opening/closing SCO was the mic LED loop on the glasses.
     */
    private fun ensurePhoneAudioPath() {
        if (phoneMic.callMode) return
        val manager = audio ?: return
        manager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 31) {
            val current = runCatching { manager.communicationDevice }.getOrNull()
            val scoLike = current != null && (
                current.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    current.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    current.type == android.media.AudioDeviceInfo.TYPE_TELEPHONY
                )
            if (scoLike) {
                runCatching { manager.clearCommunicationDevice() }
                Log.i(TAG, "SCO/HFP residual limpo — mic dos óculos fica off")
            }
        }
        // Only stop SCO if something else left it on — do not toggle every listen cycle.
        @Suppress("DEPRECATION")
        if (manager.isBluetoothScoOn) {
            Log.i(TAG, "SCO estava ligado — desligando uma vez")
            runCatching {
                @Suppress("DEPRECATION")
                manager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = false
            }
        }
    }

    private fun destroyRecognizer() {
        sessionOpen = false
        val speech = recognizer ?: return
        recognizer = null
        runCatching { speech.destroy() }
    }

    private companion object {
        const val TAG = "DzzAura"
        const val FOLLOW_UP_MS = 25_000L
        const val DIALOGUE_MS = 55_000L
        const val RECALL_FACTS = 3
        /** Long enough for the router and specialist calls when only the phone model is available. */
        const val THINK_TIMEOUT_MS = 45_000L
        const val LEARN_IDLE_MS = 180_000L
        const val SKILL_TIMEOUT_MS = 60_000L
        const val LIVE_RETRY_MS = 120_000L
    }
}
