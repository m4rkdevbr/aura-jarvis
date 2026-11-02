package app.dzz.copilot.agenda

import app.dzz.copilot.data.TaskItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedTask(
    val title: String,
    val dueAt: Long?,
)

object TaskText {
    private val action = Regex(
        "(?i)\\b(preciso|precisamos|vou|vamos|tarefa|combin\\w*|lembrar|entregar|enviar|revisar|estudar|pagar|comprar|ligar|reuni[aã]o|prazo|amanh[aã]|deadline)\\b",
    )

    fun parse(raw: String, now: Long = System.currentTimeMillis()): ParsedTask? {
        var text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        val lowerStart = text.lowercase(Locale.ROOT)
        val prefixes = listOf(
            "me lembre de ",
            "me lembra de ",
            "me lembra ",
            "lembrete para ",
            "lembrete de ",
            "lembrar de ",
            "tarefa ",
            "adicionar ",
            "anota ",
            "anotar ",
        )
        for (prefix in prefixes) {
            if (lowerStart.startsWith(prefix)) {
                text = text.substring(prefix.length).trim()
                break
            }
        }
        if (text.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val base = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val due = findDue(text, base)
        val title = stripSchedule(text).ifBlank { text }
        return ParsedTask(title = title, dueAt = due)
    }

    fun extractActionLines(lines: List<String>): List<String> {
        return lines.map { it.trim() }
            .filter { it.length >= 4 && action.containsMatchIn(it) }
            .distinct()
    }

    fun briefing(open: List<TaskItem>, now: Long = System.currentTimeMillis()): String {
        if (open.isEmpty()) return "Você não tem tarefas abertas."
        val overdue = open.filter { it.dueAt != null && it.dueAt < now }
            .sortedBy { it.dueAt }
        val today = open.filter { it.dueAt != null && sameDay(it.dueAt, now) && it.dueAt >= now }
            .sortedBy { it.dueAt }
        val upcoming = open.filter { it.dueAt != null && !sameDay(it.dueAt, now) && it.dueAt > now }
            .sortedBy { it.dueAt }
            .take(3)
        return buildString {
            append("Você tem ${open.size} ${if (open.size == 1) "tarefa aberta" else "tarefas abertas"}. ")
            if (overdue.isNotEmpty()) {
                append("${overdue.size} atrasada${if (overdue.size == 1) "" else "s"}: ")
                append(overdue.take(3).joinToString { it.title })
                append(". ")
            }
            if (today.isNotEmpty()) {
                append("Para hoje: ")
                append(today.joinToString { it.title })
                append(". ")
            }
            if (upcoming.isNotEmpty()) {
                append("Depois: ")
                append(upcoming.joinToString { it.title })
                append(".")
            }
            if (overdue.isEmpty() && today.isEmpty() && upcoming.isEmpty()) {
                append("Nenhuma tem horário marcado.")
            }
        }.trim()
    }

    fun formatWhen(epochMillis: Long?): String {
        if (epochMillis == null) return "sem horário"
        val formatter = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault())
        return formatter.format(Instant.ofEpochMilli(epochMillis))
    }

    private fun sameDay(epochMillis: Long, now: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return day == today
    }

    private fun findDue(text: String, base: ZonedDateTime): Long? {
        val lower = text.lowercase(Locale.ROOT)
        val relativeMinutes = Regex("em\\s+(\\d{1,3})\\s+min").find(lower)
        if (relativeMinutes != null) {
            val minutes = relativeMinutes.groupValues[1].toLong()
            return base.plusMinutes(minutes).toInstant().toEpochMilli()
        }
        val relativeHours = Regex("em\\s+(\\d{1,2})\\s+hora").find(lower)
        if (relativeHours != null) {
            val hours = relativeHours.groupValues[1].toLong()
            return base.plusHours(hours).toInstant().toEpochMilli()
        }
        val clock = Regex("(?:às|as)\\s*(\\d{1,2})(?:[:h](\\d{2}))?").find(lower)
            ?: Regex("(\\d{1,2})\\s*h(?:(\\d{2}))?").find(lower)
        val hour = clock?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minute = clock?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        if (hour == null || hour !in 0..23 || minute !in 0..59) {
            if (lower.contains("amanhã") || lower.contains("amanha")) {
                return null
            }
            return null
        }
        var day: LocalDate = base.toLocalDate()
        if (lower.contains("amanhã") || lower.contains("amanha")) {
            day = day.plusDays(1)
        } else if (!lower.contains("hoje")) {
            val candidate = day.atTime(hour, minute).atZone(base.zone)
            if (candidate.isBefore(base.minusMinutes(1))) day = day.plusDays(1)
        }
        return day.atTime(hour, minute).atZone(base.zone).toInstant().toEpochMilli()
    }

    private fun stripSchedule(text: String): String {
        return text
            .replace(Regex("(?i)\\b(hoje|amanhã|amanha)\\b"), " ")
            .replace(Regex("(?i)\\bem\\s+\\d{1,3}\\s+minutos?\\b"), " ")
            .replace(Regex("(?i)\\bem\\s+\\d{1,2}\\s+horas?\\b"), " ")
            .replace(Regex("(?i)\\b(às|as)\\s*\\d{1,2}([:h]\\d{2})?\\b"), " ")
            .replace(Regex("(?i)\\b\\d{1,2}\\s*h\\d{0,2}\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '.', '-')
    }
}
