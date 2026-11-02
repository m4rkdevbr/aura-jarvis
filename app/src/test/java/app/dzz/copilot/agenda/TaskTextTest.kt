package app.dzz.copilot.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTextTest {
    @Test
    fun parsesTomorrowAtNine() {
        val parsed = TaskText.parse("lembrar de comprar leite amanhã às 9")
        assertNotNull(parsed)
        assertEquals("comprar leite", parsed!!.title)
        assertNotNull(parsed.dueAt)
    }

    @Test
    fun extractsActionLines() {
        val lines = TaskText.extractActionLines(listOf("bom dia", "preciso enviar o relatório"))
        assertEquals(listOf("preciso enviar o relatório"), lines)
    }

    @Test
    fun briefingMentionsOpenTasks() {
        val text = TaskText.briefing(emptyList())
        assertTrue(text.contains("não tem"))
    }
}
