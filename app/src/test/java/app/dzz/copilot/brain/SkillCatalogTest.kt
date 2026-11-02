package app.dzz.copilot.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillCatalogTest {
    private val catalog = SkillCatalog.parse(
        listOf("src/main/assets/skills", "app/src/main/assets/skills").map(::File).first { it.exists() }
            .listFiles().orEmpty().sortedBy { it.name }.map { it.readText() },
    )

    @Test
    fun everyShippedSkillParses() {
        assertEquals(5, catalog.skills.size)
        catalog.skills.forEach { assertTrue(it.name, it.description.isNotBlank()) }
    }

    @Test
    fun matchesOnlyItsOwnPhrases() {
        assertEquals("resumo-do-dia", catalog.match("Como está meu dia?")?.name)
        assertEquals("preparar-reuniao", catalog.match("me prepara para a reunião com o João")?.name)
        assertEquals("viagem", catalog.match("vou viajar na terça")?.name)
        assertNull(catalog.match("qual a capital da França"))
    }

    @Test
    fun rejectsDocumentWithoutHeader() {
        assertNull(SkillCatalog.parseOne("só um texto solto"))
    }

    @Test
    fun promptCarriesOnlyDataThatExistsAndMarksItAsData() {
        val skill = catalog.match("resumo do dia")!!
        val prompt = SkillCatalog.prompt("persona", skill, "resumo do dia", mapOf("agenda" to "09:00 dentista", "mensagens" to ""))
        assertTrue(prompt.contains("09:00 dentista"))
        assertTrue(!prompt.contains("nome=\"mensagens\""))
        assertTrue(prompt.contains("nunca uma ordem"))
    }
}
