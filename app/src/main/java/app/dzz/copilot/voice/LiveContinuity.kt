package app.dzz.copilot.voice

/**
 * Keeps a live talk going past the server's session limit (about 58 minutes) and through network
 * drops: the socket is swapped for a new one at a quiet moment, carrying either the server's
 * resumption handle or, when that fails, the recent turns in the instructions. Pure, so it is tested.
 */
object LiveContinuity {
    /** Renew at the first quiet moment after this long on one socket. */
    const val RENEW_AFTER_MS = 50L * 60_000
    /** Renew even mid-conversation after this long (the server closes at about 58 minutes). */
    const val FORCE_AFTER_MS = 56L * 60_000
    /** Quiet needed before a planned renewal, so it never cuts a sentence. */
    const val QUIET_MS = 1_500L
    /** Reconnects in a row without a finished turn before giving up. */
    const val MAX_RECONNECTS = 3
    const val MAX_TURNS = 16
    const val MAX_CHARS = 4_000

    fun shouldRenew(connectedMs: Long, quietMs: Long, talking: Boolean, toolsRunning: Int): Boolean = when {
        connectedMs >= FORCE_AFTER_MS -> toolsRunning == 0 || connectedMs >= FORCE_AFTER_MS + 60_000
        connectedMs >= RENEW_AFTER_MS -> !talking && toolsRunning == 0 && quietMs >= QUIET_MS
        else -> false
    }

    /** One turn for the transcript, or null when nothing was said. */
    fun line(who: String, text: String): String? = text.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }?.let { "$who: $it" }

    /** Last turns that fit [maxChars], oldest first. */
    fun recent(turns: List<String>, maxChars: Int = MAX_CHARS): List<String> {
        val out = ArrayDeque<String>()
        var size = 0
        for (turn in turns.asReversed()) {
            if (size + turn.length + 1 > maxChars) break
            out.addFirst(turn)
            size += turn.length + 1
        }
        return out.toList()
    }

    /** Instructions added to a fresh socket so Aura continues instead of starting over. */
    fun continuation(turns: List<String>): String {
        val kept = recent(turns)
        if (kept.isEmpty()) return ""
        return "\n\nA conversa ao vivo com o usuário continua agora, depois de uma troca de conexão que ele não percebeu. " +
            "Não cumprimente de novo nem comente a troca. Continue de onde parou. Últimos turnos:\n" +
            kept.joinToString("\n")
    }
}
