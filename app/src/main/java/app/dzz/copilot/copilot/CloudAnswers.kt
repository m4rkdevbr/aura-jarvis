package app.dzz.copilot.copilot

import android.util.Base64
import app.dzz.copilot.data.PreferencesStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CloudReply(val text: String, val provider: String)

data class MeetingBrief(val summary: String, val tasks: List<String>)

object MeetingNotes {
    fun parse(raw: String): MeetingBrief {
        val tasks = Regex("(?m)^\\s*[-*]\\s+(.+)$")
            .findAll(raw)
            .map { it.groupValues[1].trim() }
            .filter { it.length > 2 }
            .distinct()
            .toList()
        val summary = raw.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("-") &&
                    !line.startsWith("*") &&
                    !line.equals("RESUMO:", ignoreCase = true) &&
                    !line.equals("TAREFAS:", ignoreCase = true)
            }
            .joinToString(" ")
            .trim()
        return MeetingBrief(summary.ifBlank { raw.trim() }, tasks)
    }
}

class CloudAnswers(private val store: PreferencesStore) {
    private val local = LocalLfmBrain(store)
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun vision(prompt: String, jpeg: ByteArray): CloudReply? {
        local.vision(prompt, jpeg)?.let { return it }
        geminiVision(store.geminiKey.value.trim(), prompt, jpeg)?.let { return CloudReply(it, "Gemini") }
        silicon(store.siliconFlowKey.value.trim(), prompt, jpeg)?.let { return CloudReply(it, "SiliconFlow") }
        openRouter(store.openRouterKey.value.trim(), prompt, jpeg)?.let { return CloudReply(it, "OpenRouter") }
        return null
    }

    /**
     * @param preferLocal false for heavy reasoning: cloud models first, the slow local
     * model only when no cloud answered (or in only-local mode).
     */
    fun text(prompt: String): CloudReply? {
        local.text(prompt)?.let { return it }
        if (store.onlyLocal.value) return null
        return cloudText(prompt, GEMINI_SHORT_TOKENS) ?: local.offlineText(prompt)
    }

    /**
     * Hard thinking: the on-device model answers short questions but does not reason, so the
     * cloud goes first and the phone is the offline fallback, unless the user keeps it private.
     */
    fun deepText(prompt: String, maxTokens: Int): CloudReply? {
        if (!store.privateReasoning.value && !store.onlyLocal.value) {
            cloudText(prompt, maxTokens)?.let { return it }
        }
        return local.text(prompt, minOf(maxTokens, LOCAL_DEEP_TOKENS)) ?: local.offlineText(prompt)
    }

    private fun cloudText(prompt: String, geminiTokens: Int): CloudReply? {
        groq(store.groqKey.value.trim(), prompt)?.let { return CloudReply(it, "Groq") }
        geminiText(store.geminiKey.value.trim(), prompt, geminiTokens)?.let { return CloudReply(it, "Gemini") }
        silicon(store.siliconFlowKey.value.trim(), prompt, null)?.let { return CloudReply(it, "SiliconFlow") }
        ollama(store.ollamaKey.value.trim(), prompt)?.let { return CloudReply(it, "Ollama") }
        openRouter(store.openRouterKey.value.trim(), prompt, null)?.let { return CloudReply(it, "OpenRouter") }
        return null
    }

    fun search(question: String): CloudReply? {
        val key = store.geminiKey.value.trim()
        if (key.isNotEmpty() && !store.onlyLocal.value) {
            val grounded = geminiSearch(key, question)
            if (!grounded.isNullOrBlank()) return CloudReply(grounded, "Gemini")
        }
        return text(AuraMind.searchFallback(question))
    }

    fun converse(turns: List<Pair<String, String>>, facts: String = ""): CloudReply? {
        if (turns.isEmpty()) return null
        local.chat(turns, facts)?.let { return it }
        val key = store.groqKey.value.trim()
        if (key.isNotEmpty()) {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", AuraMind.spoken(facts)))
            turns.takeLast(10).forEach { (role, text) ->
                messages.put(
                    JSONObject()
                        .put("role", if (role == "aura") "assistant" else "user")
                        .put("content", text),
                )
            }
            for (model in GROQ_MODELS) {
                val payload = JSONObject()
                    .put("model", model)
                    .put("temperature", 0.4)
                    .put("max_tokens", 220)
                    .put("messages", messages)
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
                val spoken = execute(request)?.let(::openRouterTextOf)
                if (!spoken.isNullOrBlank()) return CloudReply(spoken, "Groq")
            }
        }
        val flat = turns.takeLast(6).joinToString("\n") { (role, text) ->
            val who = if (role == "aura") "Aura" else "Pessoa"
            "$who: $text"
        }
        return text("${AuraMind.spoken(facts)}\nContinue a conversa.\n$flat\nAura:")
    }

