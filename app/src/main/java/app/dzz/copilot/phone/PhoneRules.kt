package app.dzz.copilot.phone

import app.dzz.copilot.voice.AuraCommands
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Contact(val name: String, val number: String)

/** Pure rules behind the phone tools (contacts, numbers, confirmations, dates). Unit tested. */
object PhoneRules {
    /** Digits only, keeping a leading "+". Empty when it does not look like a phone number. */
    fun normalizeNumber(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 3) return ""
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /**
     * Contacts that match [query], best first: exact name, then every word of the query starting
     * a word of the name (so "joão" finds "João Silva" and "mãe" finds "Mãe").
     */
    fun match(query: String, contacts: List<Contact>, limit: Int = 5): List<Contact> {
        val q = AuraCommands.normalize(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ')
        return contacts.mapNotNull { contact ->
            val name = AuraCommands.normalize(contact.name)
            val parts = name.split(' ')
            val score = when {
                name == q -> 100
                words.all { w -> parts.any { it.startsWith(w) } } -> 60 - (parts.size - words.size).coerceAtLeast(0)
                name.contains(q) -> 30
                else -> return@mapNotNull null
            }
            score to contact
        }.sortedByDescending { it.first }
            .distinctBy { (_, c) -> normalizeNumber(c.number).takeLast(9).ifEmpty { c.number.trim().lowercase() } }
            .take(limit)
            .map { it.second }
    }

    /** One clear winner, or null when the caller must ask which one. */
    fun single(query: String, contacts: List<Contact>): Contact? {
        val found = match(query, contacts)
        if (found.size == 1) return found[0]
        val q = AuraCommands.normalize(query)
        return found.firstOrNull { AuraCommands.normalize(it.name) == q }
            ?.takeIf { exact -> found.count { AuraCommands.normalize(it.name) == q } == 1 }
    }

    /** What the tool says back when the user has not confirmed yet: nothing is sent. */
    fun needConfirm(action: String): String =
        "AINDA NÃO FEITO. Pergunte ao usuário em uma frase: $action? " +
            "Só chame esta ferramenta de novo com confirmed=true depois que ele disser sim."

    /**
     * "2026-11-02T15:30", "2026-11-02 15:30", "15:30" (today, or tomorrow if past) or "amanhã 9:00".
     * Returns epoch milliseconds or null.
     */
    fun parseWhen(text: String, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): Long? {
        val raw = text.trim().replace(' ', 'T')
        runCatching { return LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli() }
        runCatching {
            return LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'H:mm")).atZone(zone).toInstant().toEpochMilli()
        }
        val norm = AuraCommands.normalize(text)
        val time = Regex("(\\d{1,2})(?:[ :h](\\d{2}))?").find(norm.replace("amanha", "").replace("hoje", "").trim()) ?: return null
        val hour = time.groupValues[1].toInt()
        val minute = time.groupValues[2].ifEmpty { "0" }.toInt()
        if (hour > 23 || minute > 59) return null
        var day: LocalDate = now.toLocalDate()
        if (norm.contains("amanha")) day = day.plusDays(1)
        var at = LocalDateTime.of(day, LocalTime.of(hour, minute))
        if (!norm.contains("amanha") && !norm.contains("hoje") && at.isBefore(now)) at = at.plusDays(1)
        return at.atZone(zone).toInstant().toEpochMilli()
    }
}
