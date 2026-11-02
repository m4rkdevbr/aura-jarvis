package app.dzz.copilot.proactive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import app.dzz.copilot.data.PreferencesStore
import app.dzz.copilot.glasses.GlassesClient
import app.dzz.copilot.phone.Inbox
import app.dzz.copilot.phone.InboxItem
import app.dzz.copilot.phone.PhoneBrief
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class UpcomingEvent(val id: Long, val title: String, val begin: Long, val location: String = "")

/** When Aura speaks up on her own. Pure, unit tested. */
object ProactiveRules {
    val MESSAGING = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.facebook.orca", "com.instagram.android",
    )

    fun quiet(now: LocalDateTime, startHour: Int = 22, endHour: Int = 7): Boolean {
        val h = now.hour
        return if (startHour > endHour) h >= startHour || h < endHour else h in startHour until endHour
    }

    /** Morning brief: once a day, from [hour] until noon, if it was not given today. */
    fun briefDue(now: LocalDateTime, hour: Int, lastBriefEpochDay: Long): Boolean =
        now.hour >= hour && now.hour < 12 && now.toLocalDate().toEpochDay() != lastBriefEpochDay

    /** Events starting within [leadMs] that were not announced yet. */
    fun dueEvents(events: List<UpcomingEvent>, nowMs: Long, announced: Set<String>, leadMs: Long = 10 * 60_000L): List<UpcomingEvent> =
        events.filter { it.begin in nowMs..(nowMs + leadMs) && key(it) !in announced }

    /** Evening preview of tomorrow: once a day, from [hour] until 22h. */
    fun eveningDue(now: LocalDateTime, hour: Int, lastEpochDay: Long): Boolean =
        now.hour >= hour && now.hour < 22 && now.toLocalDate().toEpochDay() != lastEpochDay

    fun spokenEvent(event: UpcomingEvent, minutes: Long): String =
        "Daqui a $minutes minutos: ${event.title}" +
            (if (event.location.isNotBlank()) ", em ${event.location.take(60)}. Se quiser, peço a rota." else ".")

    fun key(event: UpcomingEvent): String = "${event.id}@${event.begin}"

    /** A message worth saying out loud: a messaging app, not Aura's own, not a group summary line. */
    fun announceable(item: InboxItem, ownPackage: String): Boolean =
        item.packageName != ownPackage && item.packageName in MESSAGING && item.title.isNotBlank() &&
            !Regex("\\d+ (novas )?mensagens|new messages", RegexOption.IGNORE_CASE).containsMatchIn(item.text)

    fun spokenMessage(item: InboxItem): String =
        "Mensagem de ${item.title.take(40)} no ${item.app}: ${item.text.take(140)}. Se quiser responder, é só me dizer."

    /** Glasses battery warning crossing 15% and 5%, once each per discharge. */
    fun batteryWarning(level: Int?, lastWarned: Int): Int? = when {
        level == null -> null
        level <= 5 && lastWarned > 5 -> 5
        level <= 15 && lastWarned > 15 -> 15
        else -> null
    }
}

/**
 * Aura speaking up without being asked: the morning brief, a reminder a few minutes before each
 * calendar event, new messages from chat apps, and low glasses battery. Never during quiet hours,
 * and only when the user turned it on. [say] queues a spoken line (it waits if Aura is busy).
 */
