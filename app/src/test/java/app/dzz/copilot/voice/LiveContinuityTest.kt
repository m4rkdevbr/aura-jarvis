package app.dzz.copilot.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveContinuityTest {
    private val min = 60_000L

    @Test
    fun renewsOnlyAtAQuietMomentBeforeTheLimit() {
        assertFalse(LiveContinuity.shouldRenew(10 * min, 5_000, talking = false, toolsRunning = 0))
        assertFalse(LiveContinuity.shouldRenew(51 * min, 5_000, talking = true, toolsRunning = 0))
        assertFalse(LiveContinuity.shouldRenew(51 * min, 500, talking = false, toolsRunning = 0))
        assertFalse(LiveContinuity.shouldRenew(51 * min, 5_000, talking = false, toolsRunning = 1))
        assertTrue(LiveContinuity.shouldRenew(51 * min, 2_000, talking = false, toolsRunning = 0))
        // Close to the hard limit it renews even while she talks, but waits briefly for a tool.
        assertTrue(LiveContinuity.shouldRenew(56 * min, 0, talking = true, toolsRunning = 0))
        assertFalse(LiveContinuity.shouldRenew(56 * min, 0, talking = false, toolsRunning = 1))
        assertTrue(LiveContinuity.shouldRenew(57 * min + 1, 0, talking = false, toolsRunning = 1))
    }

    @Test
    fun continuationKeepsTheLastTurnsInOrder() {
        val turns = (1..40).map { "Usuário: frase número $it com algum conteúdo para ocupar espaço" }
        val kept = LiveContinuity.recent(turns, 500)
        assertTrue(kept.isNotEmpty())
        assertEquals(turns.last(), kept.last())
        assertTrue(kept.joinToString("\n").length <= 500)
        assertEquals(turns.subList(turns.size - kept.size, turns.size), kept)
        val text = LiveContinuity.continuation(listOf("Usuário: oi", "Aura: oi, pode falar"))
        assertTrue(text.contains("Não cumprimente"))
        assertTrue(text.endsWith("Usuário: oi\nAura: oi, pode falar"))
        assertEquals("", LiveContinuity.continuation(emptyList()))
        assertEquals(null, LiveContinuity.line("Aura", "   "))
        assertEquals("Aura: tudo bem", LiveContinuity.line("Aura", " tudo \n bem "))
    }

    @Test
    fun setupAsksForResumptionAndCarriesTheHandle() {
        val fresh = JSONObject(LiveProtocol.setup("m", "Leda", "s", JSONArray(), resumption = true)).getJSONObject("setup")
        assertEquals(0, fresh.getJSONObject("sessionResumption").length())
        val resumed = JSONObject(LiveProtocol.setup("m", "Leda", "s", JSONArray(), resumption = true, resumeHandle = "abc")).getJSONObject("setup")
        assertEquals("abc", resumed.getJSONObject("sessionResumption").getString("handle"))
        val off = JSONObject(LiveProtocol.setup("m", "Leda", "s", JSONArray())).getJSONObject("setup")
        assertFalse(off.has("sessionResumption"))
    }

    @Test
    fun parsesResumptionHandlesAndGoAway() {
        val events = LiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"h1","resumable":true}}""")
        assertEquals(listOf(LiveEvent.Resume("h1")), events)
        assertTrue(LiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"h2","resumable":false}}""").isEmpty())
        assertTrue(LiveProtocol.parse("""{"sessionResumptionUpdate":{"resumable":true}}""").isEmpty())
        assertEquals(listOf(LiveEvent.GoAway("50s")), LiveProtocol.parse("""{"goAway":{"timeLeft":"50s"}}"""))
    }
}
