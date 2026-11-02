package app.dzz.copilot.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuraCommandsTest {
    @Test
    fun wakeAloneDoesNotRunACommand() {
        assertEquals(AuraAction.Wake, AuraCommands.parse("Aura")?.action)
        assertEquals(AuraAction.Wake, AuraCommands.parse("oi, Áura")?.action)
    }

    @Test
    fun ignoresSpeechWithoutTheWakeWord() {
        assertNull(AuraCommands.parse("tira uma foto"))
    }

    @Test
    fun photoVideoAndStop() {
        assertEquals(AuraAction.Photo, AuraCommands.parse("Aura, tira uma foto")?.action)
        assertEquals(AuraAction.VideoStart, AuraCommands.parse("aura grava um vídeo")?.action)
        assertEquals(AuraAction.VideoStop, AuraCommands.parse("Aura, parar o vídeo")?.action)
        assertEquals(AuraAction.AudioStop, AuraCommands.parse("aura parar o áudio")?.action)
    }

    @Test
    fun armedWindowAcceptsTheNextPhrase() {
        assertEquals(AuraAction.Battery, AuraCommands.parse("qual a bateria", armed = true)?.action)
        assertEquals(AuraAction.Find, AuraCommands.parse("onde estão os óculos", armed = true)?.action)
    }

    @Test
    fun lookImportAndTaskKeepThePhrase() {
        assertEquals(AuraAction.Look, AuraCommands.parse("Aura o que está acontecendo")?.action)
        assertEquals(AuraAction.Look, AuraCommands.parse("Aura me diga o que você está vendo")?.action)
        assertEquals("你好", AuraCommands.spokenPhrase("Aura 你好", armed = false))
        assertEquals(AuraAction.Import, AuraCommands.parse("Aura importar")?.action)
        val task = AuraCommands.parse("Aura me lembre de comprar leite amanhã às 9")
        assertEquals(AuraAction.Task, task?.action)
        assertEquals("me lembre de comprar leite amanha as 9", task?.detail)
    }

    @Test
    fun horaDoesNotMatchAgora() {
        assertEquals(AuraAction.Time, AuraCommands.parse("Aura, que horas")?.action)
        assertNull(AuraCommands.parse("agora", armed = true))
    }

    @Test
    fun explainCommandsStayApartFromTheLocalLook() {
        assertEquals(AuraAction.Explain, AuraCommands.parse("Aura, explica")?.action)
        assertEquals(AuraAction.ExplainText, AuraCommands.parse("Aura explica o texto")?.action)
        assertEquals(AuraAction.Look, AuraCommands.parse("Aura o que está acontecendo")?.action)
    }

    @Test
    fun searchAgendaAndMessages() {
        val search = AuraCommands.parse("Aura pesquisa previsão do tempo")
        assertEquals(AuraAction.Search, search?.action)
        assertEquals("previsao do tempo", search?.detail)
        assertEquals(AuraAction.Agenda, AuraCommands.parse("Aura, minha agenda")?.action)
        assertEquals(AuraAction.Messages, AuraCommands.parse("Aura mensagens")?.action)
        assertEquals(AuraAction.Find, AuraCommands.parse("Aura, onde estão os óculos")?.action)
    }

    @Test
    fun misheardWakeStillOpensTheCommand() {
        assertEquals(AuraAction.Wake, AuraCommands.parse("Áurea")?.action)
        assertEquals(AuraAction.Photo, AuraCommands.parse("hora tira uma foto")?.action)
        assertNull(AuraCommands.parse("que horas"))
        assertEquals(AuraAction.Time, AuraCommands.parse("Aura, que horas")?.action)
    }

    @Test
    fun openAndImageKeepTheSubject() {
        assertEquals("clima de brasilia", AuraCommands.parse("Aura abre clima de brasília")?.detail)
        assertEquals(AuraAction.Open, AuraCommands.parse("Aura abre clima de brasília")?.action)
        assertEquals(AuraAction.Imagine, AuraCommands.parse("Aura cria uma imagem de um farol")?.action)
        assertEquals("um farol", AuraCommands.parse("Aura cria uma imagem de um farol")?.detail)
    }

    @Test
    fun translateAndIdentify() {
        assertEquals(AuraAction.Translate, AuraCommands.parse("Aura traduz")?.action)
        assertEquals(AuraAction.Translate, AuraCommands.parse("Aura traduz good morning")?.action)
        assertEquals("good morning", AuraCommands.parse("Aura traduz good morning")?.detail)
        assertEquals(AuraAction.TranslateStop, AuraCommands.parse("Aura parar tradução")?.action)
        assertEquals(AuraAction.Identify, AuraCommands.parse("Aura o que é isso")?.action)
        assertEquals(AuraAction.Identify, AuraCommands.parse("Aura lê a placa")?.action)
        assertEquals("placa ou letreiro", AuraCommands.parse("Aura lê a placa")?.detail)
        assertEquals(AuraAction.ExplainText, AuraCommands.parse("Aura explica o texto")?.action)
    }

    @Test
    fun armedDialogueAcceptsFollowUpCommands() {
        assertEquals(AuraAction.Photo, AuraCommands.parse("tira uma foto", armed = true)?.action)
        assertEquals(AuraAction.Battery, AuraCommands.parse("bateria", armed = true)?.action)
        assertNull(AuraCommands.parse("como vai o clima em brasilia", armed = true))
    }
}