class Proactive(
    context: Context,
    private val store: PreferencesStore,
    private val glasses: GlassesClient,
    private val say: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val brief = PhoneBrief(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("aura_proactive", Context.MODE_PRIVATE)
    private val announced = LinkedHashSet<String>()
    private var lastBattery = 101
    private var lastPhoneBattery = 101
    private var lastMessageAt = 0L
    private var job: Job? = null

    fun start() {
        if (job != null) return
        Inbox.listener = { item -> onMessage(item) }
        job = scope.launch {
            delay(20_000)
            while (isActive) {
                runCatching { tick() }.onFailure { Log.w(TAG, "tick: ${it.message}") }
                delay(60_000)
            }
        }
    }

    private fun on(): Boolean = store.proactive.value && store.auraEnabled.value

    private fun tick() {
        if (!on()) return
        val now = LocalDateTime.now()
        if (ProactiveRules.quiet(now)) return
        val lastDay = prefs.getLong("brief_day", -1L)
        if (ProactiveRules.briefDue(now, store.briefHour.value, lastDay)) {
            prefs.edit().putLong("brief_day", now.toLocalDate().toEpochDay()).apply()
            val unread = Inbox.since(System.currentTimeMillis() - 12 * 3_600_000L)
                .count { it.packageName in ProactiveRules.MESSAGING }
            say("Bom dia. ${brief.agenda()} " + if (unread > 0) "Você tem $unread mensagens desde ontem à noite." else "")
        }
        val events = upcoming()
        ProactiveRules.dueEvents(events, System.currentTimeMillis(), announced).forEach { event ->
            announced += ProactiveRules.key(event)
            val minutes = ((event.begin - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
            say(ProactiveRules.spokenEvent(event, minutes))
        }
        while (announced.size > 200) announced.remove(announced.first())
        ProactiveRules.batteryWarning(glasses.snapshot.value.battery, lastBattery)?.let { level ->
            lastBattery = level
            say("A bateria dos óculos está em $level por cento.")
        }
        glasses.snapshot.value.battery?.let { if (it > 30) lastBattery = 101 }
        phoneBattery()?.let { level ->
            ProactiveRules.batteryWarning(level, lastPhoneBattery)?.let {
                lastPhoneBattery = it
                say("A bateria do celular está em $it por cento.")
            }
            if (level > 30) lastPhoneBattery = 101
        }
        val lastEvening = prefs.getLong("evening_day", -1L)
        if (ProactiveRules.eveningDue(now, EVENING_HOUR, lastEvening)) {
            prefs.edit().putLong("evening_day", now.toLocalDate().toEpochDay()).apply()
            tomorrowFirst()?.let { (title, at) -> say("Para amanhã: o primeiro compromisso é $title às $at.") }
        }
    }

    private fun phoneBattery(): Int? = runCatching {
        val bm = app.getSystemService(android.os.BatteryManager::class.java) ?: return null
        if (bm.isCharging) null else bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }.getOrNull()

    private fun tomorrowFirst(): Pair<String, String>? {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.LocalDate.now().plusDays(1)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(start.toString()).appendPath(end.toString()).build()
        return runCatching {
            app.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(2) == 1) continue
                    val at = java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(zone).toLocalTime()
                    return@use c.getString(0).orEmpty().ifBlank { "um compromisso" } to "%02d:%02d".format(at.hour, at.minute)
                }
                null
            }
        }.getOrNull()
    }

    private fun onMessage(item: InboxItem) {
        if (!on() || !store.announceMessages.value) return
        if (ProactiveRules.quiet(LocalDateTime.now())) return
        if (!ProactiveRules.announceable(item, app.packageName)) return
        // A burst of messages becomes one line every 20 seconds at most.
        val now = System.currentTimeMillis()
        if (now - lastMessageAt < 20_000) return
        lastMessageAt = now
        say(ProactiveRules.spokenMessage(item))
    }

    private fun upcoming(): List<UpcomingEvent> {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val start = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString()).appendPath((start + 30 * 60_000L).toString()).build()
        val out = ArrayList<UpcomingEvent>()
        runCatching {
            app.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(3) == 1) continue
                    out += UpcomingEvent(
                        c.getLong(0), c.getString(1).orEmpty().ifBlank { "um compromisso" }, c.getLong(2), c.getString(4).orEmpty(),
                    )
                }
            }
        }
        return out
    }

    private companion object {
        const val TAG = "AuraProactive"
        const val EVENING_HOUR = 20
    }
}
