package app.dzz.copilot.voice

import app.dzz.copilot.copilot.AuraToolKit
import app.dzz.copilot.copilot.LocalLfmBrain
import app.dzz.copilot.data.PreferencesStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CommandBrain(private val store: PreferencesStore) {
    private val local = LocalLfmBrain(store)
    private val http = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build()

    fun route(phrase: String): AuraCommand {
        val clean = phrase.trim()
        if (clean.length < 2) return AuraCommand(AuraAction.Chat, clean)
        val parsed = AuraCommands.parse(clean, armed = true)
        if (parsed != null && parsed.action != AuraAction.Ask && parsed.action != AuraAction.Wake) {
            return parsed
        }
        if (!AuraToolKit.mightNeedTool(AuraCommands.normalize(clean))) return AuraCommand(AuraAction.Chat, clean)
        // Prefer on-device / LAN LFM3 tool calling before Groq cloud.
        local.route(clean)?.let { return it }
        val key = store.groqKey.value.trim()
        if (key.isEmpty()) return AuraCommand(AuraAction.Chat, clean)
        val raw = ask(key, clean) ?: return AuraCommand(AuraAction.Chat, clean)
        val json = Regex("\\{[^{}]*\\}").find(raw)?.value ?: return AuraCommand(AuraAction.Chat, clean)
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return AuraCommand(AuraAction.Chat, clean)
        val action = obj.optString("acao").trim().lowercase()
        val detail = obj.optString("detalhe").trim().ifBlank { clean }
        return when (action) {
            "pesquisa" -> AuraCommand(AuraAction.Search, detail)
            "agenda" -> AuraCommand(AuraAction.Agenda)
            "mensagens" -> AuraCommand(AuraAction.Messages)
            "explica" -> AuraCommand(AuraAction.Explain)
            "explica_texto" -> AuraCommand(AuraAction.ExplainText)
            "identificar", "identifica", "o_que_e" -> AuraCommand(AuraAction.Identify, detail)
            "traduzir", "traduz" -> AuraCommand(AuraAction.Translate, if (detail == clean) "" else detail)
            "parar_traducao" -> AuraCommand(AuraAction.TranslateStop)
            "olhar" -> AuraCommand(AuraAction.Look)
            "foto" -> AuraCommand(AuraAction.Photo)
            "video" -> AuraCommand(AuraAction.VideoStart)
            "parar_video" -> AuraCommand(AuraAction.VideoStop)
            "audio" -> AuraCommand(AuraAction.AudioStart)
            "parar_audio" -> AuraCommand(AuraAction.AudioStop)
            "bateria" -> AuraCommand(AuraAction.Battery)
            "hora" -> AuraCommand(AuraAction.Time)
            "achar" -> AuraCommand(AuraAction.Find)
            "importar" -> AuraCommand(AuraAction.Import)
            "tarefa" -> AuraCommand(AuraAction.Task, detail)
            "ajuda" -> AuraCommand(AuraAction.Help)
            "parar" -> AuraCommand(AuraAction.Stop)
            "abre" -> AuraCommand(AuraAction.Open, detail)
            "imagem" -> AuraCommand(AuraAction.Imagine, detail)
            "conversa" -> AuraCommand(AuraAction.Chat, detail)
            else -> AuraCommand(AuraAction.Chat, clean)
        }
    }

    private fun ask(key: String, phrase: String): String? {
        val payload = JSONObject()
            .put("temperature", 0)
            .put("max_tokens", 80)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        "Classifique o comando de voz dos óculos. Responda só JSON " +
                            "{\"acao\":\"pesquisa|agenda|mensagens|explica|explica_texto|identificar|traduzir|parar_traducao|olhar|foto|video|parar_video|audio|parar_audio|bateria|hora|achar|importar|tarefa|ajuda|parar|abre|imagem|conversa\",\"detalhe\":\"\"}. " +
                            "detalhe guarda o assunto da pesquisa, a tarefa, o foco do objeto ou a frase a traduzir. Frase: $phrase",
                    ),
                ),
            )
        for (model in MODELS) {
            payload.put("model", model)
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer $key")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            val body = runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                }
            }.getOrNull() ?: continue
            val text = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val MODELS = listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b", "llama-3.1-8b-instant")
    }
}
