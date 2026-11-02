package app.dzz.copilot.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("dzz_copilot", Context.MODE_PRIVATE)
    private val secrets: SharedPreferences = runCatching {
        val master = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "dzz_copilot_secrets",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Fallback keeps the app usable if Keystore is broken on the device.
        context.getSharedPreferences("dzz_copilot_secrets_plain", Context.MODE_PRIVATE)
    }

    private val _keepAlive = MutableStateFlow(prefs.getBoolean(KEY_KEEP, true))
    val keepAlive: StateFlow<Boolean> = _keepAlive.asStateFlow()

    private val _speakAloud = MutableStateFlow(prefs.getBoolean(KEY_SPEAK, true))
    val speakAloud: StateFlow<Boolean> = _speakAloud.asStateFlow()

    private val _placesArmed = MutableStateFlow(prefs.getBoolean(KEY_PLACES, false))
    val placesArmed: StateFlow<Boolean> = _placesArmed.asStateFlow()

    private val _geminiKey = MutableStateFlow(secrets.getString(KEY_GEMINI, "") ?: "")
    val geminiKey: StateFlow<String> = _geminiKey.asStateFlow()

    private val _openRouterKey = MutableStateFlow(secrets.getString(KEY_ROUTER, "") ?: "")
    val openRouterKey: StateFlow<String> = _openRouterKey.asStateFlow()

    private val _groqKey = MutableStateFlow(secrets.getString(KEY_GROQ, "") ?: "")
    val groqKey: StateFlow<String> = _groqKey.asStateFlow()

    private val _siliconKey = MutableStateFlow(secrets.getString(KEY_SILICON, "") ?: "")
    val siliconFlowKey: StateFlow<String> = _siliconKey.asStateFlow()

    private val _ollamaKey = MutableStateFlow(secrets.getString(KEY_OLLAMA, "") ?: "")
    val ollamaKey: StateFlow<String> = _ollamaKey.asStateFlow()

    /** Local LFM3 via Ollama/llama-server on phone or LAN (OpenAI-compatible). */
    private val _localLfmEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOCAL_LFM, true))
    val localLfmEnabled: StateFlow<Boolean> = _localLfmEnabled.asStateFlow()

    private val _localLfmUrl = MutableStateFlow(
        prefs.getString(KEY_LOCAL_LFM_URL, "http://127.0.0.1:11434") ?: "http://127.0.0.1:11434",
    )
    val localLfmUrl: StateFlow<String> = _localLfmUrl.asStateFlow()

    private val _localLfmModel = MutableStateFlow(
        prefs.getString(KEY_LOCAL_LFM_MODEL, "LFM3-Omni-2B") ?: "LFM3-Omni-2B",
    )
    val localLfmModel: StateFlow<String> = _localLfmModel.asStateFlow()

    /** Dilemmas and analyses stay on the phone, even when a cloud key is saved. */
    private val _privateReasoning = MutableStateFlow(prefs.getBoolean(KEY_PRIVATE_REASONING, false))
    val privateReasoning: StateFlow<Boolean> = _privateReasoning.asStateFlow()

    init {
        migrateSecrets()
    }

    private val _deviceAddress = MutableStateFlow(prefs.getString(KEY_ADDRESS, null))
    val deviceAddress: StateFlow<String?> = _deviceAddress.asStateFlow()

    private val _deviceName = MutableStateFlow(prefs.getString(KEY_NAME, null))
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _translateFrom = MutableStateFlow(prefs.getString(KEY_FROM, "pt") ?: "pt")
    val translateFrom: StateFlow<String> = _translateFrom.asStateFlow()

    private val _translateTo = MutableStateFlow(prefs.getString(KEY_TO, "en") ?: "en")
    val translateTo: StateFlow<String> = _translateTo.asStateFlow()

    private val _alternateChannel = MutableStateFlow(prefs.getBoolean(KEY_ALT, false))
    val alternateChannel: StateFlow<Boolean> = _alternateChannel.asStateFlow()

    private val _auraEnabled = MutableStateFlow(prefs.getBoolean(KEY_AURA, true))
    val auraEnabled: StateFlow<Boolean> = _auraEnabled.asStateFlow()

    private val _autoImport = MutableStateFlow(prefs.getBoolean(KEY_AUTO_IMPORT, false))
    val autoImport: StateFlow<Boolean> = _autoImport.asStateFlow()

    private val _voiceName = MutableStateFlow(prefs.getString(KEY_VOICE, "Leda") ?: "Leda")
    val voiceName: StateFlow<String> = _voiceName.asStateFlow()

    private val _voiceBoost = MutableStateFlow(prefs.getString(KEY_BOOST, "alto") ?: "alto")
    val voiceBoost: StateFlow<String> = _voiceBoost.asStateFlow()

    private val _liveListen = MutableStateFlow(prefs.getBoolean(KEY_LIVE, false))
    val liveListen: StateFlow<Boolean> = _liveListen.asStateFlow()

    fun setKeepAlive(value: Boolean) = writeBool(KEY_KEEP, value) { _keepAlive.value = value }
    fun setSpeakAloud(value: Boolean) = writeBool(KEY_SPEAK, value) { _speakAloud.value = value }
    fun setPlacesArmed(value: Boolean) = writeBool(KEY_PLACES, value) { _placesArmed.value = value }
    fun setAlternateChannel(value: Boolean) = writeBool(KEY_ALT, value) { _alternateChannel.value = value }
    fun setAuraEnabled(value: Boolean) = writeBool(KEY_AURA, value) { _auraEnabled.value = value }
    fun setAutoImport(value: Boolean) = writeBool(KEY_AUTO_IMPORT, value) { _autoImport.value = value }
    fun setVoiceName(value: String) = writeString(KEY_VOICE, value) { _voiceName.value = value }
    fun setVoiceBoost(value: String) = writeString(KEY_BOOST, value) { _voiceBoost.value = value }
    fun setLiveListen(value: Boolean) = writeBool(KEY_LIVE, value) { _liveListen.value = value }

    private val _glassesMic = MutableStateFlow(prefs.getBoolean(KEY_GLASSES_MIC, true))
    /** Listen through the glasses microphone (Opus over BLE) instead of the phone's. */
    val glassesMic: StateFlow<Boolean> = _glassesMic.asStateFlow()
    fun setGlassesMic(value: Boolean) = writeBool(KEY_GLASSES_MIC, value) { _glassesMic.value = value }

    private val _localWake = MutableStateFlow(prefs.getBoolean(KEY_LOCAL_WAKE, true))
    /** Detect "Aura" with the offline model; only speech with "Aura" (or a command) goes to the cloud. */
    val localWake: StateFlow<Boolean> = _localWake.asStateFlow()
    fun setLocalWake(value: Boolean) = writeBool(KEY_LOCAL_WAKE, value) { _localWake.value = value }

    private val _emailAddress = MutableStateFlow(prefs.getString(KEY_EMAIL, "") ?: "")
    val emailAddress: StateFlow<String> = _emailAddress.asStateFlow()
    private val _emailPassword = MutableStateFlow(secrets.getString(KEY_EMAIL_PASSWORD, "") ?: "")
    /** App password of the mailbox (never the account password). */
    val emailPassword: StateFlow<String> = _emailPassword.asStateFlow()
    fun setEmail(address: String, password: String) {
        writeString(KEY_EMAIL, address.trim()) { _emailAddress.value = address.trim() }
        val clean = password.replace(" ", "").trim()
        writeSecret(KEY_EMAIL_PASSWORD, clean) { _emailPassword.value = clean }
    }

    private val _proactive = MutableStateFlow(prefs.getBoolean(KEY_PROACTIVE, true))
    /** Aura speaks up on her own: morning brief, event reminders, low battery (not 22h to 7h). */
    val proactive: StateFlow<Boolean> = _proactive.asStateFlow()
    fun setProactive(value: Boolean) = writeBool(KEY_PROACTIVE, value) { _proactive.value = value }
    private val _announceMessages = MutableStateFlow(prefs.getBoolean(KEY_ANNOUNCE, true))
    val announceMessages: StateFlow<Boolean> = _announceMessages.asStateFlow()
    fun setAnnounceMessages(value: Boolean) = writeBool(KEY_ANNOUNCE, value) { _announceMessages.value = value }
    private val _briefHour = MutableStateFlow(prefs.getInt(KEY_BRIEF_HOUR, 8))
    val briefHour: StateFlow<Int> = _briefHour.asStateFlow()
    fun setBriefHour(value: Int) {
        prefs.edit().putInt(KEY_BRIEF_HOUR, value).apply()
        _briefHour.value = value
    }

    private val _liveVoice = MutableStateFlow(prefs.getBoolean(KEY_LIVE_VOICE, true))
    /** After "Aura", talk through Gemini Live (natural voice, interruptions) when there is a Gemini key. */
    val liveVoice: StateFlow<Boolean> = _liveVoice.asStateFlow()
    fun setLiveVoice(value: Boolean) = writeBool(KEY_LIVE_VOICE, value) { _liveVoice.value = value }

    private val _liveModel = MutableStateFlow(prefs.getString(KEY_LIVE_MODEL, DEFAULT_LIVE_MODEL) ?: DEFAULT_LIVE_MODEL)
    val liveModel: StateFlow<String> = _liveModel.asStateFlow()
    fun setLiveModel(value: String) {
        val model = value.trim().removePrefix("models/").ifBlank { DEFAULT_LIVE_MODEL }
        writeString(KEY_LIVE_MODEL, model) { _liveModel.value = model }
    }

    private val _glassesMicAlways = MutableStateFlow(prefs.getBoolean(KEY_GLASSES_ALWAYS, false))
    /** Keep the glasses microphone open even outside conversations (the glasses beep on each reopen). */
    val glassesMicAlways: StateFlow<Boolean> = _glassesMicAlways.asStateFlow()
    fun setGlassesMicAlways(value: Boolean) = writeBool(KEY_GLASSES_ALWAYS, value) { _glassesMicAlways.value = value }

    private val _conversationMic = MutableStateFlow(prefs.getString(KEY_CONVERSATION_MIC, MIC_CALL) ?: MIC_CALL)
    /**
     * Microphone during a conversation: [MIC_CALL] = the glasses as a Bluetooth headset (HFP, continuous,
     * no beeps), [MIC_GLASSES] = the glasses' AI microphone over BLE (one beep per turn), [MIC_PHONE].
     */
    val conversationMic: StateFlow<String> = _conversationMic.asStateFlow()
    fun setConversationMic(value: String) = writeString(KEY_CONVERSATION_MIC, value) { _conversationMic.value = value }

    // Voice focus: hear the user, not the TV or the people around.
    private val _noiseClean = MutableStateFlow(prefs.getBoolean(KEY_NOISE_CLEAN, true))
    val noiseClean: StateFlow<Boolean> = _noiseClean.asStateFlow()
    fun setNoiseClean(value: Boolean) = writeBool(KEY_NOISE_CLEAN, value) { _noiseClean.value = value }
    private val _liveCalm = MutableStateFlow(prefs.getBoolean(KEY_LIVE_CALM, true))
    /** Gemini Live starts a turn only on clear, close speech (ignores far voices and murmur). */
    val liveCalm: StateFlow<Boolean> = _liveCalm.asStateFlow()
    fun setLiveCalm(value: Boolean) = writeBool(KEY_LIVE_CALM, value) { _liveCalm.value = value }
    private val _onlyMyVoice = MutableStateFlow(prefs.getBoolean(KEY_ONLY_ME, false))
    val onlyMyVoice: StateFlow<Boolean> = _onlyMyVoice.asStateFlow()
    fun setOnlyMyVoice(value: Boolean) = writeBool(KEY_ONLY_ME, value) { _onlyMyVoice.value = value }
    private val _voiceStrictness = MutableStateFlow(prefs.getInt(KEY_STRICT, 1))
    val voiceStrictness: StateFlow<Int> = _voiceStrictness.asStateFlow()
    fun setVoiceStrictness(value: Int) {
        val v = value.coerceIn(0, 2)
        prefs.edit().putInt(KEY_STRICT, v).apply()
        _voiceStrictness.value = v
    }
    private val _voicePrint = MutableStateFlow(secrets.getString(KEY_VOICE_PRINT, "") ?: "")
    val voicePrint: StateFlow<String> = _voicePrint.asStateFlow()
    fun setVoicePrint(value: String) = writeSecret(KEY_VOICE_PRINT, value) { _voicePrint.value = value }

    // MCP connections (addresses, keys and tokens), JSON, encrypted.
    private val _mcpServers = MutableStateFlow(secrets.getString(KEY_MCP, "") ?: "")
    val mcpServers: StateFlow<String> = _mcpServers.asStateFlow()
    fun setMcpServers(value: String) = writeSecret(KEY_MCP, value) { _mcpServers.value = value }
    fun setGeminiKey(value: String) = writeSecret(KEY_GEMINI, value.trim()) { _geminiKey.value = value.trim() }
    fun setOpenRouterKey(value: String) = writeSecret(KEY_ROUTER, value.trim()) { _openRouterKey.value = value.trim() }
    fun setGroqKey(value: String) = writeSecret(KEY_GROQ, value.trim()) { _groqKey.value = value.trim() }
    fun setSiliconFlowKey(value: String) = writeSecret(KEY_SILICON, value.trim()) { _siliconKey.value = value.trim() }
    fun setOllamaKey(value: String) = writeSecret(KEY_OLLAMA, value.trim()) { _ollamaKey.value = value.trim() }
    private val _onlyLocal = MutableStateFlow(prefs.getBoolean(KEY_ONLY_LOCAL, false))
    /** Privacy mode: nothing leaves the phone; the brain runs only on the local model. */
    val onlyLocal: StateFlow<Boolean> = _onlyLocal.asStateFlow()
    fun setOnlyLocal(value: Boolean) = writeBool(KEY_ONLY_LOCAL, value) { _onlyLocal.value = value }

    private val _telegramToken = MutableStateFlow(secrets.getString(KEY_TELEGRAM, "") ?: "")
    val telegramToken: StateFlow<String> = _telegramToken.asStateFlow()
    fun setTelegramToken(value: String) = writeSecret(KEY_TELEGRAM, value.trim()) { _telegramToken.value = value.trim() }

    private val _telegramChatId = MutableStateFlow(prefs.getLong(KEY_TELEGRAM_CHAT, 0L))
    /** The only Telegram chat the bot obeys; set by the first /start after the token is saved. */
    val telegramChatId: StateFlow<Long> = _telegramChatId.asStateFlow()
    fun setTelegramChatId(value: Long) {
        prefs.edit().putLong(KEY_TELEGRAM_CHAT, value).apply()
        _telegramChatId.value = value
    }

    fun setLocalLfmEnabled(value: Boolean) = writeBool(KEY_LOCAL_LFM, value) { _localLfmEnabled.value = value }
    fun setPrivateReasoning(value: Boolean) = writeBool(KEY_PRIVATE_REASONING, value) { _privateReasoning.value = value }
    fun setLocalLfmUrl(value: String) =
        writeString(KEY_LOCAL_LFM_URL, value.trim().trimEnd('/')) { _localLfmUrl.value = value.trim().trimEnd('/') }
    fun setLocalLfmModel(value: String) =
        writeString(KEY_LOCAL_LFM_MODEL, value.trim()) { _localLfmModel.value = value.trim().ifBlank { "LFM3-Omni-2B" } }
    fun setLanguages(from: String, to: String) {
        prefs.edit().putString(KEY_FROM, from).putString(KEY_TO, to).apply()
        _translateFrom.value = from
        _translateTo.value = to
    }

    fun setDevice(address: String?, name: String?) {
        prefs.edit().putString(KEY_ADDRESS, address).putString(KEY_NAME, name).apply()
        _deviceAddress.value = address
        _deviceName.value = name
    }

    private fun migrateSecrets() {
        val keys = listOf(KEY_GEMINI, KEY_ROUTER, KEY_GROQ, KEY_SILICON, KEY_OLLAMA)
        var moved = false
        val editor = secrets.edit()
        val plain = prefs.edit()
        for (key in keys) {
            val legacy = prefs.getString(key, null)?.trim().orEmpty()
            if (legacy.isEmpty()) continue
            if (secrets.getString(key, "").isNullOrBlank()) {
                editor.putString(key, legacy)
                moved = true
            }
            plain.remove(key)
        }
        if (moved) editor.apply()
        plain.apply()
    }


    private fun writeBool(key: String, value: Boolean, assign: () -> Unit) {
        prefs.edit().putBoolean(key, value).apply()
        assign()
    }

    private fun writeString(key: String, value: String, assign: () -> Unit) {
        prefs.edit().putString(key, value).apply()
        assign()
    }

    private fun writeSecret(key: String, value: String, assign: () -> Unit) {
        secrets.edit().putString(key, value).apply()
        prefs.edit().remove(key).apply()
        assign()
    }

    private companion object {
        const val KEY_KEEP = "keep"
        const val KEY_SPEAK = "speak"
        const val KEY_PLACES = "places"
        const val KEY_GEMINI = "gemini"
        const val KEY_ROUTER = "router"
        const val KEY_GROQ = "groq"
        const val KEY_SILICON = "silicon"
        const val KEY_OLLAMA = "ollama"
        const val KEY_LOCAL_LFM = "local_lfm"
        const val KEY_LOCAL_LFM_URL = "local_lfm_url"
        const val KEY_LOCAL_LFM_MODEL = "local_lfm_model"
        const val KEY_PRIVATE_REASONING = "private_reasoning"
        const val KEY_ADDRESS = "addr"
        const val KEY_NAME = "name"
        const val KEY_FROM = "from"
        const val KEY_TO = "to"
        const val KEY_ALT = "alt"
        const val KEY_AURA = "aura"
        const val KEY_AUTO_IMPORT = "auto_import"
        const val KEY_VOICE = "voice"
        const val KEY_BOOST = "voice_boost"
        const val KEY_LIVE = "live_listen"
        const val KEY_GLASSES_MIC = "glasses_mic"
        const val KEY_LOCAL_WAKE = "local_wake"
        const val KEY_LIVE_VOICE = "live_voice"
        const val KEY_PROACTIVE = "proactive"
        const val KEY_ANNOUNCE = "announce_messages"
        const val KEY_BRIEF_HOUR = "brief_hour"
        const val KEY_EMAIL = "email_address"
        const val KEY_EMAIL_PASSWORD = "email_password"
        const val KEY_LIVE_MODEL = "live_model"
        const val KEY_GLASSES_ALWAYS = "glasses_mic_always"
        const val KEY_CONVERSATION_MIC = "conversation_mic"
        const val KEY_NOISE_CLEAN = "noise_clean"
        const val KEY_LIVE_CALM = "live_calm"
        const val KEY_ONLY_ME = "only_my_voice"
        const val KEY_STRICT = "voice_strictness"
        const val KEY_VOICE_PRINT = "voice_print"
        const val KEY_MCP = "mcp_servers"
        const val DEFAULT_LIVE_MODEL = "gemini-3.8-live"
        const val KEY_ONLY_LOCAL = "only_local"
        const val KEY_TELEGRAM = "telegram_token"
        const val KEY_TELEGRAM_CHAT = "telegram_chat"
    }
}

/** Values of [PreferencesStore.conversationMic]. */
const val MIC_CALL = "call"
const val MIC_GLASSES = "glasses"
const val MIC_PHONE = "phone"
