package app.dzz.copilot.data

import android.content.Context
import app.dzz.copilot.reminder.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Vault(private val context: Context) {
    private val file = File(context.filesDir, "vault.json")
    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()
    private val _places = MutableStateFlow<List<PlaceMark>>(emptyList())
    val places: StateFlow<List<PlaceMark>> = _places.asStateFlow()
    private val _meetings = MutableStateFlow<List<MeetingNote>>(emptyList())
    val meetings: StateFlow<List<MeetingNote>> = _meetings.asStateFlow()

    init {
        load()
        rescheduleOpen()
    }

    fun addTask(title: String, dueAt: Long? = null, notes: String = "", placeId: String? = null): TaskItem {
        val task = TaskItem(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            notes = notes.trim(),
            dueAt = dueAt,
            placeId = placeId,
        )
        _tasks.value = listOf(task) + _tasks.value
        persist()
        ReminderScheduler.schedule(context, task)
        return task
    }

    fun toggle(id: String) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id != id) task else task.copy(done = !task.done)
        }
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.done) ReminderScheduler.cancel(context, id) else ReminderScheduler.schedule(context, task)
        persist()
    }

    fun deleteTask(id: String) {
        ReminderScheduler.cancel(context, id)
        _tasks.value = _tasks.value.filterNot { it.id == id }
        persist()
    }

    fun setPlace(taskId: String, placeId: String?) {
        _tasks.value = _tasks.value.map { if (it.id == taskId) it.copy(placeId = placeId) else it }
        persist()
    }

    fun addPlace(name: String, latitude: Double, longitude: Double): PlaceMark {
        val place = PlaceMark(UUID.randomUUID().toString(), name.trim(), latitude, longitude)
        _places.value = _places.value + place
        persist()
        return place
    }

    fun deletePlace(id: String) {
        _places.value = _places.value.filterNot { it.id == id }
        _tasks.value = _tasks.value.map { if (it.placeId == id) it.copy(placeId = null) else it }
        persist()
    }

    fun addMeeting(title: String, body: String): MeetingNote {
        val note = MeetingNote(UUID.randomUUID().toString(), title.trim(), body.trim())
        _meetings.value = listOf(note) + _meetings.value.take(40)
        persist()
        return note
    }

    fun deleteMeeting(id: String) {
        _meetings.value = _meetings.value.filterNot { it.id == id }
        persist()
    }

    fun rescheduleOpen() {
        val now = System.currentTimeMillis()
        _tasks.value.filter { !it.done && it.dueAt != null && it.dueAt > now }.forEach {
            ReminderScheduler.schedule(context, it)
        }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            _tasks.value = root.optJSONArray("tasks").toTasks()
            _places.value = root.optJSONArray("places").toPlaces()
            _meetings.value = root.optJSONArray("meetings").toMeetings()
        }
    }

    private fun persist() {
        val root = JSONObject()
            .put("version", 1)
            .put("tasks", JSONArray().apply { _tasks.value.forEach { put(it.toJson()) } })
            .put("places", JSONArray().apply { _places.value.forEach { put(it.toJson()) } })
            .put("meetings", JSONArray().apply { _meetings.value.forEach { put(it.toJson()) } })
        file.writeText(root.toString())
    }

    private fun TaskItem.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("notes", notes)
        .put("dueAt", dueAt)
        .put("placeId", placeId)
        .put("done", done)
        .put("createdAt", createdAt)

    private fun PlaceMark.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("radiusMeters", radiusMeters)

    private fun MeetingNote.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("body", body)
        .put("createdAt", createdAt)

    private fun JSONArray?.toTasks(): List<TaskItem> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    TaskItem(
                        id = item.getString("id"),
                        title = item.optString("title"),
                        notes = item.optString("notes"),
                        dueAt = if (item.isNull("dueAt")) null else item.optLong("dueAt"),
                        placeId = item.optString("placeId").ifBlank { null },
                        done = item.optBoolean("done"),
                        createdAt = item.optLong("createdAt"),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toPlaces(): List<PlaceMark> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    PlaceMark(
                        id = item.getString("id"),
                        name = item.optString("name"),
                        latitude = item.optDouble("latitude"),
                        longitude = item.optDouble("longitude"),
                        radiusMeters = item.optInt("radiusMeters", 180),
                    ),
                )
            }
        }
    }

    private fun JSONArray?.toMeetings(): List<MeetingNote> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    MeetingNote(
                        id = item.getString("id"),
                        title = item.optString("title"),
                        body = item.optString("body"),
                        createdAt = item.optLong("createdAt"),
                    ),
                )
            }
        }
    }
}
