package app.dzz.copilot.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/** Any chat model: system + user in, text out. Null when every provider failed. */
fun interface Llm {
    suspend fun complete(system: String, user: String, maxTokens: Int): String?
}

data class Analysis(
    val id: String,
    val frameworkId: String,
    val title: String,
    val createdAt: Long,
    val question: String,
    val result: JSONObject,
    val spoken: String,
    val summary: String,
    val next: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("frameworkId", frameworkId)
        .put("title", title)
        .put("createdAt", createdAt)
        .put("question", question)
        .put("result", result)
        .put("spoken", spoken)
        .put("summary", summary)
        .put("next", JSONArray(next))

    companion object {
        fun fromJson(json: JSONObject): Analysis {
            val next = json.optJSONArray("next")
            return Analysis(
                id = json.optString("id"),
                frameworkId = json.optString("frameworkId"),
                title = json.optString("title"),
                createdAt = json.optLong("createdAt"),
                question = json.optString("question"),
                result = json.optJSONObject("result") ?: JSONObject(),
                spoken = json.optString("spoken"),
                summary = json.optString("summary"),
                next = if (next == null) emptyList() else (0 until next.length()).map { next.optString(it) },
            )
        }
    }
}

sealed class Reasoning {
    /** Not a problem that needs a framework: answer as normal conversation. */
    data object NotApplicable : Reasoning()

    /** Missing context: speak these short questions and wait for the answer. */
    data class NeedsContext(val spoken: String, val frameworkId: String) : Reasoning()

    data class Done(val analysis: Analysis) : Reasoning()

    /** A framework was chosen but the model failed; fall back to plain conversation. */
    data class Failed(val frameworkId: String) : Reasoning()
}

/** Saved analyses, one JSON file each, newest first. The app screen and Telegram read from here. */
class AnalysisStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun save(analysis: Analysis) {
        runCatching { File(dir, "${analysis.id}.json").writeText(analysis.toJson().toString()) }
    }

    fun get(id: String): Analysis? = runCatching {
        Analysis.fromJson(JSONObject(File(dir, "$id.json").readText()))
    }.getOrNull()

    fun recent(limit: Int = 30): List<Analysis> {
        val files = dir.listFiles { file -> file.extension == "json" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .take(limit)
        return files.mapNotNull { file ->
            runCatching { Analysis.fromJson(JSONObject(file.readText())) }.getOrNull()
        }
    }

    fun latest(): Analysis? = recent(1).firstOrNull()
}

/**
 * Invisible reasoning engines.
 *
 * 1. A cheap word gate skips small talk and commands, so normal chat pays no extra call.
 * 2. The router (one fast call) picks a framework and says whether context is enough.
 * 3. If not, Aura asks at most two short questions, one round, then runs with what it has.
 * 4. The specialist call returns structured JSON for the app plus a spoken verdict of
 *    at most three short sentences for the glasses.
 */
