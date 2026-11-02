package app.dzz.copilot.brain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the agent's OpenAI-style message list into plain turns an on-device runtime (LiteRT-LM)
 * can take, and its answer back into a [ModelTurn]. No Android and no runtime types, so it is tested.
 */
object LocalTurns {
    enum class Role { USER, MODEL, TOOL }

    data class Call(val name: String, val args: Map<String, Any?>)

    data class Turn(val role: Role, val text: String, val calls: List<Call> = emptyList(), val toolName: String = "")

    data class Plan(val system: String, val history: List<Turn>, val last: Turn)

    /** Null when there is nothing to answer (no user or tool message at the end). */
    fun plan(messages: JSONArray): Plan? {
        val system = StringBuilder()
        val turns = ArrayList<Turn>()
        val callNames = HashMap<String, String>()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val text = content(message)
            when (message.optString("role")) {
                "system" -> {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(text)
                }
                "user" -> turns += Turn(Role.USER, text)
                "assistant" -> {
                    val calls = ArrayList<Call>()
                    message.optJSONArray("tool_calls")?.let { raw ->
                        for (j in 0 until raw.length()) {
                            val call = raw.optJSONObject(j) ?: continue
                            val fn = call.optJSONObject("function") ?: continue
                            val name = fn.optString("name")
                            if (name.isBlank()) continue
                            callNames[call.optString("id")] = name
                            calls += Call(name, args(fn.opt("arguments")))
                        }
                    }
                    turns += Turn(Role.MODEL, text, calls)
                }
                "tool" -> {
                    val name = message.optString("name").ifBlank { callNames[message.optString("tool_call_id")].orEmpty() }
                    turns += Turn(Role.TOOL, text, toolName = name.ifBlank { "tool" })
                }
            }
        }
        val last = turns.removeLastOrNull() ?: return null
        if (last.role == Role.MODEL) return null
        return Plan(system.toString(), turns, last)
    }

    /** OpenAI `tools` array to one function declaration JSON per tool (what LiteRT-LM expects). */
    fun toolDeclarations(tools: JSONArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until tools.length()) {
            val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
            val name = fn.optString("name").takeIf { it.isNotBlank() } ?: continue
            val decl = JSONObject().put("name", name).put("description", fn.optString("description"))
            fn.optJSONObject("parameters")?.let { decl.put("parameters", it) }
            out += name to decl.toString()
        }
        return out
    }

    /** What the user asked in this turn (the last user message). */
    fun lastUserText(messages: JSONArray): String {
        for (i in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") == "user") return content(message)
        }
        return ""
    }

    /**
     * The tools most related to the request, best first, at most [max]. Tools already called in
     * this turn stay in, so the model can read their results. A tool scores by how many of the
     * request's words (accents and short words ignored) appear in its name and description.
     */
    fun pickTools(tools: JSONArray, request: String, max: Int, called: Set<String> = emptySet(), alwaysKeep: Set<String> = ALWAYS): List<Pair<String, String>> {
        val all = toolDeclarations(tools)
        if (all.size <= max) return all
        val words = words(request)
        val scored = all.mapIndexed { index, (name, json) ->
            val decl = JSONObject(json)
            val text = words(name.replace('_', ' ') + " " + decl.optString("description"))
            val score = words.count { w -> text.any { it.startsWith(w) || w.startsWith(it) } }
            Triple(index, score, name to json)
        }
        val pinned = called + alwaysKeep
        val keep = scored.filter { it.third.first in called } + scored.filter { it.third.first in alwaysKeep && it.third.first !in called }
        val best = scored.filter { it.second > 0 && it.third.first !in pinned }
            .sortedWith(compareByDescending<Triple<Int, Int, Pair<String, String>>> { it.second }.thenBy { it.first })
        return (keep + best).take(max).map { it.third }
    }

    private val ALWAYS = setOf("remember", "recall")

    private fun words(text: String): Set<String> = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 4 && it !in STOP }
        .map { it.take(6) }
        .toSet()

    private val STOP = setOf("para", "como", "qual", "quais", "esta", "este", "isso", "essa", "esse", "voce", "pode", "fazer", "quero", "minha", "meu", "aura", "pelo", "pela", "com", "uma", "sobre", "the", "when", "what")

    /** The runtime's answer as the agent's turn; call ids are made up since the runtime has none. */
    fun turn(text: String, calls: List<Call>): ModelTurn {
        val toolCalls = calls.mapIndexed { i, call ->
            ToolCall("local_${System.nanoTime()}_$i", call.name, JSONObject(call.args.mapValues { (_, v) -> json(v) }))
        }
        val raw = JSONObject().put("role", "assistant").put("content", text)
        return ModelTurn(clean(text), toolCalls, raw)
    }

    /** Small models sometimes wrap the answer in markdown; the reply is spoken, so strip it. */
    fun clean(text: String): String = text
        .replace(Regex("(?s)<think>.*?</think>"), "")
        .replace(Regex("[*#`_]{1,3}"), "")
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    private fun content(message: JSONObject): String = when (val c = message.opt("content")) {
        is String -> c
        is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text").orEmpty() }
        else -> ""
    }

    private fun args(raw: Any?): Map<String, Any?> {
        val obj = when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: return emptyMap()
        return obj.keys().asSequence().associateWith { plain(obj.opt(it)) }
    }

    private fun plain(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.opt(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.opt(it)) }
        else -> value
    }

    private fun json(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> value.forEach { (k, v) -> o.put(k.toString(), json(v)) } }
        is Iterable<*> -> JSONArray().also { a -> value.forEach { a.put(json(it)) } }
        // Gson gives whole numbers as doubles; tools expect ints for counts and minutes.
        is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong() else value
        else -> value
    }
}
