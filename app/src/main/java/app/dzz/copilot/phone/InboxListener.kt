package app.dzz.copilot.phone

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class InboxListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Inbox.init(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Inbox.init(applicationContext)
        val notification = sbn.notification ?: return
        // Group summaries repeat the last message; ongoing ones are media/progress noise.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if (sbn.packageName == packageName) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank()) return
        Replies.capture(sbn.key, notification)
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        Inbox.remember(
            InboxItem(
                key = sbn.key,
                packageName = sbn.packageName,
                app = app,
                title = title,
                text = text.replace(Regex("\\s+"), " ").take(600),
                postedAt = sbn.postTime,
            ),
        )
    }
}

data class InboxItem(
    val key: String,
    val packageName: String,
    val app: String,
    val title: String,
    val text: String,
    val postedAt: Long,
) {
    fun spoken(): String = "$app. $title. ${text.take(160)}".replace(Regex("\\s+"), " ").trim()

    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("pkg", packageName)
        .put("app", app)
        .put("title", title)
        .put("text", text)
        .put("at", postedAt)

    companion object {
        fun fromJson(json: JSONObject): InboxItem = InboxItem(
            key = json.optString("key"),
            packageName = json.optString("pkg"),
            app = json.optString("app"),
            title = json.optString("title"),
            text = json.optString("text"),
            postedAt = json.optLong("at"),
        )
    }
}

/**
 * Reply buttons (RemoteInput) of recent notifications, so Aura can answer a WhatsApp or SMS
 * notification the way the "Responder" button does. In memory only: a PendingIntent does not
 * survive a restart.
 */
object Replies {
    private const val MAX = 60
    private val actions = LinkedHashMap<String, Notification.Action>()

    fun capture(key: String, notification: Notification) {
        val action = notification.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return
        synchronized(actions) {
            actions.remove(key)
            actions[key] = action
            while (actions.size > MAX) actions.remove(actions.keys.first())
        }
    }

    fun has(key: String): Boolean = synchronized(actions) { key in actions }

    /** Sends [text] through the notification's reply button. False when it has none or it expired. */
    fun send(context: Context, key: String, text: String): Boolean {
        val action = synchronized(actions) { actions[key] } ?: return false
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
        if (inputs.isEmpty()) return false
        val intent = android.content.Intent()
        val results = android.os.Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        android.app.RemoteInput.addResultsToIntent(inputs.toTypedArray(), intent, results)
        return runCatching { action.actionIntent.send(context, 0, intent); true }.getOrDefault(false)
    }
}

/**
 * Notification history kept on disk, so a restart does not wipe what arrived.
 * Holds the last [MAX_ITEMS] notifications, newest first.
 */
object Inbox {
    private const val MAX_ITEMS = 300
    private const val FILE_NAME = "inbox.json"
    private val items = ArrayDeque<InboxItem>()
    private var file: File? = null

    /** Told about each new notification (proactive announcements). */
    @Volatile
    var listener: ((InboxItem) -> Unit)? = null

    fun init(context: Context) {
        synchronized(items) {
            if (file != null) return
            val target = File(context.applicationContext.filesDir, FILE_NAME)
            file = target
            runCatching {
                if (!target.exists()) return@runCatching
                val array = JSONArray(target.readText())
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { items.addLast(InboxItem.fromJson(it)) }
                }
            }
        }
    }

    fun remember(item: InboxItem) {
        synchronized(items) {
            // Same conversation updating its notification: keep only the newest text.
            val duplicate = items.firstOrNull { it.key == item.key && it.text == item.text }
            if (duplicate != null) return
            items.addFirst(item)
            while (items.size > MAX_ITEMS) items.removeLast()
            save()
        }
        runCatching { listener?.invoke(item) }
    }

    /** Short spoken lines for the quick "minhas mensagens" brief. */
    fun recent(limit: Int = 8): List<String> = synchronized(items) {
        items.take(limit).map { it.spoken() }
    }

    fun since(epochMs: Long): List<InboxItem> = synchronized(items) {
        items.filter { it.postedAt >= epochMs }
    }

    fun search(query: String, limit: Int = 20): List<InboxItem> = synchronized(items) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return@synchronized items.take(limit)
        items.filter {
            it.app.lowercase().contains(needle) ||
                it.title.lowercase().contains(needle) ||
                it.text.lowercase().contains(needle)
        }.take(limit)
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(array.toString())
                tmp.delete()
            }
        }
    }
}
