package app.dzz.copilot.voice

import app.dzz.copilot.copilot.AuraToolKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real phrases, so a parser change that breaks one is visible. No model involved. */
class CommandEvalTest {
    private val cases = listOf("src/test/resources/eval_commands.tsv", "app/src/test/resources/eval_commands.tsv")
        .map(::File).first { it.exists() }.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line -> line.split('\t').let { it[0] to it[1] } }

    @Test
    fun everyPhraseReachesTheExpectedAction() {
        val wrong = cases.mapNotNull { (phrase, expected) ->
            val actual = AuraCommands.parse("Aura $phrase")?.action?.name ?: "NENHUM"
            if (actual == expected) null else "\"$phrase\": esperado $expected, veio $actual"
        }
        assertEquals("Erros em ${cases.size} frases:\n" + wrong.joinToString("\n"), emptyList<String>(), wrong)
    }

    @Test
    fun plainConversationSkipsToolRouting() {
        val plain = listOf("qual a capital da França", "me conta uma piada", "quem escreveu dom casmurro", "você gosta de música")
        plain.forEach { assertTrue(it, !AuraToolKit.mightNeedTool(AuraCommands.normalize(it))) }
    }

    @Test
    fun toolRequestsTheParserMissesStillReachTheModel() {
        val tools = listOf("dá uma olhada aqui", "me mostra a câmera", "manda um whatsapp", "liga o modo tradução")
        tools.forEach { assertTrue(it, AuraToolKit.mightNeedTool(AuraCommands.normalize(it))) }
    }

    @Test
    fun spokenFactKeepsItsAccents() {
        assertEquals("o João prefere reunião de manhã", AuraCommands.rememberedText("Aura, lembra que o João prefere reunião de manhã."))
    }
}
