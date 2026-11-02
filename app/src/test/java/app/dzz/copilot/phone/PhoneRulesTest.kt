package app.dzz.copilot.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class PhoneRulesTest {
    private val contacts = listOf(
        Contact("João Silva", "+55 11 98888-1111"),
        Contact("João Pedro", "11 97777-2222"),
        Contact("Mãe", "11 96666-3333"),
        Contact("Mãe", "+5511966663333"),
        Contact("Ana Paula Souza", "21 95555-4444"),
    )

    @Test
    fun numbersKeepDigitsAndPlus() {
        assertEquals("+5511988881111", PhoneRules.normalizeNumber("+55 (11) 98888-1111"))
        assertEquals("", PhoneRules.normalizeNumber("ana"))
    }

    @Test
    fun matchesByWordsWithoutAccents() {
        assertEquals("Mãe", PhoneRules.single("mae", contacts)?.name)
        assertEquals("Ana Paula Souza", PhoneRules.single("ana souza", contacts)?.name)
        // Two Joãos: ask which one.
        assertNull(PhoneRules.single("joao", contacts))
        assertEquals(2, PhoneRules.match("joão", contacts).size)
        assertEquals("João Pedro", PhoneRules.single("joao pedro", contacts)?.name)
        assertTrue(PhoneRules.match("carlos", contacts).isEmpty())
    }

    @Test
    fun confirmationSaysNothingWasSent() {
        val text = PhoneRules.needConfirm("mandar SMS para Mãe dizendo \"oi\"")
        assertTrue(text.startsWith("AINDA NÃO FEITO"))
        assertTrue(text.contains("confirmed=true"))
    }

    @Test
    fun parsesDates() {
        val zone = ZoneId.of("UTC")
        val now = LocalDateTime.of(2026, 11, 1, 18, 0)
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(at(2026, 11, 2, 15, 30), PhoneRules.parseWhen("2026-11-02T15:30", now, zone))
        assertEquals(at(2026, 11, 2, 9, 5), PhoneRules.parseWhen("2026-11-02 9:05", now, zone))
        assertEquals(at(2026, 11, 1, 20, 0), PhoneRules.parseWhen("20:00", now, zone))
        // A time already past today means tomorrow.
        assertEquals(at(2026, 11, 2, 9, 0), PhoneRules.parseWhen("9:00", now, zone))
        assertEquals(at(2026, 11, 2, 14, 0), PhoneRules.parseWhen("amanhã 14h", now, zone))
        assertNull(PhoneRules.parseWhen("qualquer dia", now, zone))
    }
}
