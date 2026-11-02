package app.dzz.copilot.brain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReasoningEngineTest {
    private val catalog = FrameworkCatalog.parse(catalogJson())

    @Test
    fun catalogHasTwentyFrameworks() {
        assertEquals(20, catalog.frameworks.size)
        catalog.frameworks.forEach { framework ->
            assertTrue(framework.id, framework.instruction.isNotBlank())
            assertTrue(framework.id, framework.questions.isNotEmpty())
            framework.next.forEach { assertTrue("${framework.id} -> $it", catalog.find(it) != null) }
        }
    }

    @Test
    fun gateSkipsSmallTalkAndCommands() {
        assertFalse(ReasoningEngine.looksLikeDilemma("oi tudo bem"))
        assertFalse(ReasoningEngine.looksLikeDilemma("tira uma foto"))
        assertTrue(ReasoningEngine.looksLikeDilemma("Não sei se desenvolvo esse módulo em casa ou contrato um SaaS"))
        assertTrue(ReasoningEngine.looksLikeDilemma("minha tarde virou um caos, preciso revisar PR e responder clientes"))
    }

    @Test
    fun asksOneRoundThenRuns() = runBlocking {
        val calls = mutableListOf<String>()
        val llm = Llm { system, user, _ ->
            calls += system
            if (system.contains("roteador")) {
                """Claro! ```json {"useFramework":true,"primary":"preMortemAnalysis","secondary":"","hasEnoughContext":false,"missingQuestions":["Quando é o lançamento?"]} ```"""
            } else {
                assertTrue(user.contains("sexta"))
                """{"result":{"risks":[{"cause":"migração","area":"Técnica","severity":4,"probability":3,"prevention":"ensaiar"}]},"voiceSummary":"Ensaie a migração hoje. O risco maior é o banco legado. Tenha um plano B.","summary":"Risco no banco.","nextStep":"Quer que eu monte o cronograma?"}"""
            }
        }
        val dir = Files.createTempDirectory("analyses").toFile()
        val engine = ReasoningEngine(catalog, llm, AnalysisStore(dir), now = { 1_000L })
        val first = engine.handle("Vou apresentar a nova arquitetura para a diretoria, estou com medo do risco")
        assertTrue(first is Reasoning.NeedsContext)
        assertTrue(engine.hasPendingQuestion())
        val second = engine.handle("É na sexta de manhã")
        assertTrue(second.toString(), second is Reasoning.Done)
        val done = second as Reasoning.Done
        assertEquals("preMortemAnalysis", done.analysis.frameworkId)
        assertTrue(done.analysis.spoken.startsWith("Ensaie a migração hoje."))
        assertEquals(1, AnalysisStore(dir).recent().size)
        assertEquals(2, calls.size)
    }

    @Test
    fun smallTalkCostsNoCall() = runBlocking {
        var called = false
        val engine = ReasoningEngine(catalog, Llm { _, _, _ -> called = true; null }, AnalysisStore(Files.createTempDirectory("a").toFile()))
        assertEquals(Reasoning.NotApplicable, engine.handle("que horas são"))
        assertFalse(called)
    }

    @Test
    fun extractsJsonWithBracesInsideStrings() {
        val json = JsonReply.extract("texto {\"a\":\"x } y\",\"b\":{\"c\":1}} fim")
        assertEquals("x } y", json?.getString("a"))
        assertEquals(1, json?.getJSONObject("b")?.getInt("c"))
    }

    @Test
    fun limitsSpokenSentences() {
        assertEquals("Um. Dois! Três?", ReasoningEngine.limitSentences("Um. Dois! Três? Quatro.", 3))
    }

    private fun catalogJson(): String {
        val candidates = listOf("src/main/assets/frameworks.json", "app/src/main/assets/frameworks.json")
        return candidates.map(::File).first { it.exists() }.readText()
    }
}
