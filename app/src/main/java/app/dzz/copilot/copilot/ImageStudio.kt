package app.dzz.copilot.copilot

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import app.dzz.copilot.data.PreferencesStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ImageStudio(private val context: Context, private val store: PreferencesStore) {
    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()

    fun create(prompt: String): String {
        val key = store.geminiKey.value.trim()
        if (key.isEmpty()) return "Para criar imagem eu preciso da chave do Gemini neste celular."
        val bytes = models.firstNotNullOfOrNull { model -> image(key, model, prompt) }
            ?: return "Não consegui criar a imagem agora."
        val saved = save(bytes)
        return if (saved) "A imagem está no álbum do celular. Os óculos não têm tela para mostrá-la."
        else "A imagem foi criada, mas não entrou no álbum."
    }

    private fun image(key: String, model: String, prompt: String): ByteArray? {
        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", "Crie uma imagem nítida, sem texto escrito: $prompt")),
                    ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")),
            )
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val body = runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull() ?: return null
        val parts = runCatching {
            JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        }.getOrNull() ?: return null
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val data = part.optJSONObject("inlineData")?.optString("data")
                ?: part.optJSONObject("inline_data")?.optString("data")
            if (!data.isNullOrBlank()) return runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
        }
        return null
    }

    private fun save(bytes: ByteArray): Boolean {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "aura-${System.currentTimeMillis()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/DZZ Copiloto")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return false
        val wrote = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("sem arquivo")
        }.isSuccess
        if (Build.VERSION.SDK_INT >= 29) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        if (!wrote) resolver.delete(uri, null, null)
        return wrote
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val models = listOf("gemini-2.5-flash-image", "gemini-2.0-flash-preview-image-generation")
    }
}
