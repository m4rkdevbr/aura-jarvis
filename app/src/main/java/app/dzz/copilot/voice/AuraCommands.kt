package app.dzz.copilot.voice

import java.text.Normalizer

enum class AuraAction {
    Wake,
    Photo,
    VideoStart,
    VideoStop,
    AudioStart,
    AudioStop,
    Battery,
    Time,
    Find,
    Import,
    Look,
    Identify,
    Explain,
    ExplainText,
    Translate,
    TranslateStop,
    Search,
    Agenda,
    Messages,
    Stop,
    Help,
    Task,
    Ask,
    Chat,
    Open,
    Imagine,
    Remember,
    Recall,
    Forget,
}

data class AuraCommand(val action: AuraAction, val detail: String = "")

object AuraCommands {
    const val WAKE = "aura"

    private const val REMEMBER =
        "^(?:lembra que|lembre que|lembre se que|guarda que|guarde que|guarda isso|guarda na memoria|grava na memoria|" +
            "memoriza que|memoriza|memorize que|memorize|nao esquece que|nao esqueca que)\\s+(.+)$"
    private const val REMEMBER_RAW =
        "^(?:lembra que|lembre que|lembre-se que|lembre se que|guarda que|guarde que|guarda isso|guarda na mem[oó]ria|" +
            "grava na mem[oó]ria|memoriza que|memoriza|memorize que|memorize|n[aã]o esquece que|n[aã]o esque[cç]a que)[:,\\s]+(.+)$"
    private const val RECALL =
        "^(?:me lembra |me lembre )?(?:o que (?:voce sabe|voce lembra|eu decidi|decidi|combinei|eu combinei|eu falei|falei|" +
            "eu disse|disse|guardei|voce guardou|ficou combinado|ficou decidido)|voce lembra|do que voce lembra|" +
            "qual foi a decisao|o que tem na memoria)(?: sobre| de| do| da| com| disso)?\\s*(.*)$"

    fun parse(raw: String, armed: Boolean = false): AuraCommand? {
        val body = remainder(raw, armed) ?: return null
        if (body.isBlank()) return AuraCommand(AuraAction.Wake)
        return command(body)
    }

    fun remainder(raw: String, armed: Boolean = false): String? {
        val text = normalize(raw)
        if (text.isBlank()) return null
        val wake = lead(text)
        return when {
            wake != null -> text.substring(wake.range.last + 1).trim()
            armed -> text
            else -> null
        }
    }

