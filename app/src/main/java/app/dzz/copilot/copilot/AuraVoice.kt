package app.dzz.copilot.copilot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import app.dzz.copilot.data.PreferencesStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * TTS / PCM playback for Aura.
 *
 * Critical: never open Bluetooth SCO/HFP. SCO is bidirectional — every start/stop
 * toggles the glasses mic LED and breaks usable speech. Glasses hear Aura via A2DP
 * (STREAM_MUSIC). Listening always stays on the phone mic (see AuraSession).
 */
class AuraVoice(
    context: Context,
    private val store: PreferencesStore,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val http = OkHttpClient.Builder().callTimeout(14, TimeUnit.SECONDS).build()
    private var tts: TextToSpeech? = null
    private var ready = false
    /** Prefer A2DP / media route to the glasses — never HFP/SCO. */
    @Volatile
    var glassesRoute: Boolean = false

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val engine = tts ?: return@TextToSpeech
            engine.language = Locale("pt", "BR")
            engine.setPitch(1.04f)
            engine.setSpeechRate(1.0f)
            val voice = engine.voices.orEmpty()
                .filter { it.locale.language == "pt" && !isMaleVoice(it.name) }
                .maxByOrNull { score(it) }
            if (voice != null) engine.voice = voice
            engine.setAudioAttributes(mediaAttributes())
            ready = true
        }
    }

    fun speakQuick(text: String, onDone: (() -> Unit)? = null) {
        if (!store.speakAloud.value || text.isBlank()) {
            main.post { onDone?.invoke() }
            return
        }
        main.post { systemSpeak(text, onDone) }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!store.speakAloud.value || text.isBlank()) {
            main.post { onDone?.invoke() }
            return
        }
        Thread {
            try {
                val pcm = runCatching { geminiSpeech(text) }.getOrNull()
                if (pcm != null && pcm.isNotEmpty()) {
                    runCatching { play(pcm) }
                    main.post { onDone?.invoke() }
                } else {
                    main.post { systemSpeak(text, onDone) }
                }
            } catch (_: Exception) {
                main.post { systemSpeak(text, onDone) }
            }
        }.start()
    }

    private fun geminiSpeech(text: String): ByteArray? {
        val key = store.geminiKey.value.trim()
        if (key.isEmpty()) return null
        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "Fale em português do Brasil, com voz feminina leve e natural, sem pressa e sem teatralidade: $text",
                            ),
                        ),
                    ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", store.voiceName.value.ifBlank { "Leda" }),
                            ),
                        ),
                    ),
            )
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$key")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        } ?: return null
        val data = JSONObject(body)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.let { part ->
                part.optJSONObject("inlineData")?.optString("data")
                    ?: part.optJSONObject("inline_data")?.optString("data")
            }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return Base64.decode(data, Base64.DEFAULT)
    }

    private fun play(pcm: ByteArray) {
        prepareMediaOutput()
        val boosted = amplify(pcm, boostGain())
        val size = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(boosted.size)
        val track = AudioTrack.Builder()
            .setAudioAttributes(mediaAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            if (glassesRoute) preferA2dpOutput(track)
            track.play()
            var offset = 0
            while (offset < boosted.size) {
                val written = track.write(boosted, offset, boosted.size - offset)
                if (written <= 0) break
                offset += written
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun systemSpeak(text: String, onDone: (() -> Unit)?) {
        val engine = tts
        if (!ready || engine == null) {
            onDone?.invoke()
            return
        }
        prepareMediaOutput()
        engine.setAudioAttributes(mediaAttributes())
        val utterance = "aura-${System.nanoTime()}"
        var finished = false
        fun finishOnce() {
            if (finished) return
            finished = true
            main.post { onDone?.invoke() }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == utterance) finishOnce()
            }
            override fun onError(utteranceId: String?) {
                if (utteranceId == utterance) finishOnce()
            }
            @Deprecated("Deprecated in API")
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == utterance) finishOnce()
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == utterance) finishOnce()
            }
        })
        // STREAM_MUSIC → A2DP. Never STREAM_VOICE_CALL (that pulls HFP/SCO and flaps the mic LED).
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }
        val spoken = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utterance)
        if (spoken == TextToSpeech.ERROR) finishOnce()
        main.postDelayed({ finishOnce() }, 25_000)
    }

    /**
     * Media-only path. Deliberately avoids MODE_IN_COMMUNICATION, startBluetoothSco,
     * setCommunicationDevice(SCO/BLE_HEADSET) — those open the glasses microphone.
     */
    internal fun prepareMediaOutput() {
        val manager = audio ?: return
        manager.mode = AudioManager.MODE_NORMAL
        // Ensure we are not left on a leftover communication device from another app.
        if (Build.VERSION.SDK_INT >= 31) {
            val current = runCatching { manager.communicationDevice }.getOrNull()
            if (current != null && isScoLike(current.type)) {
                Log.i(TAG, "Limpando device de comunicação SCO residual (${current.type})")
                runCatching { manager.clearCommunicationDevice() }
            }
        }
        val musicMax = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = when {
            !glassesRoute -> max(1, (musicMax * if (store.voiceBoost.value == "max") 1f else 0.85f).toInt())
            store.voiceBoost.value == "max" -> musicMax
            store.voiceBoost.value == "alto" -> max(1, (musicMax * 0.92f).toInt())
            else -> max(1, (musicMax * 0.8f).toInt())
        }
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }

    internal fun preferA2dpOutput(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < 23) return
        val manager = audio ?: return
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val a2dp = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
        if (a2dp != null) {
            runCatching { track.setPreferredDevice(a2dp) }
            Log.i(TAG, "PCM → A2DP/BLE speaker ${a2dp.productName}")
            return
        }
        // Do NOT fall back to SCO — that would revive the mic LED loop.
        Log.i(TAG, "Sem A2DP — áudio segue rota media padrão (sem SCO)")
    }

    internal fun callAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Aura's voice on the headset call link while the glasses microphone is open as a headset. */
    internal fun preferCallOutput(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < 23) return
        val sco = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        if (sco != null) runCatching { track.setPreferredDevice(sco) }
        Log.i(TAG, "PCM → chamada Bluetooth ${sco?.productName ?: "(rota padrão)"}")
    }

    internal fun mediaAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    internal fun boostGain(): Float {
        return when (store.voiceBoost.value) {
            "max" -> 1.85f
            "alto" -> 1.55f
            else -> 1.35f
        }
    }

    internal fun amplify(pcm: ByteArray, gain: Float): ByteArray {
        if (pcm.size < 2) return pcm
        val samples = pcm.size / 2
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteArray(pcm.size)
        val dest = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) {
            val sample = buffer.short.toInt()
            val boosted = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            dest.putShort(boosted.toShort())
        }
        return out
    }

    /** "female" contains "male": check the male markers without matching female names. */
    private fun isMaleVoice(name: String): Boolean {
        val n = name.lowercase()
        if (n.contains("female") || n.contains("feminin")) return false
        return Regex("(^|[^a-z])male|masculin|-m-|#male").containsMatchIn(n)
    }

    private fun score(voice: android.speech.tts.Voice): Int {
        var value = voice.quality
        val name = voice.name.lowercase()
        if (name.contains("female") || name.contains("-f") || name.contains("fem")) value += 8
        if (voice.locale.country.equals("BR", true)) value += 4
        if (!voice.isNetworkConnectionRequired) value += 1
        return value
    }

    private companion object {
        const val TAG = "AuraVoice"
        const val SAMPLE_RATE = 24_000
        val JSON = "application/json".toMediaType()

        fun isScoLike(type: Int): Boolean {
            return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                type == AudioDeviceInfo.TYPE_TELEPHONY
        }
    }
}
