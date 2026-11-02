package app.dzz.copilot.voice

import app.dzz.copilot.brain.Tool
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** What the Gemini Live server said, already decoded. */
sealed class LiveEvent {
    object SetupDone : LiveEvent()
    class Audio(val pcm: ByteArray) : LiveEvent()
    data class UserText(val text: String) : LiveEvent()
    data class ModelText(val text: String) : LiveEvent()
    object Interrupted : LiveEvent()
    object TurnDone : LiveEvent()
    data class ToolCall(val id: String, val name: String, val args: JSONObject) : LiveEvent()
    data class ToolCancel(val ids: List<String>) : LiveEvent()
    data class GoAway(val timeLeft: String) : LiveEvent()
}

/**
 * Messages of the Gemini Live WebSocket (BidiGenerateContent): 16 kHz PCM in, 24 kHz PCM out,
 * function calls over the same socket. Pure JSON, no Android, so it is unit tested.
 */
object LiveProtocol {
    const val URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val INPUT_RATE = 16_000
    const val OUTPUT_RATE = 24_000

    fun setup(model: String, voice: String, system: String, functions: JSONArray, calm: Boolean = false): String {
        val generation = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "speechConfig",
                JSONObject().put(
                    "voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice.ifBlank { "Leda" })),
                ),
            )
        val setup = JSONObject()
            .put("model", if (model.startsWith("models/")) model else "models/$model")
            .put("generationConfig", generation)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            // Without compression an audio+video session ends after about two minutes.
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        if (calm) {
            // Noisy rooms: a turn starts only on clear, close speech and ends promptly when the user stops.
            setup.put(
                "realtimeInputConfig",
                JSONObject().put(
                    "automaticActivityDetection",
                    JSONObject()
                        .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                        .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                        .put("prefixPaddingMs", 200)
                        .put("silenceDurationMs", 700),
                ),
            )
        }
        if (functions.length() > 0) {
            setup.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functions)))
        }
        return JSONObject().put("setup", setup).toString()
    }

    fun audio(pcm: ByteArray, length: Int = pcm.size): String {
        val data = Base64.getEncoder().encodeToString(if (length == pcm.size) pcm else pcm.copyOf(length))
        return JSONObject().put(
            "realtimeInput",
            JSONObject().put("audio", JSONObject().put("data", data).put("mimeType", "audio/pcm;rate=$INPUT_RATE")),
        ).toString()
    }

    fun image(jpeg: ByteArray): String = JSONObject().put(
        "realtimeInput",
        JSONObject().put(
            "video",
            JSONObject().put("data", Base64.getEncoder().encodeToString(jpeg)).put("mimeType", "image/jpeg"),
        ),
    ).toString()

    fun text(text: String): String =
        JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString()

    fun toolResponse(id: String, name: String, result: String): String = JSONObject().put(
        "toolResponse",
        JSONObject().put(
            "functionResponses",
            JSONArray().put(
                JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result)),
            ),
        ),
    ).toString()

    /** Brain tools as Gemini function declarations (OpenAPI types in capitals, empty schemas left out). */
    fun functions(tools: List<Tool>): JSONArray {
        val out = JSONArray()
        tools.forEach { tool ->
            val decl = JSONObject().put("name", tool.name).put("description", tool.description)
            val props = tool.parameters.optJSONObject("properties")
            if (props != null && props.length() > 0) decl.put("parameters", schema(tool.parameters))
            out.put(decl)
        }
        return out
    }

    private val SCHEMA_KEYS = setOf("type", "description", "properties", "required", "items", "enum", "format", "nullable")

    private fun schema(source: JSONObject): JSONObject {
        val out = JSONObject()
        source.keys().forEach { key ->
            if (key !in SCHEMA_KEYS) return@forEach
            when (key) {
                "type" -> out.put("type", source.optString("type").uppercase())
                "properties" -> {
                    val props = source.optJSONObject("properties") ?: return@forEach
                    val mapped = JSONObject()
                    props.keys().forEach { name -> props.optJSONObject(name)?.let { mapped.put(name, schema(it)) } }
                    out.put("properties", mapped)
                }
                "items" -> source.optJSONObject("items")?.let { out.put("items", schema(it)) }
                else -> out.put(key, source.get(key))
            }
        }
        return out
    }

    fun parse(message: String): List<LiveEvent> {
        val root = runCatching { JSONObject(message) }.getOrNull() ?: return emptyList()
        val events = mutableListOf<LiveEvent>()
        if (root.has("setupComplete")) events += LiveEvent.SetupDone
        root.optJSONObject("serverContent")?.let { content ->
            content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
                ?.let { events += LiveEvent.UserText(it) }
            if (content.optBoolean("interrupted")) events += LiveEvent.Interrupted
            val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                    val data = inline?.optString("data").orEmpty()
                    if (data.isNotEmpty() && inline?.optString("mimeType").orEmpty().let { it.isEmpty() || it.startsWith("audio") }) {
                        runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.let { events += LiveEvent.Audio(it) }
                    }
                }
            }
            content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
                ?.let { events += LiveEvent.ModelText(it) }
            if (content.optBoolean("turnComplete")) events += LiveEvent.TurnDone
        }
        root.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                events += LiveEvent.ToolCall(
                    call.optString("id"),
                    call.optString("name"),
                    call.optJSONObject("args") ?: JSONObject(),
                )
            }
        }
        root.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { ids ->
            events += LiveEvent.ToolCancel((0 until ids.length()).map { ids.optString(it) })
        }
        root.optJSONObject("goAway")?.let { events += LiveEvent.GoAway(it.optString("timeLeft")) }
        return events
    }
}