class ReasoningEngine(
    private val catalog: FrameworkCatalog,
    private val llm: Llm,
    private val store: AnalysisStore,
    /** Long-term facts about the user (projects, priorities, agenda) relevant to this question. */
    private val context: (String) -> String = { "" },
    private val onDone: (Analysis) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private data class Pending(
        val framework: Framework,
        val secondary: Framework?,
        val question: String,
        val answers: MutableList<String>,
        val expiresAt: Long,
    )

    private var pending: Pending? = null

    fun hasPendingQuestion(): Boolean = pending?.let { now() < it.expiresAt } == true

    fun cancelPending() {
        pending = null
    }

    /** @param force skip the cheap word gate (the agent already decided this is a problem). */
    suspend fun handle(text: String, force: Boolean = false): Reasoning {
        val spoken = text.trim()
        if (spoken.isEmpty()) return Reasoning.NotApplicable
        val waiting = pending?.takeIf { now() < it.expiresAt }
        if (waiting != null) {
            pending = null
            waiting.answers += spoken
            return runFramework(waiting.framework, waiting.secondary, waiting.question, waiting.answers)
        }
        pending = null
        if (!force && !looksLikeDilemma(spoken)) return Reasoning.NotApplicable
        val route = route(spoken) ?: return Reasoning.NotApplicable
        if (!route.optBoolean("useFramework", false)) return Reasoning.NotApplicable
        val primary = catalog.find(route.optString("primary")) ?: return Reasoning.NotApplicable
        val secondary = catalog.find(route.optString("secondary"))?.takeIf { it.id != primary.id }
        val enough = route.optBoolean("hasEnoughContext", true)
        val questions = route.optJSONArray("missingQuestions").strings().take(2)
            .ifEmpty { primary.questions.take(2) }
        if (!enough && questions.isNotEmpty()) {
            pending = Pending(primary, secondary, spoken, mutableListOf(), now() + PENDING_MS)
            return Reasoning.NeedsContext(questions.joinToString(" "), primary.id)
        }
        return runFramework(primary, secondary, spoken, emptyList())
    }

    /** Chaining: feed a finished analysis into the next framework ("transforma em plano"). */
    suspend fun chain(fromId: String, nextFrameworkId: String? = null): Reasoning {
        val source = store.get(fromId) ?: store.latest() ?: return Reasoning.NotApplicable
        val nextId = nextFrameworkId ?: source.next.firstOrNull()
        val next = catalog.find(nextId) ?: return Reasoning.NotApplicable
        val input = "Resultado anterior (${source.title}): ${source.summary}\nDados: ${source.result}"
        return runFramework(next, null, source.question, listOf(input))
    }

    private suspend fun route(text: String): JSONObject? {
        val system = """
            Você é o roteador cognitivo da Aura. Decida se a fala do usuário pede um raciocínio estruturado
            (um dilema, problema, decisão, risco, prioridade ou estratégia) ou se é conversa/comando simples.
            Frameworks disponíveis:
            ${catalog.routerMenu()}
            Regras:
            - useFramework=false para conversa casual, perguntas factuais, comandos de óculos ou celular.
            - Escolha 1 primário e no máximo 1 secundário.
            - hasEnoughContext=false só se faltar um dado sem o qual a análise seria genérica.
              Considere o contexto conhecido do usuário antes de concluir que falta algo.
            - missingQuestions: no máximo 2 perguntas curtas, naturais, faladas, sem citar o nome do framework.
            Responda só JSON: {"useFramework":true,"primary":"id","secondary":"id ou vazio","hasEnoughContext":true,"missingQuestions":[]}
        """.trimIndent()
        val user = buildString {
            val known = context(text).trim()
            if (known.isNotEmpty()) append("Contexto conhecido do usuário:\n").append(known).append("\n\n")
            append("Fala do usuário: ").append(text)
        }
        return JsonReply.extract(llm.complete(system, user, ROUTER_TOKENS))
    }

    private suspend fun runFramework(
        framework: Framework,
        secondary: Framework?,
        question: String,
        answers: List<String>,
    ): Reasoning {
        val system = buildString {
            append("Você é a Aura, uma consultora executiva que usa ").append(framework.title)
            append(" em silêncio para resolver o problema do usuário.\n")
            append(framework.instruction).append('\n')
            if (secondary != null) {
                append("Use também a lente de ").append(secondary.title).append(" se ela mudar a recomendação.\n")
            }
            append(
                """
                Regras:
                - Português do Brasil. Específico ao caso do usuário, nunca resposta de livro.
                - Use o contexto conhecido do usuário. Não invente números; marque estimativas como estimativas.
                - voiceSummary: no máximo 3 frases curtas, faladas no ouvido, a ação mais importante primeiro,
                  sem citar o nome do framework, sem listas, sem markdown.
                - summary: um parágrafo executivo de até 60 palavras para o app.
                - nextStep: uma frase curta oferecendo o próximo passo.
                Responda só JSON: {"result": <estrutura abaixo>, "voiceSummary": "", "summary": "", "nextStep": ""}
                Estrutura de result:
                """.trimIndent(),
            )
            append('\n').append(framework.outputShape.toString())
        }
        val user = buildString {
            val known = context(question).trim()
            if (known.isNotEmpty()) append("Contexto conhecido do usuário:\n").append(known).append("\n\n")
            append("Pedido: ").append(question)
            answers.forEachIndexed { index, answer -> append("\nInformação ${index + 1}: ").append(answer) }
        }
        val reply = JsonReply.extract(llm.complete(system, user, SPECIALIST_TOKENS))
            ?: return Reasoning.Failed(framework.id)
        val voice = reply.optString("voiceSummary").trim()
        if (voice.isEmpty()) return Reasoning.Failed(framework.id)
        val spoken = buildString {
            append(limitSentences(voice, 3))
            if (!framework.spokenFirst) append(" Deixei a análise completa no app.")
            reply.optString("nextStep").trim().takeIf { it.isNotEmpty() }?.let { append(' ').append(it) }
        }
        val analysis = Analysis(
            id = "a${now()}",
            frameworkId = framework.id,
            title = framework.title,
            createdAt = now(),
            question = question,
            result = reply.optJSONObject("result") ?: JSONObject(),
            spoken = spoken,
            summary = reply.optString("summary").trim().ifEmpty { voice },
            next = listOfNotNull(secondary?.id) + framework.next,
        )
        store.save(analysis)
        onDone(analysis)
        return Reasoning.Done(analysis)
    }

    companion object {
        const val PENDING_MS = 90_000L
        const val ROUTER_TOKENS = 220
        const val SPECIALIST_TOKENS = 1_400

        private val GATE = Regex(
            "nao sei se|duvida|decid|escolh|vale a pena|devo |deveria|compensa|melhor opcao|" +
                "problema|caiu|caindo|aumentou|piorou|nao funciona|por que (sera|sera que)|causa|" +
                "caos|sobrecarreg|prioriz|muita coisa|nao dou conta|foco|" +
                "lancar|lancamento|apresentar|apresentacao|reuniao com|proposta|" +
                "concorrent|estrategi|mercado|crescer|expandir|investir|risco|" +
                "ideia|travad|empacad|planej|prazo|cronograma|analisa|me ajuda a pensar|o que voce acha",
        )

        /** Cheap first filter so small talk and commands never pay for the router call. */
        fun looksLikeDilemma(text: String): Boolean {
            val plain = normalize(text)
            if (plain.split(' ').count { it.isNotBlank() } < 5) return false
            return GATE.containsMatchIn(plain)
        }

        fun limitSentences(text: String, max: Int): String {
            val parts = Regex("(?<=[.!?])\\s+").split(text.trim()).filter { it.isNotBlank() }
            return parts.take(max).joinToString(" ")
        }

        private fun normalize(raw: String): String =
            Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun JSONArray?.strings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { optString(it).trim() }.filter { it.isNotEmpty() }
        }
    }
}
