package app.dzz.copilot.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class PhoneBrief(private val context: Context) {
    fun agenda(): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "Preciso da permissão da agenda do celular."
        }
        val start = System.currentTimeMillis()
        val end = start + 36 * 60 * 60 * 1000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString())
            .appendPath(end.toString())
            .build()
        val events = ArrayList<String>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
                null,
                null,
                CalendarContract.Instances.BEGIN + " ASC",
            )?.use { cursor ->
                val title = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val begin = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                while (cursor.moveToNext() && events.size < 5) {
                    val name = cursor.getString(title).orEmpty().ifBlank { "Compromisso" }
                    val whenText = clock.format(Instant.ofEpochMilli(cursor.getLong(begin)).atZone(ZoneId.systemDefault()))
                    events += "$whenText $name"
                }
            }
        }
        if (events.isEmpty()) return "Não há compromissos nas próximas horas."
        return "Na agenda: " + events.joinToString(". ")
    }

    fun messages(): String {
        val pieces = ArrayList<String>()
        Inbox.init(context)
        pieces += Inbox.recent()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
                null,
                null,
                Telephony.Sms.DATE + " DESC",
            )?.use { cursor ->
                val address = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val body = cursor.getColumnIndex(Telephony.Sms.BODY)
                var count = 0
                while (cursor.moveToNext() && count < 3) {
                    val from = cursor.getString(address).orEmpty().ifBlank { "um número" }
                    val text = cursor.getString(body).orEmpty().replace(Regex("\\s+"), " ").take(80)
                    if (text.isNotBlank()) {
                        pieces += "SMS de $from: $text"
                        count++
                    }
                }
            }
            }
        }
        if (pieces.isEmpty()) {
            return "Não vi mensagens novas. SMS pede permissão, e WhatsApp ou e-mail aparecem se o acesso às notificações estiver ligado."
        }
        return pieces.take(5).joinToString(". ")
    }

    private companion object {
        val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale("pt", "BR"))
    }
}
