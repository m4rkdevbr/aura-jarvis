package app.dzz.copilot.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One incoming Telegram message, reduced to what Aura uses. */
data class TgMessage(
    val updateId: Long,
    val chatId: Long,
    val from: String,
    val text: String,
    /** Largest photo's file_id, when the message is a photo. */
    val photoId: String? = null,
    val voiceId: String? = null,
    val documentId: String? = null,
    val documentName: String? = null,
)

/** What the bot should do with a message; pure, so it can be tested without the network. */
sealed class TgAction {
    data class Paired(val chatId: Long) : TgAction()
    data class Reply(val chatId: Long, val text: String) : TgAction()
    data class Ask(val message: TgMessage) : TgAction()
    data object Ignore : TgAction()
}

object TelegramRules {
    /**
     * Only one chat may talk to Aura. It is bound by "/start CODE", where CODE is the pairing
     * code shown in Ajustes, so finding the bot's username is not enough to control the phone.
     */
    fun decide(message: TgMessage, ownerChatId: Long, pairingCode: String?): TgAction {
        val text = message.text.trim()
        if (text.startsWith("/start")) {
            val code = text.removePrefix("/start").trim()
            if (ownerChatId == message.chatId) return TgAction.Reply(message.chatId, "Já estamos conectados. Pode falar comigo.")
            if (ownerChatId != 0L) return TgAction.Ignore
            if (pairingCode.isNullOrBlank() || code != pairingCode) {
                return TgAction.Reply(message.chatId, "Código inválido. Abra Ajustes na Aura, gere o código e envie /start CÓDIGO.")
            }
            return TgAction.Paired(message.chatId)
        }
        if (ownerChatId == 0L || message.chatId != ownerChatId) return TgAction.Ignore
        if (text.isEmpty() && message.photoId == null && message.voiceId == null && message.documentId == null) {
            return TgAction.Ignore
        }
        return TgAction.Ask(message)
    }

    /** Messages in a getUpdates reply, plus the next offset (which also skips non-message updates). */
    data class Updates(val nextOffset: Long, val messages: List<TgMessage>)

    fun parseUpdates(body: String, offset: Long = 0L): Updates {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return Updates(offset, emptyList())
        if (!root.optBoolean("ok")) return Updates(offset, emptyList())
        val results = root.optJSONArray("result") ?: return Updates(offset, emptyList())
        val out = ArrayList<TgMessage>()
        var next = offset
        for (i in 0 until results.length()) {
            val update = results.optJSONObject(i) ?: continue
            val id = update.optLong("update_id")
            next = maxOf(next, id + 1)
            val message = update.optJSONObject("message") ?: continue
            val chat = message.optJSONObject("chat") ?: continue
            val photos = message.optJSONArray("photo")
            val photo = if (photos != null && photos.length() > 0) {
                photos.optJSONObject(photos.length() - 1)?.optString("file_id")
            } else {
                null
            }
            val document = message.optJSONObject("document")
            out += TgMessage(
                updateId = id,
                chatId = chat.optLong("id"),
                from = message.optJSONObject("from")?.optString("first_name").orEmpty(),
                text = message.optString("text").ifEmpty { message.optString("caption") },
                photoId = photo,
                voiceId = message.optJSONObject("voice")?.optString("file_id"),
                documentId = document?.optString("file_id"),
                documentName = document?.optString("file_name"),
            )
        }
        return Updates(next, out)
    }

    /** Telegram caps a message at 4096 characters. */
    fun chunks(text: String, size: Int = 4_000): List<String> =
        if (text.length <= size) listOf(text) else text.chunked(size)
}

/**
 * Aura's Telegram bot: long polling (no server needed), one owner chat, text in and out,
 * photos and files from the owner go to the brain, and the brain can send to the owner.
 */