/**
 * Half-duplex audio towards Gemini Live. While Aura's voice plays, the glasses microphone also hears
 * her (the speakers sit next to it), so the microphone is held back and only a real interruption
 * gets through: louder than her echo by a clear margin (the echo level is learned while she talks),
 * for about a quarter of a second, and, when a voice print exists, recognized as the user's voice.
 * Otherwise audio goes out in 100 ms chunks.
 */
class LiveDuplex(
    private val bargeRms: Double = 3_000.0,
    private val bargeFrames: Int = 12,
    private val chunkFrames: Int = 5,
    private val tailFrames: Int = 20,
    private val echoMargin: Double = 2.5,
    /** Optional check that the held audio is the user's own voice (voice print). */
    private val isUser: ((ShortArray) -> Boolean)? = null,
) {
    sealed class Out {
        object Hold : Out()
        class Send(val pcm: ByteArray) : Out()
        class BargeIn(val pcm: ByteArray) : Out()
    }

    private val recent = ArrayDeque<ShortArray>()
    private val chunk = java.io.ByteArrayOutputStream()
    private var chunkCount = 0
    private var loud = 0
    private var tail = 0
    private var echo = 0.0
    private var playFrames = 0

    /** Barge-in threshold right now (for logs and tests). */
    val threshold: Double get() = maxOf(bargeRms, echo * echoMargin)

    /** [playing] is true while Aura's voice is still coming out of the speaker. */
    fun push(frame: ShortArray, n: Int, playing: Boolean): Out {
        val level = SpeechSegmenter.rms(frame, n)
        if (playing || tail > 0) {
            if (playing) tail = tailFrames else tail--
            recent.addLast(frame.copyOf(n))
            while (recent.size > bargeFrames + 3) recent.removeFirst()
            if (playFrames++ < WARMUP_FRAMES) {
                // First moments of her voice: only learn how loud she sounds.
                echo = maxOf(echo, level)
                return Out.Hold
            }
            val limit = threshold
            if (level >= limit) {
                loud++
            } else {
                loud = 0
                // Learn how loud Aura sounds in the microphone (rises fast, falls slowly).
                echo = if (level > echo) 0.6 * echo + 0.4 * level else 0.98 * echo + 0.02 * level
            }
            if (loud < bargeFrames) return Out.Hold
            val held = recent.toList()
            loud = 0
            val joined = ShortArray(held.sumOf { it.size })
            var at = 0
            held.forEach { System.arraycopy(it, 0, joined, at, it.size); at += it.size }
            if (isUser != null && !isUser.invoke(joined)) return Out.Hold
            recent.clear()
            tail = 0
            chunk.reset()
            chunkCount = 0
            return Out.BargeIn(SpeechSegmenter.toBytes(joined, joined.size))
        }
        recent.clear()
        loud = 0
        playFrames = 0
        echo *= 0.995
        chunk.write(SpeechSegmenter.toBytes(frame, n))
        chunkCount++
        if (chunkCount < chunkFrames) return Out.Hold
        val out = chunk.toByteArray()
        chunk.reset()
        chunkCount = 0
        return Out.Send(out)
    }

    fun reset() {
        recent.clear()
        chunk.reset()
        chunkCount = 0
        loud = 0
        tail = 0
        echo = 0.0
        playFrames = 0
    }

    private companion object {
        const val WARMUP_FRAMES = 15
    }
}
