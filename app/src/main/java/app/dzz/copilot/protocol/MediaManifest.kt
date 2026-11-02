package app.dzz.copilot.protocol

object MediaManifest {
    private val namePattern = Regex("[A-Za-z0-9._\\-/ ]+")

    fun parse(body: String): List<String> {
        val text = body.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) return emptyList()
        val found = linkedSetOf<String>()
        Regex(""""(?:filename|name|path|file)"\s*:\s*"([^"]+)"""").findAll(text).forEach { match ->
            keep(found, match.groupValues[1])
        }
        Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            keep(found, match.groupValues[1])
        }
        text.lineSequence().forEach { line ->
            line.split(',', '|', ';', '\t', ' ').forEach { token ->
                keep(found, token.trim().trim('"', '\''))
            }
        }
        return found.toList()
    }

    fun safeRelative(name: String): String? {
        val cleaned = name.trim().replace('\\', '/').trimStart('/')
        if (cleaned.isEmpty() || cleaned.contains("..")) return null
        if (!namePattern.matches(cleaned)) return null
        if (!isMedia(cleaned)) return null
        return cleaned
    }

    private fun keep(found: MutableSet<String>, raw: String) {
        val safe = safeRelative(raw.substringBefore('?')) ?: return
        found += safe
    }

    private fun isMedia(name: String): Boolean {
        val base = name.substringAfterLast('/').lowercase()
        return base.endsWith(".jpg") ||
            base.endsWith(".jpeg") ||
            base.endsWith(".png") ||
            base.endsWith(".mp4") ||
            base.endsWith(".mov") ||
            base.endsWith(".m4v") ||
            base.endsWith(".opus") ||
            base.endsWith(".wav") ||
            base.endsWith(".aac") ||
            base.endsWith(".m4a")
    }
}
