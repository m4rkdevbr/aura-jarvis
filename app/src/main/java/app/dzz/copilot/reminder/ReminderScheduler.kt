package app.dzz.copilot.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.dzz.copilot.MainActivity
import app.dzz.copilot.R
import app.dzz.copilot.data.TaskItem

object Channels {
    const val STATUS = "dzz_status"
    const val REMINDER = "dzz_reminder"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val status = NotificationChannel(STATUS, "Conexão dos óculos", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mantém o Bluetooth dos óculos ativo"
        }
        val reminder = NotificationChannel(REMINDER, "Compromissos", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Lembretes de tarefas e lugares"
        }
        manager.createNotificationChannel(status)
        manager.createNotificationChannel(reminder)
    }
}

object ReminderNotifier {
    fun show(context: Context, id: String, title: String, text: String) {
        Channels.ensure(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Channels.REMINDER)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(id.hashCode(), notification)
    }
}

object ReminderScheduler {
    fun schedule(context: Context, task: TaskItem) {
        val due = task.dueAt ?: return
        if (task.done || due <= System.currentTimeMillis()) return
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(context, task.id)
        val trigger = due.coerceAtLeast(System.currentTimeMillis() + 1_500L)
        try {
            val canExact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
            if (canExact) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    fun cancel(context: Context, id: String) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(pending(context, id))
    }

    private fun pending(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
