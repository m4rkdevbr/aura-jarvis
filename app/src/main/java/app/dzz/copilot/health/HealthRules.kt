package app.dzz.copilot.health

import app.dzz.copilot.glasses.LinkPhase

/** What to repair, decided from plain numbers so it can be unit tested. */
enum class Repair { None, Reconnect, ReopenChannel }

object HealthRules {
    const val DOWN_MS = 90_000L
    const val NO_CHANNEL_MS = 60_000L
    const val COOLDOWN_MS = 180_000L
    const val DROPS_PER_CHECK = 5
    const val PHOTO_FAILS = 2

    /**
     * [downForMs]: how long the glasses link has been Idle/Failed; [noChannelForMs]: how long BLE is
     * up without the command channel. Never repairs if the user disconnected on purpose, if there is no
     * saved device, or if the last repair was less than [COOLDOWN_MS] ago.
     */
    fun glassesRepair(
        hasDevice: Boolean,
        userDisconnected: Boolean,
        phase: LinkPhase,
        downForMs: Long,
        noChannelForMs: Long,
        newDrops: Int,
        photoFailStreak: Int,
        sinceLastRepairMs: Long,
    ): Repair {
        if (!hasDevice || userDisconnected || sinceLastRepairMs < COOLDOWN_MS) return Repair.None
        return when {
            (phase == LinkPhase.Idle || phase == LinkPhase.Failed) && downForMs >= DOWN_MS -> Repair.Reconnect
            phase == LinkPhase.Linked && noChannelForMs >= NO_CHANNEL_MS -> Repair.ReopenChannel
            phase == LinkPhase.Ready && (newDrops >= DROPS_PER_CHECK || photoFailStreak >= PHOTO_FAILS) -> Repair.ReopenChannel
            else -> Repair.None
        }
    }

    /** A tool that failed this many times in a row is reported to the user. */
    fun toolBroken(failStreak: Int): Boolean = failStreak >= 3

    /** Average of the last runs above this is "slow" in the report. */
    fun toolSlow(avgMs: Long): Boolean = avgMs > 15_000
}
