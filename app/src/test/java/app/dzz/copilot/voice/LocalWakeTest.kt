package app.dzz.copilot.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWakeTest {
    @Test fun wakeWordIsFoundInOfflineText() {
        assertTrue(LocalAsr.hasWake("aura tira uma foto"))
        assertTrue(LocalAsr.hasWake("oi aura tudo bem"))
        assertTrue(LocalAsr.hasWake("Áurea, que horas são"))
    }

    @Test fun similarWordsAreNotTheWakeWord() {
        assertFalse(LocalAsr.hasWake("laura chegou em casa"))
        assertFalse(LocalAsr.hasWake("agora eu vou embora"))
        assertFalse(LocalAsr.hasWake("a loura ficou lá fora"))
        assertFalse(LocalAsr.hasWake(""))
        assertFalse(LocalAsr.hasWake(null))
    }
}
