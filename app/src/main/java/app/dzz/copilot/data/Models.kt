package app.dzz.copilot.data

data class TaskItem(
    val id: String,
    val title: String,
    val notes: String = "",
    val dueAt: Long? = null,
    val placeId: String? = null,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

data class PlaceMark(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = 180,
)

data class MeetingNote(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
)
