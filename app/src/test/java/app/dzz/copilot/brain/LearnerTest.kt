package app.dzz.copilot.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerTest {
    @Test
    fun parsesKindsAndSkipsNoise() {
        val items = Learner.parse(
            """
            FATO: O usuário trabalha com educação digital
            - PREFERÊNCIA: Prefere respostas bem curtas
            LIÇÃO: Quando ele diz "toca", quer música no Spotify.
            comentário qualquer
            NADA
            """.trimIndent(),
        )
        assertEquals(3, items.size)
        assertEquals(MemoryStore.KIND_FACT, items[0].kind)
        assertEquals(MemoryStore.KIND_PREFERENCE, items[1].kind)
        assertEquals(MemoryStore.KIND_LESSON, items[2].kind)
        assertEquals("Quando ele diz \"toca\", quer música no Spotify", items[2].text)
    }

    @Test
    fun dropsCutOffDuplicates() {
        val items = Learner.parse("FATO: Gosta de automação de tarefas e de aprender tecnologias novas\nFATO: Gosta de automação de tarefas e de aprender")
        assertEquals(1, items.size)
        val memory = Memory(createTempDir())
        memory.learn(items)
        assertEquals(0, memory.learn(listOf(Learner.Item(MemoryStore.KIND_FACT, "Gosta de automação de tarefas"))))
    }

    @Test
    fun neverKeepsSecrets() {
        val items = Learner.parse("FATO: A senha do banco é 1234\nFATO: O CPF dele é 123.456.789-00\nFATO: Mora em Curitiba")
        assertEquals(listOf("Mora em Curitiba"), items.map { it.text })
    }

    @Test
    fun capsItemsAndDedupes() {
        val reply = (1..9).joinToString("\n") { "FATO: fato número $it sobre ele" } + "\nFATO: fato número 1 sobre ele"
        assertEquals(Learner.MAX_ITEMS, Learner.parse(reply).size)
    }

    @Test
    fun transcriptNeedsEnoughUserLines() {
        assertNull(Learner.transcript(listOf("voce" to "oi", "aura" to "olá")))
        val t = Learner.transcript(listOf("voce" to "oi", "aura" to "olá", "voce" to "prefiro curto"))
        assertTrue(t!!.contains("Usuário: prefiro curto"))
    }

    @Test
    fun memoryKeepsKinds() {
        val dir = createTempDir()
        val memory = Memory(dir)
        assertEquals(2, memory.learn(listOf(Learner.Item(MemoryStore.KIND_PREFERENCE, "Prefere respostas curtas"), Learner.Item(MemoryStore.KIND_FACT, "Mora em Curitiba"))))
        assertEquals(0, memory.learn(listOf(Learner.Item(MemoryStore.KIND_FACT, "Mora em Curitiba"))))
        assertEquals(listOf("Prefere respostas curtas"), memory.facts.byKind(MemoryStore.KIND_PREFERENCE).map { it.text })
        dir.deleteRecursively()
    }
}
