package app.dzz.copilot.brain

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

class AgentTest {
    @Test
    fun runsToolThenAnswers() = runBlocking {
        val seen = mutableListOf<JSONArray>()
        val model = ChatModel { messages, _ ->
            seen += JSONArray(messages.toString())
            val last = messages.getJSONObject(messages.length() - 1)
            when {
                seen.size == 1 -> ModelTurn("", listOf(ToolCall("c1", "agenda", JSONObject())), JSONObject())
                seen.size == 2 -> {
                    assertEquals("tool", last.getString("role"))
                    assertEquals("10h reunião", last.getString("content"))
                    ModelTurn("Você tem reunião às 10h.", emptyList(), JSONObject())
                }
                else -> ModelTurn("Amanhã está livre.", emptyList(), JSONObject())
            }
        }
        val tools = ToolRegistry(listOf(SimpleTool("agenda", "Agenda") { "10h reunião" }))
        val agent = Agent(model, tools, { "sys" })
        val reply = agent.run("o que tenho hoje?")
        assertEquals("Você tem reunião às 10h.", reply?.text)
        assertEquals(listOf("agenda"), reply?.toolsUsed)
        // History keeps only plain text: next turn sees user + assistant, not tool traffic.
        agent.run("e amanhã?")
        val third = seen.last()
        assertEquals(4, third.length()) // system, user, assistant, user
    }

    @Test
    fun unknownToolAndFailureGoBackToModel() = runBlocking {
        var round = 0
        val model = ChatModel { messages, _ ->
            round++
            if (round == 1) {
                ModelTurn("", listOf(ToolCall("x", "nao_existe", JSONObject()), ToolCall("y", "quebra", JSONObject())), JSONObject())
            } else {
                val n = messages.length()
                assertTrue(messages.getJSONObject(n - 2).getString("content").contains("não existe"))
                assertTrue(messages.getJSONObject(n - 1).getString("content").contains("boom"))
                ModelTurn("Não consegui.", emptyList(), JSONObject())
            }
        }
        val tools = ToolRegistry(listOf(SimpleTool("quebra", "x") { error("boom") }))
        assertEquals("Não consegui.", Agent(model, tools, { "" }).run("faz")?.text)
    }

    @Test
    fun nullModelGivesNull() = runBlocking {
        assertNull(Agent({ _, _ -> null }, ToolRegistry(), { "" }).run("oi"))
    }

    @Test
    fun parsesOpenAiToolCalls() {
        val body = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"remember","arguments":"{\"fact\":\"filha Ana\"}"}}]}}]}"""
        val turn = Agent.parseOpenAi(body)!!
        assertEquals("remember", turn.toolCalls.single().name)
        assertEquals("filha Ana", turn.toolCalls.single().arguments.getString("fact"))
    }

    @Test
    fun memoryRemembersAndRecalls() {
        val dir = Files.createTempDirectory("mem").toFile()
        var t = 0L
        val memory = Memory(dir) { ++t }
        assertTrue(memory.remember("A filha do Paulo se chama Ana"))
        assertTrue(!memory.remember("a filha do paulo se chama ana"))
        memory.remember("Prefere reuniões depois das 10h")
        assertEquals("A filha do Paulo se chama Ana", Memory(dir).recall("como chama a filha").first().text)
        assertEquals(1, memory.forget("reunioes"))
    }

    @Test
    fun skillsParseFrontMatter() {
        val text = "---\nname: status\ndescription: Resumo de dinheiro, tempo e pessoas.\n---\n# Status\nFale o mais urgente primeiro."
        val library = SkillLibrary { listOf("status.md" to text, "ruim.md" to "sem cabeçalho") }
        assertEquals(1, library.all().size)
        assertTrue(library.find("STATUS")!!.body.contains("urgente"))
        assertEquals("- status: Resumo de dinheiro, tempo e pessoas.", library.menu())
    }

    @Test
    fun systemPromptCarriesMemoryAndSkills() {
        val dir = Files.createTempDirectory("brain").toFile()
        val memory = Memory(dir)
        memory.setProfile("Fábio, dono da Aura.")
        memory.remember("Gosta de café sem açúcar")
        val skills = SkillLibrary { listOf("s.md" to "---\nname: status\ndescription: d\n---\ncorpo") }
        val brain = Brain({ _, _ -> null }, memory, skills, null, { "Óculos conectados, bateria 80%" }) {
            ZonedDateTime.of(2026, 11, 1, 9, 0, 0, 0, ZoneId.of("America/Sao_Paulo"))
        }
        val prompt = brain.systemPrompt()
        listOf("Fábio, dono da Aura.", "café sem açúcar", "bateria 80%", "- status: d", "01/11/2026 09:00")
            .forEach { assertTrue(it, prompt.contains(it)) }
        assertEquals(listOf("remember", "recall", "forget", "load_skill"), brain.tools.names())
    }
}
