package app.dzz.copilot.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class ChatLine(
    val role: String,
    val text: String,
    val at: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
)

class ChatLog(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(context.filesDir, "chat.json")
    private val imageDir = File(context.filesDir, "chat_images").also { it.mkdirs() }
    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()

    init {
        val raw = runCatching { file.readText() }.getOrNull()
        if (!raw.isNullOrBlank()) {
            val parsed = ArrayList<ChatLine>()
            val array = runCatching { JSONArray(raw) }.getOrNull()
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                    if (text.isBlank()) continue
                    val path = item.optString("image").takeIf { it.isNotBlank() }
                    parsed += ChatLine(item.optString("role", "aura"), text, item.optLong("at"), path)
                }
            }
            _lines.value = parsed.takeLast(80)
        }
    }

    fun add(role: String, text: String, imagePath: String? = null) {
        val clean = text.trim()
        if (clean.isBlank() && imagePath.isNullOrBlank()) return
        _lines.value = (_lines.value + ChatLine(role, clean.ifBlank { " " }, imagePath = imagePath)).takeLast(80)
        persist()
    }

    fun addVision(role: String, text: String, jpeg: ByteArray?, bitmap: Bitmap? = null): String? {
        val path = saveImage(jpeg, bitmap)
        add(role, text, path)
        return path
    }

    fun recent(): List<Pair<String, String>> = _lines.value.takeLast(10).map { it.role to it.text }

    fun replaceLast(role: String, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val current = _lines.value
        _lines.value = if (current.isEmpty()) {
            listOf(ChatLine(role, clean))
        } else {
            val last = current.last()
            current.dropLast(1) + ChatLine(role, clean, last.at, last.imagePath)
        }
        persist()
    }

    fun clear() {
        _lines.value = emptyList()
        imageDir.listFiles()?.forEach { runCatching { it.delete() } }
        persist()
    }

    fun imageFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        return file.takeIf { it.exists() }
    }

    private fun saveImage(jpeg: ByteArray?, bitmap: Bitmap?): String? {
        val name = "vision-${System.currentTimeMillis()}.jpg"
        val out = File(imageDir, name)
        return runCatching {
            if (jpeg != null && jpeg.isNotEmpty()) {
                out.writeBytes(jpeg)
            } else if (bitmap != null) {
                FileOutputStream(out).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                }
            } else {
                return null
            }
            out.absolutePath
        }.getOrNull()
    }

    private fun persist() {
        val array = JSONArray()
        _lines.value.forEach { line ->
            val obj = JSONObject()
                .put("role", line.role)
                .put("text", line.text)
                .put("at", line.at)
            if (!line.imagePath.isNullOrBlank()) obj.put("image", line.imagePath)
            array.put(obj)
        }
        runCatching { file.writeText(array.toString()) }
    }
}
