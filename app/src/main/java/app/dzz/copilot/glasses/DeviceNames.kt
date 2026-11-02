package app.dzz.copilot.glasses

object DeviceNames {
    private val known = listOf(
        "AIMB-S1PRO",
        "AIMB-S1",
        "S3 ULTRA",
        "S3 Ultra",
        "AIMB-G2pro",
        "UiAi G2 Pro",
        "AIMB-G5MAX",
        "AIMB-G7",
        "AIMB-S2MAX",
        "AUXOM G2 MAX",
        "AUXOM G2",
        "AIMB-G6",
    )

    fun looksLikeGlasses(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val value = name.lowercase()
        if (known.any { value.contains(it.lowercase()) }) return true
        return listOf("aimb", "auxom", "uiai", "s3", "g2 pro", "glasses", "dzz").any { value.contains(it) }
    }

    fun family(name: String?): String {
        val value = name.orEmpty()
        return when {
            value.contains("S3 ULTRA", ignoreCase = false) && value == value.uppercase() -> "BK"
            looksLikeGlasses(value) -> "QC / BK"
            else -> "outro"
        }
    }
}
