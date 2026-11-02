package app.dzz.copilot.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dzz.copilot.hub

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val task = context.hub().vault.tasks.value.firstOrNull { it.id == id } ?: return
        if (task.done) return
        ReminderNotifier.show(context, task.id, "Compromisso", task.title)
    }

    companion object {
        const val EXTRA_ID = "task_id"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val hub = context.hub()
        hub.vault.rescheduleOpen()
        val address = hub.store.deviceAddress.value
        if (hub.store.keepAlive.value && address != null) {
            app.dzz.copilot.glasses.GlassesService.start(context)
        }
    }
}
