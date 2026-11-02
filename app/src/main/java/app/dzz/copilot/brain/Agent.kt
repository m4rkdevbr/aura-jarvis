package app.dzz.copilot.brain

import org.json.JSONArray
import org.json.JSONObject

/** Something the brain can do: read the agenda, take a photo, send a message. */
interface Tool {
    val name: String
    val description: String
    /** JSON schema of the arguments (OpenAI function-calling shape). */
    val parameters: JSONObject get() = JSONObject().put("type", "object").put("properties", JSONObject())

    /** Returns a short text result that goes back to the model. */
    suspend fun run(args: JSONObject): String
}

/** Small helper to declare a tool inline. */
class SimpleTool(
    override val name: String,
    override val description: String,
    override val parameters: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject()),
    private val action: suspend (JSONObject) -> String,
) : Tool {
    override suspend fun run(args: JSONObject): String = action(args)
}

object Schema {
    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val properties = JSONObject()
        props.forEach { (key, value) -> properties.put(key, value) }
        val schema = JSONObject().put("type", "object").put("properties", properties)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
        return schema
    }

    fun str(description: String): JSONObject = JSONObject().put("type", "string").put("description", description)
    fun int(description: String): JSONObject = JSONObject().put("type", "integer").put("description", description)
}

class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val tools = LinkedHashMap<String, Tool>()

    init {
        tools.forEach { register(it) }
    }

    @Synchronized
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    @Synchronized
    fun unregister(name: String) {
        tools.remove(name)
    }

    @Synchronized
    fun find(name: String): Tool? = tools[name.trim()]

    @Synchronized
    fun names(): List<String> = tools.keys.toList()

    @Synchronized
    fun all(): List<Tool> = tools.values.toList()

    @Synchronized
    fun toOpenAi(): JSONArray {
        val array = JSONArray()
        tools.values.forEach { tool ->
            array.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                        .put("parameters", tool.parameters),
                ),
            )
        }
        return array
    }
}

data class ToolCall(val id: String, val name: String, val arguments: JSONObject)

/** One model turn: either final text, or tool calls to run (and maybe some text). */
data class ModelTurn(val text: String, val toolCalls: List<ToolCall>, val rawMessage: JSONObject)

/** Chat model with function calling (OpenAI-compatible message list). */
fun interface ChatModel {
    suspend fun turn(messages: JSONArray, tools: JSONArray): ModelTurn?
}

data class AgentReply(val text: String, val toolsUsed: List<String>)

/**
 * The single brain. Every input (voice, app, Telegram) goes through [run]:
 * the model sees the user's context, memory and the tool list, calls tools until it
 * can answer, and returns one reply short enough to be spoken.
 */
class Agent(
    private val model: ChatModel,
    private val tools: ToolRegistry,
    private val systemPrompt: () -> String,
    private val maxRounds: Int = 6,
    private val historyLimit: Int = 16,
) {
    private val history = ArrayList<JSONObject>()

    fun reset() {
        synchronized(history) { history.clear() }
    }

    suspend fun run(userText: String, onTool: (String) -> Unit = {}): AgentReply? {
        val used = ArrayList<String>()
        val turnMessages = ArrayList<JSONObject>()
        turnMessages += JSONObject().put("role", "user").put("content", userText)
        repeat(maxRounds) {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
            synchronized(history) { history.forEach { messages.put(it) } }
            turnMessages.forEach { messages.put(it) }
            val turn = model.turn(messages, tools.toOpenAi()) ?: return null
            if (turn.toolCalls.isEmpty()) {
                val answer = turn.text.trim()
                if (answer.isEmpty()) return null
                turnMessages += JSONObject().put("role", "assistant").put("content", answer)
                remember(turnMessages)
                return AgentReply(answer, used)
            }
            turnMessages += assistantWithCalls(turn)
            for (call in turn.toolCalls) {
                val tool = tools.find(call.name)
                onTool(call.name)
                used += call.name
                val result = if (tool == null) {
                    "Erro: ferramenta ${call.name} não existe."
                } else {
                    runCatching { tool.run(call.arguments) }
                        .getOrElse { "Erro ao executar ${call.name}: ${it.message ?: it.javaClass.simpleName}" }
                }
                turnMessages += JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", call.id)
                    .put("name", call.name)
                    .put("content", result.take(MAX_TOOL_RESULT))
            }
        }
        return AgentReply("Precisei de passos demais para isso. Pode dividir o pedido?", used)
    }

    /** Keep only plain user/assistant text in history; tool traffic is per-turn. */
    private fun remember(turn: List<JSONObject>) {
        val user = turn.first()
        val answer = turn.last()
        synchronized(history) {
            history += user
            history += answer
            while (history.size > historyLimit) history.removeAt(0)
        }
    }

    private fun assistantWithCalls(turn: ModelTurn): JSONObject {
        val calls = JSONArray()
        turn.toolCalls.forEach { call ->
            calls.put(
                JSONObject()
                    .put("id", call.id)
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject().put("name", call.name).put("arguments", call.arguments.toString()),
                    ),
            )
        }
        return JSONObject()
            .put("role", "assistant")
            .put("content", turn.text.ifBlank { JSONObject.NULL })
            .put("tool_calls", calls)
    }

    companion object {
        const val MAX_TOOL_RESULT = 4_000

        /** Parses an OpenAI-compatible chat completion body into a [ModelTurn]. */
        fun parseOpenAi(body: String): ModelTurn? {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
            val text = when (val content = message.opt("content")) {
                is String -> content
                is JSONArray -> (0 until content.length()).joinToString("") {
                    content.optJSONObject(it)?.optString("text").orEmpty()
                }
                else -> ""
            }
            val calls = ArrayList<ToolCall>()
            val raw = message.optJSONArray("tool_calls")
            if (raw != null) {
                for (i in 0 until raw.length()) {
                    val call = raw.optJSONObject(i) ?: continue
                    val fn = call.optJSONObject("function") ?: continue
                    val args = when (val a = fn.opt("arguments")) {
                        is JSONObject -> a
                        is String -> runCatching { JSONObject(a.ifBlank { "{}" }) }.getOrElse { JSONObject() }
                        else -> JSONObject()
                    }
                    calls += ToolCall(call.optString("id").ifBlank { "call_$i" }, fn.optString("name"), args)
                }
            }
            return ModelTurn(text, calls, message)
        }
    }
}
