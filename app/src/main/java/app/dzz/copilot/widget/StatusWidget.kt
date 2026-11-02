package app.dzz.copilot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.dzz.copilot.DzzApplication
import app.dzz.copilot.MainActivity
import app.dzz.copilot.R
import app.dzz.copilot.glasses.LinkPhase

class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context)
    }

    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, StatusWidget::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val app = context.applicationContext as? DzzApplication
            val snap = app?.hubIfReady()?.glasses?.snapshot?.value
            val status = when {
                snap == null -> "Sem conexão"
                snap.phase == LinkPhase.Ready -> snap.name ?: "Conectado"
                snap.phase == LinkPhase.Linked -> "Sem canal QC"
                else -> snap.status
            }
            val battery = when {
                snap?.battery == null -> "Bateria —"
                snap.charging -> "Bateria ${snap.battery}% · carregando"
                else -> "Bateria ${snap.battery}%"
            }
            val views = RemoteViews(context.packageName, R.layout.widget_status).apply {
                setTextViewText(R.id.widget_status, status)
                setTextViewText(R.id.widget_battery, battery)
                val open = PendingIntent.getActivity(
                    context,
                    7,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                setOnClickPendingIntent(R.id.widget_status, open)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
