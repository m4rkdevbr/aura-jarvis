package app.dzz.copilot.health

import app.dzz.copilot.glasses.LinkPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthRulesTest {
    private fun repair(
        phase: LinkPhase,
        down: Long = 0,
        noChannel: Long = 0,
        drops: Int = 0,
        photos: Int = 0,
        since: Long = Long.MAX_VALUE,
        user: Boolean = false,
        device: Boolean = true,
    ) = HealthRules.glassesRepair(device, user, phase, down, noChannel, drops, photos, since)

    @Test
    fun reconnectsAfterLongDrop() {
        assertEquals(Repair.Reconnect, repair(LinkPhase.Failed, down = 95_000))
        assertEquals(Repair.None, repair(LinkPhase.Failed, down = 30_000))
    }

    @Test
    fun respectsUserAndCooldown() {
        assertEquals(Repair.None, repair(LinkPhase.Idle, down = 200_000, user = true))
        assertEquals(Repair.None, repair(LinkPhase.Idle, down = 200_000, since = 60_000))
        assertEquals(Repair.None, repair(LinkPhase.Idle, down = 200_000, device = false))
    }

    @Test
    fun reopensStuckChannel() {
        assertEquals(Repair.ReopenChannel, repair(LinkPhase.Linked, noChannel = 61_000))
        assertEquals(Repair.ReopenChannel, repair(LinkPhase.Ready, drops = 6))
        assertEquals(Repair.ReopenChannel, repair(LinkPhase.Ready, photos = 2))
        assertEquals(Repair.None, repair(LinkPhase.Ready, drops = 1, photos = 1))
    }
}
