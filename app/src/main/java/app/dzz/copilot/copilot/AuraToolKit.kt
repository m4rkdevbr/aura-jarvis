package app.dzz.copilot.copilot

import app.dzz.copilot.voice.AuraAction
import app.dzz.copilot.voice.AuraCommand
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-style tool schemas for LFM3 / Ollama local brain.
 * Maps function names → [AuraCommand] so the on-device (or LAN) model can drive the glasses.
 */
object AuraToolKit {
    val SYSTEM = """
        Você é a Aura, copiloto dos óculos no celular. Responda em português do Brasil, curto e falável.
        Quando o pedido exigir ação nos óculos ou no aparelho, use as tools — não invente que já fez.
        Para conversa pura, responda em texto sem tool.
    """.trimIndent()

    private val TOOL_WORDS = Regex(
        "\\b(foto|fotograf\\w*|video|filma\\w*|audio|grav\\w+|bateria|carga|hora|horas|sincroniz\\w*|oculos|" +
            "importa\\w*|baixa\\w*|olh\\w+|ve|vendo|camera|cena|objeto|identific\\w*|placa|pesquis\\w*|busca\\w*|" +
            "procur\\w*|acha\\w*|agenda|compromisso\\w*|mensage\\w+|sms|whatsapp|email|tarefa\\w*|lembr\\w+|anot\\w+|" +
            "abr\\w+|app|aplicativo|site|traduz\\w*|traducao|ajuda|comandos|par\\w+|cancela\\w*)\\b",
    )

    /**
     * Tool routing costs seconds on the phone, because the schemas enter the prompt. Conversation
     * without any of these words goes straight to the answer. Takes [AuraCommands.normalize] text.
     */
    fun mightNeedTool(normalized: String): Boolean = TOOL_WORDS.containsMatchIn(normalized)

    fun toolsJson(): JSONArray {
        return JSONArray()
            .put(fn("take_photo", "Tirar uma foto com a câmera dos óculos."))
            .put(fn("start_video", "Começar a gravar vídeo nos óculos."))
            .put(fn("stop_video", "Parar a gravação de vídeo."))
            .put(fn("start_audio", "Começar a gravar áudio nos óculos."))
            .put(fn("stop_audio", "Parar a gravação de áudio."))
            .put(fn("battery", "Ler o nível de bateria dos óculos."))
            .put(fn("sync_time", "Sincronizar a hora dos óculos."))
            .put(fn("find_glasses", "Fazer os óculos emitirem sinal para achar."))
            .put(fn("import_media", "Importar fotos/vídeos dos óculos para o celular."))
            .put(fn("look", "Capturar a cena pela câmera dos óculos e descrever o que vê."))
            .put(
                fn(
                    "identify",
                    "Identificar um objeto na cena dos óculos.",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "focus",
                                JSONObject().put("type", "string").put("description", "O que procurar."),
                            ),
                        ),
                ),
            )
            .put(
                fn(
                    "search_web",
                    "Pesquisar informação atual na internet.",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "query",
                                JSONObject().put("type", "string").put("description", "Pergunta ou termo."),
                            ),
                        )
                        .put("required", JSONArray().put("query")),
                ),
            )
            .put(fn("agenda", "Resumir a agenda do celular."))
            .put(fn("messages", "Resumir SMS/notificações recentes."))
            .put(
                fn(
                    "add_task",
                    "Criar um lembrete/tarefa.",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "text",
                                JSONObject().put("type", "string").put("description", "Texto da tarefa."),
                            ),
                        )
                        .put("required", JSONArray().put("text")),
                ),
            )
            .put(
                fn(
                    "open_app",
                    "Abrir um app ou URL no celular.",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "target",
                                JSONObject().put("type", "string").put("description", "Nome do app ou URL."),
                            ),
                        )
                        .put("required", JSONArray().put("target")),
                ),
            )
            .put(
                fn(
                    "translate",
                    "Traduzir fala ou frase (modo tradução Aura).",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "phrase",
                                JSONObject().put("type", "string").put("description", "Frase; vazio = entrar no modo."),
                            ),
                        ),
                ),
            )
            .put(fn("stop_translate", "Sair do modo tradução."))
            .put(fn("help", "Listar o que a Aura sabe fazer."))
            .put(fn("stop", "Parar a Aura / pausar escuta."))
    }

    fun commandFromTool(name: String, args: JSONObject): AuraCommand? {
        val detail = { key: String -> args.optString(key).trim() }
        return when (name.trim().lowercase()) {
            "take_photo", "foto" -> AuraCommand(AuraAction.Photo)
            "start_video", "video" -> AuraCommand(AuraAction.VideoStart)
            "stop_video" -> AuraCommand(AuraAction.VideoStop)
            "start_audio", "audio" -> AuraCommand(AuraAction.AudioStart)
            "stop_audio" -> AuraCommand(AuraAction.AudioStop)
            "battery", "bateria" -> AuraCommand(AuraAction.Battery)
            "sync_time", "hora" -> AuraCommand(AuraAction.Time)
            "find_glasses", "achar" -> AuraCommand(AuraAction.Find)
            "import_media", "importar" -> AuraCommand(AuraAction.Import)
            "look", "olhar" -> AuraCommand(AuraAction.Look)
            "identify", "identificar" -> AuraCommand(AuraAction.Identify, detail("focus"))
            "search_web", "pesquisa" -> AuraCommand(AuraAction.Search, detail("query"))
            "agenda" -> AuraCommand(AuraAction.Agenda)
            "messages", "mensagens" -> AuraCommand(AuraAction.Messages)
            "add_task", "tarefa" -> AuraCommand(AuraAction.Task, detail("text"))
            "open_app", "abre" -> AuraCommand(AuraAction.Open, detail("target"))
            "translate", "traduzir" -> AuraCommand(AuraAction.Translate, detail("phrase"))
            "stop_translate" -> AuraCommand(AuraAction.TranslateStop)
            "help", "ajuda" -> AuraCommand(AuraAction.Help)
            "stop", "parar" -> AuraCommand(AuraAction.Stop)
            else -> null
        }
    }

    /** Parse OpenAI tool_calls and/or LFM XML &lt;tool_call&gt; blocks. */
    fun parseToolCalls(message: JSONObject?, rawText: String): List<AuraCommand> {
        val out = mutableListOf<AuraCommand>()
        val calls = message?.optJSONArray("tool_calls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }
                    .getOrElse { JSONObject() }
                commandFromTool(name, args)?.let { out += it }
            }
        }
        if (out.isNotEmpty()) return out
        val xml = Regex(
            """<tool_call>\s*<function=([^>\n]+)>(.*?)</function>\s*</tool_call>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val param = Regex("""<parameter=([^>\n]+)>\n?(.*?)\n?</parameter>""", RegexOption.DOT_MATCHES_ALL)
        for (match in xml.findAll(rawText)) {
            val name = match.groupValues[1].trim()
            val body = match.groupValues[2]
            val args = JSONObject()
            for (p in param.findAll(body)) {
                val key = p.groupValues[1].trim()
                val value = p.groupValues[2].trim()
                args.put(
                    key,
                    runCatching { JSONObject("{\"v\":$value}").get("v") }.getOrElse { value },
                )
            }
            commandFromTool(name, args)?.let { out += it }
        }
        return out
    }

    private fun fn(name: String, description: String, parameters: JSONObject? = null): JSONObject {
        val params = parameters ?: JSONObject().put("type", "object").put("properties", JSONObject())
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", params),
            )
    }
}