class TelegramBot(
    private val token: () -> String,
    private val ownerChatId: () -> Long,
    private val setOwnerChatId: (Long) -> Unit,
    private val onAsk: suspend (TgMessage, TelegramBot) -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(80, TimeUnit.SECONDS)
        .build(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var offset = 0L

    /** Shown in Ajustes; valid until it is used or the app restarts. */
    @Volatile
    var pairingCode: String? = null
        private set

    @Volatile
    var status: String = "Desligado"
        private set

    fun newPairingCode(): String {
        val code = (100_000 + java.security.SecureRandom().nextInt(900_000)).toString()
        pairingCode = code
        return code
    }

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running || token().isBlank()) return
        job = scope.launch {
            var failures = 0
            status = "Conectando"
            while (isActive) {
                val body = call("getUpdates", JSONObject().put("timeout", 50).put("offset", offset))
                if (body == null) {
                    failures++
                    status = "Sem conexão com o Telegram"
                    delay((2_000L shl failures.coerceAtMost(5)).coerceAtMost(60_000L))
                    continue
                }
                failures = 0
                status = if (ownerChatId() == 0L) "Aguardando /start com o código" else "Conectado"
                val updates = TelegramRules.parseUpdates(body, offset)
                offset = updates.nextOffset
                updates.messages.forEach { handle(it) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        status = "Desligado"
    }

    private suspend fun handle(message: TgMessage) {
        when (val action = TelegramRules.decide(message, ownerChatId(), pairingCode)) {
            is TgAction.Paired -> {
                setOwnerChatId(action.chatId)
                pairingCode = null
                send(action.chatId, "Pronto, sou a Aura. A partir de agora só respondo a este chat.")
            }
            is TgAction.Reply -> send(action.chatId, action.text)
            is TgAction.Ask -> {
                call("sendChatAction", JSONObject().put("chat_id", message.chatId).put("action", "typing"))
                val answer = runCatching { onAsk(action.message, this) }.getOrNull()
                send(message.chatId, answer?.takeIf { it.isNotBlank() } ?: "Não consegui responder agora.")
            }
            TgAction.Ignore -> Unit
        }
    }

    /** Sends text to the owner chat. False when no owner is paired or the call failed. */
    suspend fun sendToOwner(text: String): Boolean {
        val chat = ownerChatId()
        if (chat == 0L) return false
        return send(chat, text)
    }

    suspend fun send(chatId: Long, text: String): Boolean {
        var ok = true
        for (part in TelegramRules.chunks(text)) {
            ok = ok && call("sendMessage", JSONObject().put("chat_id", chatId).put("text", part)) != null
        }
        return ok
    }

    suspend fun sendPhotoToOwner(jpeg: ByteArray, caption: String = ""): Boolean =
        upload("sendPhoto", "photo", "foto.jpg", "image/jpeg", jpeg, caption)

    suspend fun sendDocumentToOwner(bytes: ByteArray, name: String, mime: String, caption: String = ""): Boolean =
        upload("sendDocument", "document", name, mime, bytes, caption)

    suspend fun sendAudioToOwner(bytes: ByteArray, name: String, mime: String, caption: String = ""): Boolean =
        upload("sendAudio", "audio", name, mime, bytes, caption)

    suspend fun sendVideoToOwner(bytes: ByteArray, name: String, caption: String = ""): Boolean =
        upload("sendVideo", "video", name, "video/mp4", bytes, caption)

    /** Downloads a file the owner sent (photo, voice note, document). */
    suspend fun download(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        val info = call("getFile", JSONObject().put("file_id", fileId)) ?: return@withContext null
        val path = runCatching { JSONObject(info).getJSONObject("result").getString("file_path") }.getOrNull()
            ?: return@withContext null
        val request = Request.Builder().url("https://api.telegram.org/file/bot${token()}/$path").build()
        runCatching {
            http.newCall(request).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        }.getOrNull()
    }

    private suspend fun upload(
        method: String,
        field: String,
        name: String,
        mime: String,
        bytes: ByteArray,
        caption: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val chat = ownerChatId()
        if (chat == 0L || token().isBlank()) return@withContext false
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chat.toString())
            .apply { if (caption.isNotBlank()) addFormDataPart("caption", caption.take(1_000)) }
            .addFormDataPart(field, name, bytes.toRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder().url(api(method)).post(body).build()
        runCatching { http.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private suspend fun call(method: String, payload: JSONObject): String? = withContext(Dispatchers.IO) {
        if (token().isBlank()) return@withContext null
        val request = Request.Builder()
            .url(api(method))
            .post(payload.toString().toRequestBody(JSON))
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()
    }

    private fun api(method: String) = "https://api.telegram.org/bot${token()}/$method"

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