    private fun geminiVision(key: String, prompt: String, jpeg: ByteArray): String? {
        if (key.isEmpty()) return null
        for (model in GEMINI_MODELS) {
            val parts = JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                    ),
                )
            val text = geminiCall(key, model, parts)
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun geminiText(key: String, prompt: String, maxTokens: Int): String? {
        if (key.isEmpty()) return null
        for (model in GEMINI_MODELS) {
            val text = geminiCall(key, model, JSONArray().put(JSONObject().put("text", prompt)), maxTokens)
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun geminiCall(key: String, model: String, parts: JSONArray, maxTokens: Int = GEMINI_SHORT_TOKENS): String? {
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", maxTokens))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return execute(request)?.let(::geminiTextOf)
    }

    private fun geminiSearch(key: String, question: String): String? {
        val parts = JSONArray().put(JSONObject().put("text", "Responda em português do Brasil, curto e direto. $question"))
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 400))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", AuraMind.SPOKEN))))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return execute(request)?.let(::geminiTextOf)
    }

    private fun groq(key: String, prompt: String): String? {
        if (key.isEmpty()) return null
        for (model in GROQ_MODELS) {
            val text = chat("https://api.groq.com/openai/v1/chat/completions", key, model, prompt, null)
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun silicon(key: String, prompt: String, jpeg: ByteArray?): String? {
        if (key.isEmpty()) return null
        val model = if (jpeg == null) "Qwen/Qwen2.5-7B-Instruct" else "Qwen/Qwen2.5-VL-7B-Instruct"
        return chat("https://api.siliconflow.cn/v1/chat/completions", key, model, prompt, jpeg)
    }

    private fun ollama(key: String, prompt: String): String? {
        if (key.isEmpty()) return null
        for (model in OLLAMA_MODELS) {
            val text = chat("https://ollama.com/v1/chat/completions", key, model, prompt, null)
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun chat(url: String, key: String, model: String, prompt: String, jpeg: ByteArray?): String? {
        val content: Any = if (jpeg == null) {
            prompt
        } else {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                    ),
                )
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return execute(request)?.let(::openRouterTextOf)
    }

    private fun openRouter(key: String, prompt: String, jpeg: ByteArray?): String? {
        if (key.isEmpty()) return null
        for (model in ROUTER_MODELS) {
            val content: Any = if (jpeg == null) {
                prompt
            } else {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                            ),
                    )
            }
            val payload = JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer $key")
                .header("HTTP-Referer", "https://dzz.copilot.local")
                .header("X-Title", "DZZ Copiloto")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            val text = execute(request)?.let(::openRouterTextOf)
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun execute(request: Request): String? {
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()
    }

    private fun geminiTextOf(body: String): String? {
        return runCatching {
            JSONObject(body)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()
                ?.ifBlank { null }
        }.getOrNull()
    }

    private fun openRouterTextOf(body: String): String? {
        return runCatching {
            val content = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.opt("content")
            when (content) {
                is String -> content.trim().ifBlank { null }
                is JSONArray -> buildString {
                    for (index in 0 until content.length()) {
                        val part = content.optJSONObject(index) ?: continue
                        append(part.optString("text"))
                    }
                }.trim().ifBlank { null }
                else -> null
            }
        }.getOrNull()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val GEMINI_SHORT_TOKENS = 500
        /** About a minute of generation on the phone, enough for a compact analysis. */
        const val LOCAL_DEEP_TOKENS = 450
        val GEMINI_MODELS = listOf("gemini-2.5-flash", "gemini-2.0-flash")
        val GROQ_MODELS = listOf(
            "openai/gpt-oss-20b",
            "openai/gpt-oss-120b",
            "llama-3.1-8b-instant",
            "llama-3.3-70b-versatile",
        )
        val ROUTER_MODELS = listOf("openrouter/free", "google/gemma-4-31b-it:free")
        val OLLAMA_MODELS = listOf("llama3.2", "gemma2", "mistral")
    }
}
