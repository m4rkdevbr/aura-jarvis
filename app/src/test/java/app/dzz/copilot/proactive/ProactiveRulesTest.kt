package app.dzz.copilot.proactive

import app.dzz.copilot.phone.InboxItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ProactiveRulesTest {
    @Test
    fun quietHoursCrossMidnight() {
        assertTrue(ProactiveRules.quiet(LocalDateTime.of(2026, 11, 1, 23, 0)))
        assertTrue(ProactiveRules.quiet(LocalDateTime.of(2026, 11, 2, 6, 59)))
        assertFalse(ProactiveRules.quiet(LocalDateTime.of(2026, 11, 2, 7, 0)))
        assertFalse(ProactiveRules.quiet(LocalDateTime.of(2026, 11, 2, 21, 59)))
    }

    @Test
    fun briefOncePerMorning() {
        val nine = LocalDateTime.of(2026, 11, 2, 9, 0)
        val today = nine.toLocalDate().toEpochDay()
        assertTrue(ProactiveRules.briefDue(nine, 8, today - 1))
        assertFalse(ProactiveRules.briefDue(nine, 8, today))
        assertFalse(ProactiveRules.briefDue(nine.withHour(7), 8, today - 1))
        assertFalse(ProactiveRules.briefDue(nine.withHour(13), 8, today - 1))
    }

    @Test
    fun eventsWithinTenMinutesOnce() {
        val now = 1_000_000L
        val soon = UpcomingEvent(1, "Reunião", now + 5 * 60_000)
        val later = UpcomingEvent(2, "Almoço", now + 25 * 60_000)
        assertEquals(listOf(soon), ProactiveRules.dueEvents(listOf(soon, later), now, emptySet()))
        assertTrue(ProactiveRules.dueEvents(listOf(soon), now, setOf(ProactiveRules.key(soon))).isEmpty())
    }

    @Test
    fun messagesAndBattery() {
        val wa = InboxItem("k", "com.whatsapp", "WhatsApp", "Ana", "Chego em 5 min", 0)
        assertTrue(ProactiveRules.announceable(wa, "app.dzz.copilot"))
        assertFalse(ProactiveRules.announceable(wa.copy(text = "3 novas mensagens"), "app.dzz.copilot"))
        assertFalse(ProactiveRules.announceable(wa.copy(packageName = "com.google.android.gm"), "app.dzz.copilot"))
        assertEquals("Mensagem de Ana no WhatsApp: Chego em 5 min. Se quiser responder, é só me dizer.", ProactiveRules.spokenMessage(wa))
        assertEquals(15, ProactiveRules.batteryWarning(14, 101))
        assertNull(ProactiveRules.batteryWarning(12, 15))
        assertEquals(5, ProactiveRules.batteryWarning(4, 15))
        assertNull(ProactiveRules.batteryWarning(null, 101))
    }
}