    /** Wake-stripped phrase keeping accents and non-Latin characters for translate/TTS. */
    fun spokenPhrase(raw: String, armed: Boolean = false): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val wake = Regex(
            "^(?:(?:ei|oi|ol[aá]|e ai)\\s+)?(?:aura|áurea|aurea|aurinha)\\b[,\\s]*",
            RegexOption.IGNORE_CASE,
        ).find(trimmed)
        return when {
            wake != null -> trimmed.substring(wake.range.last + 1).trim()
            armed -> trimmed
            else -> ""
        }
    }

    fun canRunEarly(action: AuraAction): Boolean {
        return action == AuraAction.Photo ||
            action == AuraAction.Battery ||
            action == AuraAction.Agenda ||
            action == AuraAction.Messages ||
            action == AuraAction.Find ||
            action == AuraAction.Help ||
            action == AuraAction.Time ||
            action == AuraAction.TranslateStop
    }

    fun normalize(raw: String): String {
        val stripped = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return stripped.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun lead(text: String): MatchResult? {
        return Regex("^(?:(?:ei|oi|ola|e ai)\\s+)?(?:aura|aurea|aurinha)\\b").find(text)
            ?: Regex("^hora\\b").find(text)
    }

    /** The fact as spoken, keeping accents, for [AuraAction.Remember]. */
    fun rememberedText(raw: String): String? {
        val phrase = spokenPhrase(raw, armed = true)
        val match = Regex(REMEMBER_RAW, RegexOption.IGNORE_CASE).find(phrase) ?: return null
        return match.groupValues[1].trim().trimEnd('.', '!', ',').ifBlank { null }
    }

    private fun command(body: String): AuraCommand? {
        val forget = Regex("^(?:esquece|esqueca|apaga da memoria|apague da memoria)(?: que| sobre| de| do| da)?\\s+(.+)$").find(body)
        if (forget != null) return AuraCommand(AuraAction.Forget, forget.groupValues[1].trim())
        val remember = Regex(REMEMBER).find(body)
        if (remember != null) return AuraCommand(AuraAction.Remember, remember.groupValues[1].trim())
        val recall = Regex(RECALL).find(body)
        if (recall != null) return AuraCommand(AuraAction.Recall, recall.groupValues[1].trim())
        val task = taskDetail(body)
        if (task != null) return AuraCommand(AuraAction.Task, task)
        val image = Regex("^(?:cria|criar|gere|gerar|desenha|desenhar)(?: uma)? imagem(?: de)?\\s+(.+)$").find(body)
        if (image != null) return AuraCommand(AuraAction.Imagine, image.groupValues[1].trim())
        val open = Regex("^(?:abre|abrir|abra)(?: no celular| a pagina| a página)?\\s+(.+)$").find(body)
        if (open != null) return AuraCommand(AuraAction.Open, open.groupValues[1].trim())
        val search = Regex("^(?:pesquisar|pesquisa|buscar|busca|google)(?: na internet| sobre| por)?\\s+(.+)$").find(body)
        if (search != null) return AuraCommand(AuraAction.Search, search.groupValues[1].trim())
        val translateOnce = Regex("^(?:traduz|traduzir|traduza)(?: isso| isto| para mim)?\\s+(.+)$").find(body)
        if (translateOnce != null) return AuraCommand(AuraAction.Translate, translateOnce.groupValues[1].trim())
        return when {
            body.contains(Regex("parar (a |o )?tradu|sair (da |do )?tradu|\\bfecha tradu")) ->
                AuraCommand(AuraAction.TranslateStop)
            body.contains(Regex("\\btraduz|\\btraduzir|\\btraduza|modo tradu")) ->
                AuraCommand(AuraAction.Translate)
            body.contains(Regex("\\bagenda\\b|\\bcalendario\\b|compromiss|o que tenho|minha agenda")) ->
                AuraCommand(AuraAction.Agenda)
            body.contains(Regex("\\bmensagens\\b|\\bsms\\b|\\bnotificac|\\bwhatsapp\\b|\\bemail\\b|e mail|o que chegou|minhas mensagens")) ->
                AuraCommand(AuraAction.Messages)
            body.contains(Regex("explica (o |este |esse )?(texto|que esta escrito)|explica o que li")) ->
                AuraCommand(AuraAction.ExplainText)
            body.contains(Regex("\\bexplica\\b|\\bexplicar\\b|me explica")) ->
                AuraCommand(AuraAction.Explain)
            body.contains(Regex("parar (o |de )?(video|filme)|para o video|parar (a )?gravacao")) ->
                AuraCommand(AuraAction.VideoStop)
            body.contains(Regex("parar (o |de )?audio|para o audio")) ->
                AuraCommand(AuraAction.AudioStop)
            body.contains(Regex("o que e (isso|aquilo|este|esse|esta|essa)|identifica|identificar|que objeto|leia a placa|le a placa|ler a placa|ler placa|leia o letreiro")) ->
                AuraCommand(AuraAction.Identify, identifyFocus(body))
            body.contains(
                Regex(
                    "o que (esta|voce ve|voce esta)|descrev|olhar|o que acontece|" +
                        "esta vendo|voce esta vendo|me diga o que|me fala o que|me conte o que|" +
                        "o que (os oculos|a camera) (ve|esta)|mostra o que",
                ),
            ) ->
                AuraCommand(AuraAction.Look)
            body.contains(Regex("importar|baixar (as )?fotos|baixar midia")) ->
                AuraCommand(AuraAction.Import)
            body.contains(Regex("\\bbateria\\b|quanta carga|nivel de bateria")) ->
                AuraCommand(AuraAction.Battery)
            body.contains(Regex("sincroniz|\\bhoras?\\b|que horas")) ->
                AuraCommand(AuraAction.Time)
            body.contains(Regex("achar|onde est|procurar os oculos|onde voce esta")) ->
                AuraCommand(AuraAction.Find)
            body.contains(Regex("ajuda|o que voce faz|quais comandos")) ->
                AuraCommand(AuraAction.Help)
            body.contains(Regex("parar|cancela|cancelar|chega")) ->
                AuraCommand(AuraAction.Stop)
            body.contains(Regex("foto|fotograf|tirar uma|tira uma|tira a foto")) ->
                AuraCommand(AuraAction.Photo)
            body.contains(Regex("video|filmar|filmagem|gravar um video")) ->
                AuraCommand(AuraAction.VideoStart)
            body.contains(Regex("\\baudio\\b|gravar audio")) ->
                AuraCommand(AuraAction.AudioStart)
            else -> null
        }
    }

    private fun identifyFocus(body: String): String {
        return when {
            body.contains(Regex("placa|letreiro|texto")) -> "placa ou letreiro"
            body.contains(Regex("objeto")) -> "objeto principal"
            else -> ""
        }
    }

    private fun taskDetail(body: String): String? {
        val markers = listOf(
            "me lembre de ",
            "me lembra de ",
            "me lembra ",
            "lembre de ",
            "lembrar de ",
            "anota ",
            "anotar ",
            "tarefa ",
        )
        val hit = markers.firstOrNull { body.startsWith(it) } ?: return null
        val detail = body.removePrefix(hit).trim()
        if (detail.isBlank()) return null
        return body
    }
}
