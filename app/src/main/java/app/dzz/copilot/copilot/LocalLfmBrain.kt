package app.dzz.copilot.copilot

import android.util.Base64
import android.util.Log
import app.dzz.copilot.data.PreferencesStore
import app.dzz.copilot.voice.AuraCommand
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Local LFM3 brain via Ollama / llama-server OpenAI-compatible API.
 *
 * Typical setups:
 * - PC on the same Wi‑Fi: `http://192.168.x.x:11434` + `ollama pull lfm3-omni`
 * - Termux / sidecar on the phone: `http://127.0.0.1:11434`
 *
 * Full in-process GGUF (llama.cpp NDK) is the next step; this path already
 * makes LFM3 the Aura brain without shipping a 2+ GB native binary yet.
 */
class LocalLfmBrain(private val store: PreferencesStore) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun enabled(): Boolean = store.localLfmEnabled.value && baseUrl().isNotBlank()

    fun ping(): String {
        if (!enabled()) return "Cérebro local desligado em Ajustes."
        // llama-server health + Ollama tags
        val probes = listOf(
            "${baseUrl()}/health",
            "${baseUrl()}/v1/models",
            "${baseUrl()}/api/tags",
        )
        for (url in probes) {
            val request = Request.Builder().url(url).get().build()
            val result = runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    when {
                        url.endsWith("/health") -> "OK · llama-server em ${baseUrl()}"
                        url.endsWith("/v1/models") -> {
                            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                            val names = buildList {
                                for (i in 0 until data.length()) {
                                    data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                                }
                            }
                            "OK · ${baseUrl()} · ${names.ifEmpty { listOf(store.localLfmModel.value) }.joinToString()}"
                        }
                        else -> {
                            val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
                            val names = buildList {
                                for (i in 0 until models.length()) {
                                    models.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                                }
                            }
                            if (names.isEmpty()) null
                            else "OK · Ollama ${baseUrl()} · ${names.take(4).joinToString()}"
                        }
                    }
                }
            }.getOrNull()
            if (!result.isNullOrBlank()) return result
        }
        return "Sem servidor em ${baseUrl()}. Em Ajustes: Instalar e iniciar no celular."
    }

    fun route(phrase: String): AuraCommand? {
        if (!enabled() || phrase.isBlank()) return null
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", AuraToolKit.SYSTEM))
            .put(JSONObject().put("role", "user").put("content", phrase.trim()))
        val (text, message) = complete(messages, withTools = true, maxTokens = ROUTE_TOKENS) ?: return null
        val tools = AuraToolKit.parseToolCalls(message, text)
        // Only return when the model chose a tool; free-form chat goes through converse/text.
        return tools.firstOrNull()
    }

    fun chat(turns: List<Pair<String, String>>, facts: String = ""): CloudReply? {
        if (!enabled() || turns.isEmpty()) return null
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", AuraMind.spoken(facts)))
        turns.takeLast(10).forEach { (role, text) ->
            messages.put(
                JSONObject()
                    .put("role", if (role == "aura") "assistant" else "user")
                    .put("content", text),
            )
        }
        val (text, _) = complete(messages, withTools = false, maxTokens = CHAT_TOKENS) ?: return null
        val spoken = text.trim()
        if (spoken.isBlank()) return null
        return CloudReply(spoken, "LFM3 local")
    }

    fun vision(prompt: String, jpeg: ByteArray): CloudReply? {
        if (!enabled()) return null
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put(
                        "url",
                        "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP),
                    ),
                ),
            )
        val messages = JSONArray()
            .put(
                JSONObject().put("role", "system").put(
                    "content",
                    "Responda em português do Brasil, curto e falável. Não invente o que não estiver na imagem.",
                ),
            )
            .put(JSONObject().put("role", "user").put("content", content))
        val (text, _) = complete(messages, withTools = false, maxTokens = CHAT_TOKENS) ?: return null
        val spoken = text.trim()
        if (spoken.isBlank()) return null
        return CloudReply(spoken, "LFM3 local")
    }

    fun text(prompt: String, maxTokens: Int = CHAT_TOKENS): CloudReply? {
        if (!enabled() || prompt.isBlank()) return null
        // The prompt brings its own persona and format, so the system turn stays minimal.
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Responda em português do Brasil. Não invente fatos."))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val (text, _) = complete(messages, withTools = false, maxTokens = maxTokens) ?: return null
        val spoken = text.trim()
        if (spoken.isBlank()) return null
        return CloudReply(spoken, "LFM3 local")
    }

    private fun complete(messages: JSONArray, withTools: Boolean, maxTokens: Int): Pair<String, JSONObject?>? {
        val payload = JSONObject()
            .put("model", store.localLfmModel.value.trim().ifBlank { "lfm3-omni" })
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0)
            .put("max_tokens", maxTokens)
        if (withTools) payload.put("tools", AuraToolKit.toolsJson())
        val endpoints = listOf(
            "${baseUrl()}/v1/chat/completions",
            "${baseUrl()}/api/chat",
        )
        for (endpoint in endpoints) {
            val body = if (endpoint.endsWith("/api/chat")) {
                // Native Ollama chat (no OpenAI tools) — still useful for plain replies.
                JSONObject()
                    .put("model", payload.getString("model"))
                    .put("messages", messages)
                    .put("stream", false)
                    .toString()
            } else {
                payload.toString()
            }
            val request = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody(JSON))
                .build()
            val raw = runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.i(TAG, "LFM local $endpoint → ${response.code}")
                        null
                    } else {
                        response.body?.string()
                    }
                }
            }.getOrNull() ?: continue
            parseCompletion(raw)?.let { return it }
        }
        return null
    }

    private fun parseCompletion(raw: String): Pair<String, JSONObject?>? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        // OpenAI shape
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        if (message != null) {
            val content = message.optString("content").trim()
            return content to message
        }
        // Ollama /api/chat shape
        val msg = root.optJSONObject("message")
        if (msg != null) {
            return msg.optString("content").trim() to msg
        }
        return null
    }

    private fun baseUrl(): String {
        return store.localLfmUrl.value.trim().trimEnd('/')
    }

    private companion object {
        const val TAG = "DzzLfm"
        const val ROUTE_TOKENS = 80
        const val CHAT_TOKENS = 160
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
