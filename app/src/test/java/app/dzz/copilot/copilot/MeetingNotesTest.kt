package app.dzz.copilot.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingNotesTest {
    @Test
    fun splitsSummaryAndTasks() {
        val brief = MeetingNotes.parse(
            """
            RESUMO:
            Aula sobre o contrato.
            TAREFAS:
            - Revisar a cláusula 4
            - Enviar o anexo
            """.trimIndent(),
        )
        assertEquals("Aula sobre o contrato.", brief.summary)
        assertEquals(listOf("Revisar a cláusula 4", "Enviar o anexo"), brief.tasks)
    }
}
