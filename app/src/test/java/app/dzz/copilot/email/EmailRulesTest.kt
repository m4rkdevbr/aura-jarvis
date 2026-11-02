package app.dzz.copilot.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailRulesTest {
    @Test
    fun knownProvidersAndFallback() {
        val gmail = EmailRules.serversFor("usuario@gmail.com")
        assertEquals("imap.gmail.com", gmail.imap)
        assertEquals(465, gmail.smtpPort)
        assertTrue(gmail.smtpSsl)
        val outlook = EmailRules.serversFor("a@Hotmail.com")
        assertEquals("smtp.office365.com", outlook.smtp)
        assertFalse(outlook.smtpSsl)
        assertEquals("imap.empresa.com.br", EmailRules.serversFor("x@empresa.com.br").imap)
    }

    @Test
    fun addressesAndHtml() {
        assertTrue(EmailRules.isAddress("ana.souza@empresa.com"))
        assertFalse(EmailRules.isAddress("Ana Souza"))
        val text = EmailRules.htmlToText("<style>p{}</style><p>Olá&nbsp;Fábio</p><p>Reunião <b>amanhã</b></p>")
        assertEquals("Olá Fábio\nReunião amanhã", text)
    }
}
