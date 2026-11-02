package app.dzz.copilot.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Offline Portuguese speech recognition (Vosk, small pt model shipped in assets/vosk-pt).
 * It hears "Aura" without internet, so idle speech never leaves the phone, and it is the
 * fallback transcript when there is no cloud.
 */
class LocalAsr(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var model: Model? = null

    @Volatile
    var status: String = "Carregando"
        private set

    val ready: Boolean get() = model != null

    /** Copies the model out of the APK once and loads it. Call off the main thread. */
    fun warmUp() {
        if (model != null) return
        synchronized(lock) {
            if (model != null) return
            runCatching {
                val dir = unpack()
                model = Model(dir.absolutePath)
                status = "Pronto"
                Log.i(TAG, "modelo local pronto em ${dir.absolutePath}")
            }.onFailure {
                status = "Falhou: ${it.message}"
                Log.w(TAG, "modelo local: ${it.message}")
            }
        }
    }

    /** Full offline transcript of one segment (16 kHz, mono, 16-bit little endian), or null. */
    fun transcribe(pcm: ByteArray): String? {
        val m = model ?: return null
        return runCatching {
            Recognizer(m, SAMPLE_RATE.toFloat()).use { rec ->
                val words = ArrayList<String>()
                var at = 0
                while (at < pcm.size) {
                    val n = minOf(CHUNK, pcm.size - at)
                    if (rec.acceptWaveForm(pcm.copyOfRange(at, at + n), n)) words += JSONObject(rec.result).optString("text")
                    at += n
                }
                words += JSONObject(rec.finalResult).optString("text")
                words.filter { it.isNotBlank() }.joinToString(" ").trim()
            }
        }.onFailure { Log.w(TAG, "transcrição local: ${it.message}") }.getOrNull()
    }

    private fun unpack(): File {
        val dir = File(appContext.filesDir, "vosk-pt")
        val marker = File(dir, ".version")
        if (marker.exists() && marker.readText() == MODEL_VERSION) return dir
        dir.deleteRecursively()
        copyAssets(ASSET_DIR, dir)
        marker.writeText(MODEL_VERSION)
        return dir
    }

    private fun copyAssets(path: String, target: File) {
        val children = appContext.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        children.forEach { copyAssets("$path/$it", File(target, it)) }
    }

    companion object {
        private const val TAG = "LocalAsr"
        const val SAMPLE_RATE = 16_000
        private const val CHUNK = 6_400
        private const val ASSET_DIR = "vosk-pt"
        private const val MODEL_VERSION = "vosk-model-small-pt-0.3"

        private val WAKE = Regex("\\b(aura|aurea|aurinha)\\b")

        /** True when an offline transcript contains the wake word. */
        fun hasWake(text: String?): Boolean = text != null && WAKE.containsMatchIn(AuraCommands.normalize(text))
    }
}
