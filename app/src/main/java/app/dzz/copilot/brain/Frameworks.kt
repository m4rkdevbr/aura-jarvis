package app.dzz.copilot.brain

import org.json.JSONArray
import org.json.JSONObject

/**
 * One reasoning engine from `assets/frameworks.json`.
 * The user never picks these by name: the router chooses one from the conversation.
 */
data class Framework(
    val id: String,
    val title: String,
    val category: String,
    /** "voz" = answer fits in a short spoken reply; "profundo" = short verdict + full card in the app. */
    val layer: String,
    val trigger: String,
    val requiredVariables: List<String>,
    val questions: List<String>,
    val instruction: String,
    val outputShape: JSONObject,
    val next: List<String>,
) {
    val spokenFirst: Boolean get() = layer == "voz"
}

class FrameworkCatalog(val frameworks: List<Framework>) {
    private val byId = frameworks.associateBy { it.id }

    fun find(id: String?): Framework? = id?.trim()?.let { byId[it] }

    /** Compact list for the router prompt: one line per framework. */
    fun routerMenu(): String = frameworks.joinToString("\n") { "- ${it.id}: ${it.trigger}" }

    companion object {
        fun parse(raw: String): FrameworkCatalog {
            val root = JSONObject(raw)
            val array = root.optJSONArray("frameworks") ?: JSONArray()
            val list = ArrayList<Framework>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                if (id.isEmpty()) continue
                list += Framework(
                    id = id,
                    title = item.optString("title", id),
                    category = item.optString("category"),
                    layer = item.optString("layer", "profundo"),
                    trigger = item.optString("trigger"),
                    requiredVariables = item.optJSONArray("requiredVariables").strings(),
                    questions = item.optJSONArray("questions").strings(),
                    instruction = item.optString("instruction"),
                    outputShape = item.optJSONObject("outputShape") ?: JSONObject(),
                    next = item.optJSONArray("next").strings(),
                )
            }
            return FrameworkCatalog(list)
        }

        private fun JSONArray?.strings(): List<String> {
            if (this == null) return emptyList()
            val out = ArrayList<String>(length())
            for (i in 0 until length()) {
                val value = optString(i).trim()
                if (value.isNotEmpty()) out += value
            }
            return out
        }
    }
}

/** Pulls the first balanced JSON object out of a model reply (code fences, prose around it). */
object JsonReply {
    fun extract(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        var start = raw.indexOf('{')
        while (start >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until raw.length) {
                val c = raw[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val parsed = runCatching { JSONObject(raw.substring(start, i + 1)) }.getOrNull()
                            if (parsed != null) return parsed
                            break
                        }
                    }
                }
            }
            start = raw.indexOf('{', start + 1)
        }
        return null
    }
}
