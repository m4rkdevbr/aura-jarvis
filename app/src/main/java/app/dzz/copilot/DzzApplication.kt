package app.dzz.copilot

import android.app.Application
import app.dzz.copilot.copilot.CopilotEngine
import app.dzz.copilot.data.ChatLog
import app.dzz.copilot.data.PreferencesStore
import app.dzz.copilot.data.Vault
import app.dzz.copilot.phone.PhoneStage
import app.dzz.copilot.glasses.GlassesClient
import app.dzz.copilot.glasses.GlassesService
import app.dzz.copilot.glasses.LinkPhase
import app.dzz.copilot.glasses.MediaSync
import app.dzz.copilot.reminder.Channels
import app.dzz.copilot.voice.AuraSession
import app.dzz.copilot.widget.StatusWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DzzApplication : Application() {
    lateinit var hub: AppHub
        private set

    fun hubIfReady(): AppHub? = if (::hub.isInitialized) hub else null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var autoImportJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        hub = AppHub(this)
        Channels.ensure(this)
        scope.launch {
            hub.glasses.snapshot.collect { StatusWidget.update(this@DzzApplication) }
        }
        scope.launch {
            hub.glasses.snapshot
                .map { snap ->
                    snap.phase == LinkPhase.Ready && (snap.qcChannel || snap.alternateChannel)
                }
                .distinctUntilChanged()
                .collect { commandReady ->
                    autoImportJob?.cancel()
                    if (!commandReady || !hub.store.autoImport.value) return@collect
                    autoImportJob = launch {
                        // Wait out reconnect blips before opening glasses Wi‑Fi.
                        delay(2_800)
                        val snap = hub.glasses.snapshot.value
                        if (snap.phase == LinkPhase.Ready &&
                            (snap.qcChannel || snap.alternateChannel) &&
                            hub.store.autoImport.value
                        ) {
                            hub.media.import(manualNetwork = false)
                        }
                    }
                }
        }
        if (hub.store.keepAlive.value && !hub.store.deviceAddress.value.isNullOrBlank()) {
            GlassesService.start(this)
        }
        // FGS start is deferred to MainActivity (Android 12+ background limits).
        scope.launch(Dispatchers.IO) { hub.onDeviceLlm.refresh() }
    }
}

class AppHub(application: Application) {
    val store = PreferencesStore(application)
    val onDeviceLlm = app.dzz.copilot.copilot.OnDeviceLlmRuntime(application)
    val localLfm = app.dzz.copilot.copilot.LocalLfmBrain(store)
    val vault = Vault(application)
    val chat = ChatLog(application)
    val stage = PhoneStage()
    val glasses = GlassesClient(application, store)
    val media = MediaSync(application, glasses)
    val copilot = CopilotEngine(application, store, chat)
    val aura = AuraSession(application, store, glasses, media, vault, copilot, chat, stage)
    val telegram = app.dzz.copilot.telegram.TelegramLink(application, store, copilot, chat)
    val phone = app.dzz.copilot.phone.PhoneActions(application) { bytes, name, mime ->
        telegram.bot.running && telegram.bot.sendDocumentToOwner(bytes, name, mime)
    }
    /** MCP connections (GitHub, Notion, Zapier…): their tools become Aura's tools. */
    val mcp = app.dzz.copilot.mcp.McpHub(application, store, copilot.brain.tools, copilot.userSkillsDir) { copilot.skills.reload() }

    init {
        telegram.tools().forEach { copilot.brain.tools.register(it) }
        phone.tools().forEach { copilot.brain.tools.register(it) }
        app.dzz.copilot.proactive.Proactive(application, store, glasses) { aura.announce(it) }.start()
        app.dzz.copilot.email.MessagingTools(application, store, chat, telegram.bot).tools()
            .forEach { copilot.brain.tools.register(it) }
        mcp.start()
        aura.health.say = { line -> if (store.proactive.value) aura.announce(line) }
        aura.health.start()
        telegram.start()
        copilot.onRecognizerBusy = { busy ->
            if (busy) aura.pause() else if (store.auraEnabled.value) aura.resume()
        }
    }
}

fun android.content.Context.hub(): AppHub = (applicationContext as DzzApplication).hub
