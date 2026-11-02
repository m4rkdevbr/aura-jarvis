package app.dzz.copilot.brain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTurnsTest {
    private fun msg(role: String, content: Any?) = JSONObject().put("role", role).put("content", content ?: JSONObject.NULL)

    @Test
    fun planSplitsSystemHistoryAndLastTurnWithToolTraffic() {
        val call = JSONObject().put("id", "c1").put("type", "function")
            .put("function", JSONObject().put("name", "agenda").put("arguments", """{"dias":2,"filtro":{"tipo":"reuniao"}}"""))
        val messages = JSONArray()
            .put(msg("system", "Você é a Aura."))
            .put(msg("user", "oi"))
            .put(msg("assistant", "Oi!"))
            .put(msg("user", "o que tenho amanhã?"))
            .put(msg("assistant", null).put("tool_calls", JSONArray().put(call)))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "Reunião às 10h"))
        val plan = LocalTurns.plan(messages)!!
        assertEquals("Você é a Aura.", plan.system)
        assertEquals(4, plan.history.size)
        val withCall = plan.history.last()
        assertEquals(LocalTurns.Role.MODEL, withCall.role)
        assertEquals("agenda", withCall.calls.single().name)
        assertEquals(2, withCall.calls.single().args["dias"])
        assertEquals(mapOf("tipo" to "reuniao"), withCall.calls.single().args["filtro"])
        assertEquals(LocalTurns.Role.TOOL, plan.last.role)
        assertEquals("agenda", plan.last.toolName)
        assertEquals("Reunião às 10h", plan.last.text)
    }

    @Test
    fun nothingToAnswerWhenTheModelSpokeLast() {
        assertNull(LocalTurns.plan(JSONArray().put(msg("system", "s")).put(msg("assistant", "x"))))
        assertNull(LocalTurns.plan(JSONArray().put(msg("system", "s"))))
    }

    @Test
    fun toolDeclarationsAndTurnsRoundTrip() {
        val tools = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", "set_timer").put("description", "Timer")
                    .put("parameters", JSONObject().put("type", "object")),
            ),
        )
        val decl = LocalTurns.toolDeclarations(tools).single()
        assertEquals("set_timer", decl.first)
        assertEquals("Timer", JSONObject(decl.second).getString("description"))
        val turn = LocalTurns.turn("**Pronto!**", listOf(LocalTurns.Call("set_timer", mapOf("minutos" to 5.0, "nome" to "chá"))))
        assertEquals("Pronto!", turn.text)
        val args = turn.toolCalls.single().arguments
        assertEquals(5L, args.get("minutos"))
        assertEquals("chá", args.getString("nome"))
        assertTrue(turn.toolCalls.single().id.startsWith("local_"))
        assertEquals("Oi", LocalTurns.clean("<think>hmm</think> Oi"))
    }
}

class LocalTurnsPickTest {
    private fun tools(vararg pairs: Pair<String, String>) = JSONArray().also { array ->
        pairs.forEach { (name, desc) ->
            array.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)))
        }
    }

    private val many = tools(
        "remember" to "Guardar um fato na memória.",
        "recall" to "Buscar na memória.",
        "agenda" to "Compromissos da agenda do celular.",
        "set_timer" to "Criar um timer em minutos.",
        "set_alarm" to "Criar um alarme para um horário.",
        "send_sms" to "Enviar SMS para um contato.",
        "phone_call" to "Ligar para um contato.",
        "weather" to "Previsão do tempo.",
        "look" to "Tirar uma foto com a câmera dos óculos e descrever.",
        "open_app" to "Abrir um aplicativo do celular.",
        "battery" to "Bateria do celular e dos óculos.",
        "notifications" to "Ler as notificações recentes.",
    )

    @Test
    fun picksTheToolsThatMatchTheRequestPlusMemory() {
        val picked = LocalTurns.pickTools(many, "Aura, cria um timer de cinco minutos", 4).map { it.first }
        assertEquals(listOf("remember", "recall", "set_timer", "set_alarm"), picked)
        val photo = LocalTurns.pickTools(many, "o que a câmera está vendo?", 4).map { it.first }
        assertTrue("look" in photo)
        val called = LocalTurns.pickTools(many, "e amanhã?", 3, called = setOf("agenda")).map { it.first }
        assertEquals("agenda", called.first())
        assertEquals(3, called.size)
    }

    @Test
    fun shortListsPassUntouchedAndLastUserTextIsFound() {
        val few = tools("agenda" to "Agenda", "look" to "Foto")
        assertEquals(2, LocalTurns.pickTools(few, "nada a ver", 10).size)
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "liga pra Ana"))
            .put(JSONObject().put("role", "tool").put("content", "ok"))
        assertEquals("liga pra Ana", LocalTurns.lastUserText(messages))
    }
}
