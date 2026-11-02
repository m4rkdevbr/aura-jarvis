package app.dzz.copilot.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramRulesTest {
    private fun msg(chat: Long, text: String, photo: String? = null) = TgMessage(1, chat, "Fábio", text, photo)

    @Test fun pairsOnlyWithTheRightCode() {
        assertEquals(TgAction.Paired(42), TelegramRules.decide(msg(42, "/start 123456"), 0L, "123456"))
        assertTrue(TelegramRules.decide(msg(42, "/start 000000"), 0L, "123456") is TgAction.Reply)
        assertTrue(TelegramRules.decide(msg(42, "/start"), 0L, null) is TgAction.Reply)
    }

    @Test fun strangersAreIgnoredOncePaired() {
        assertEquals(TgAction.Ignore, TelegramRules.decide(msg(7, "/start 123456"), 42L, "123456"))
        assertEquals(TgAction.Ignore, TelegramRules.decide(msg(7, "oi"), 42L, null))
        assertEquals(TgAction.Ignore, TelegramRules.decide(msg(42, "oi"), 0L, null))
    }

    @Test fun ownerMessagesGoToTheBrain() {
        val m = msg(42, "", photo = "abc")
        assertEquals(TgAction.Ask(m), TelegramRules.decide(m, 42L, null))
        assertEquals(TgAction.Ignore, TelegramRules.decide(msg(42, "  "), 42L, null))
    }

    @Test fun parsesUpdatesAndSkipsNonMessages() {
        val body = """{"ok":true,"result":[
          {"update_id":10,"message":{"chat":{"id":42},"from":{"first_name":"F"},"text":"oi"}},
          {"update_id":11,"edited_message":{"chat":{"id":42},"text":"x"}},
          {"update_id":12,"message":{"chat":{"id":42},"caption":"isso","photo":[{"file_id":"s"},{"file_id":"L"}]}}
        ]}"""
        val u = TelegramRules.parseUpdates(body, 5)
        assertEquals(13L, u.nextOffset)
        assertEquals(2, u.messages.size)
        assertEquals("oi", u.messages[0].text)
        assertEquals("L", u.messages[1].photoId)
        assertEquals("isso", u.messages[1].text)
        assertEquals(5L, TelegramRules.parseUpdates("lixo", 5).nextOffset)
    }

    @Test fun chunksLongText() {
        assertEquals(3, TelegramRules.chunks("a".repeat(9_000)).size)
        assertEquals(listOf("oi"), TelegramRules.chunks("oi"))
    }
}
