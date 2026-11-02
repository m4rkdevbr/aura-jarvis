package app.dzz.copilot.copilot

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class OnDeviceLlmStatus(
    val phase: String = "idle",
    val detail: String = "Cérebro local ainda não instalado neste celular.",
    val progress: Float = 0f,
    val ready: Boolean = false,
    val serverRunning: Boolean = false,
)

/**
 * Installs llama-server (Android arm64) + LFM3-Omni Q4 GGUF into app-private storage
 * and exposes paths for [LocalLlmService].
 */
class OnDeviceLlmRuntime(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "lfm3").apply { mkdirs() }
    private val runtimeDir = File(root, "runtime").apply { mkdirs() }
    private val modelDir = File(root, "models").apply { mkdirs() }

    private val _status = MutableStateFlow(OnDeviceLlmStatus())
    val status: StateFlow<OnDeviceLlmStatus> = _status.asStateFlow()

    private val logLines = ArrayDeque<String>()
    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    @Volatile
    private var startInFlight = false

    fun tryBeginStart(): Boolean {
        synchronized(this) {
            if (startInFlight || probeHealth()) return false
            startInFlight = true
            return true
        }
    }

    fun endStart() {
        startInFlight = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun modelFile(): File = File(modelDir, MODEL_FILE)
    fun mmprojFile(): File = File(modelDir, MMPROJ_FILE)
    fun serverLogFile(): File = File(root, "server.log")
    fun serverBinary(): File? = findBinary("llama-server")

    private fun runtimeVersionFile(): File = File(runtimeDir, "VERSION")

    fun runtimeVersionOk(): Boolean {
        healRuntimeVersion()
        return runtimeVersionFile().takeIf { it.exists() }?.readText()?.trim() == RUNTIME_TAG
    }

    /** Accept sideloaded/extracted folders named llama-b12017 without VERSION stamp. */
    private fun healRuntimeVersion() {
        if (runtimeVersionFile().exists()) return
        val hasTaggedDir = runtimeDir.listFiles()?.any { it.isDirectory && it.name.contains(RUNTIME_TAG) } == true
        val hasServer = findBinary("llama-server") != null
        if (hasTaggedDir || hasServer) {
            runCatching { runtimeVersionFile().writeText(RUNTIME_TAG) }
            log("VERSION regenerado → $RUNTIME_TAG")
        }
    }

    fun isInstalled(): Boolean {
        val server = serverBinary()
        return runtimeVersionOk() &&
            server != null && server.canExecute() &&
            modelFile().exists() && modelFile().length() > 100_000_000L &&
            mmprojFile().exists() && mmprojFile().length() > 10_000_000L
    }

    fun refresh() {
        val installed = isInstalled()
        val listening = probeHealth()
        _status.value = OnDeviceLlmStatus(
            phase = when {
                listening -> "serving"
                installed -> "ready"
                else -> "idle"
            },
            detail = when {
                listening -> "llama-server ativo em 127.0.0.1:$PORT"
                installed -> "LFM3-Omni instalado (${formatGb(modelFile().length() + mmprojFile().length())}). Pronto para iniciar."
                else -> "Falta instalar ~${formatGb(MODEL_BYTES + MMPROJ_BYTES + RUNTIME_BYTES)} (runtime + Omni Q4)."
            },
            progress = if (installed) 1f else 0f,
            ready = installed,
            serverRunning = listening,
        )
        log(
            "refresh ready=$installed running=$listening model=${modelFile().length()} " +
                "mmproj=${mmprojFile().length()} server=${serverBinary()?.absolutePath ?: "ausente"}",
        )
    }

    fun markServer(running: Boolean, detail: String? = null) {
        val base = _status.value
        val message = detail ?: if (running) "llama-server ouvindo em 127.0.0.1:$PORT" else base.detail
        _status.value = base.copy(
            serverRunning = running,
            detail = message,
            phase = when {
                running -> "serving"
                base.ready -> "ready"
                else -> base.phase
            },
        )
        log(message)
    }

    fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "$stamp $line"
        Log.i(TAG, line)
        synchronized(logLines) {
            logLines.addLast(entry)
            while (logLines.size > 80) logLines.removeFirst()
            _diagnostics.value = logLines.joinToString("\n")
        }
    }

    fun appendServerLogTail(maxChars: Int = 2_500): String {
        val file = serverLogFile()
        if (!file.exists()) return "(sem server.log)"
        val text = runCatching { file.readText() }.getOrElse { return "(erro lendo server.log: ${it.message})" }
        val tail = if (text.length <= maxChars) text else text.takeLast(maxChars)
        log("--- server.log ---\n$tail")
        return tail
    }

    fun probeHealth(): Boolean {
        val request = Request.Builder().url("$BASE_URL/health").get().build()
        return runCatching {
            OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Kill leftover llama-server processes from this app (port conflicts). */
    fun killStaleServers() {
        log("Encerrando llama-server anteriores…")
        runCatching {
            ProcessBuilder("sh", "-c", "pkill -f llama-server || killall llama-server || true")
                .redirectErrorStream(true)
                .start()
                .waitFor(2, TimeUnit.SECONDS)
        }
    }

    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        refresh()
        if (isInstalled()) {
            _status.value = _status.value.copy(phase = "ready", detail = "Já instalado.", progress = 1f, ready = true)
            return@withContext true
        }
        val need = MODEL_BYTES + MMPROJ_BYTES + RUNTIME_BYTES
        val free = freeBytes()
        if (free < need + 200_000_000L) {
            _status.value = OnDeviceLlmStatus(
                phase = "error",
                detail = "Espaço insuficiente. Livre ~${formatGb(need)} (há ${formatGb(free)}).",
            )
            return@withContext false
        }
        runCatching {
            ensureRuntime()
            download(MODEL_URL, modelFile(), "Modelo Omni", 0.05f, 0.70f, MODEL_BYTES)
            download(MMPROJ_URL, mmprojFile(), "Projetor visão/áudio", 0.70f, 0.95f, MMPROJ_BYTES)
            if (!isInstalled()) error("Instalação incompleta.")
            _status.value = OnDeviceLlmStatus(
                phase = "ready",
                detail = "LFM3-Omni pronto neste celular.",
                progress = 1f,
                ready = true,
            )
            true
        }.getOrElse { error ->
            Log.w(TAG, "Instalação LFM falhou", error)
            _status.value = OnDeviceLlmStatus(
                phase = "error",
                detail = "Falha: ${error.message}",
                progress = _status.value.progress,
            )
            false
        }
    }

    private fun ensureRuntime() {
        if (runtimeVersionOk() && serverBinary()?.canExecute() == true) return
        // Old builds (e.g. b11867) reject architecture "lfm3" — wipe and fetch a build with Omni.
        if (runtimeDir.exists()) {
            _status.value = _status.value.copy(phase = "extract", detail = "Atualizando runtime llama.cpp…", progress = 0.01f)
            runtimeDir.deleteRecursively()
            runtimeDir.mkdirs()
        }
        val archive = File(root, "llama-android-$RUNTIME_TAG.tar.gz")
        download(RUNTIME_URL, archive, "Runtime llama.cpp $RUNTIME_TAG", 0f, 0.05f, RUNTIME_BYTES)
        _status.value = _status.value.copy(phase = "extract", detail = "Extraindo llama-server…", progress = 0.05f)
        extractTarGz(archive, runtimeDir)
        archive.delete()
        val server = serverBinary() ?: error("llama-server não encontrado no pacote Android.")
        server.setExecutable(true, false)
        runtimeDir.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file.name.startsWith("lib") || file.extension == "so" || !file.name.contains(".")) {
                runCatching { file.setExecutable(true, false) }
            }
        }
        runtimeVersionFile().writeText(RUNTIME_TAG)
    }

    private fun download(url: String, dest: File, label: String, start: Float, end: Float, expected: Long) {
        if (dest.exists() && dest.length() > expected * 0.95) {
            _status.value = _status.value.copy(
                phase = "download",
                detail = "$label já baixado.",
                progress = end,
            )
            return
        }
        val partial = File(dest.absolutePath + ".part")
        partial.parentFile?.mkdirs()
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} em $label")
            val body = response.body ?: error("Corpo vazio ($label)")
            val total = body.contentLength().takeIf { it > 0 } ?: expected
            body.byteStream().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var read = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (read - lastEmit > 2_000_000L || read == total) {
                            lastEmit = read
                            val frac = (read.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                            val progress = start + (end - start) * frac
                            _status.value = OnDeviceLlmStatus(
                                phase = "download",
                                detail = "$label ${formatGb(read)} / ${formatGb(total)}",
                                progress = progress,
                            )
                        }
                    }
                }
            }
        }
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
    }

    private fun extractTarGz(archive: File, outDir: File) {
        outDir.mkdirs()
        TarArchiveInputStream(BufferedInputStream(GzipCompressorInputStream(FileInputStreamCompat(archive)))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val out = File(outDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                    if (entry.name.contains("llama-server") || entry.name.endsWith(".so")) {
                        out.setExecutable(true, false)
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    private fun findBinary(name: String): File? {
        return runtimeDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == name }
            ?.also { it.setExecutable(true, false) }
    }

    private fun freeBytes(): Long = runCatching {
        StatFs(app.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    private fun formatGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format("%.1f GB", gb) else String.format("%.0f MB", bytes / (1024.0 * 1024.0))
    }

    /** Avoid GZIPInputStream ambiguity — commons compress gzip wrapper. */
    private class FileInputStreamCompat(file: File) : java.io.FileInputStream(file)

    init {
        refresh()
    }

    companion object {
        const val TAG = "DzzLfmDevice"
        const val PORT = 11434
        const val BASE_URL = "http://127.0.0.1:$PORT"

        // b11941+ has LFM3-Omni; pin a current Android arm64 build.
        const val RUNTIME_TAG = "b12017"
        private const val RUNTIME_URL =
            "https://github.com/ggml-org/llama.cpp/releases/download/$RUNTIME_TAG/llama-$RUNTIME_TAG-bin-android-arm64.tar.gz"
        private const val RUNTIME_BYTES = 75_000_000L

        private const val MODEL_FILE = "LFM3-Omni-2B-Q4_0.gguf"
        private const val MMPROJ_FILE = "mmproj-LFM3-Omni-2B-Q4_0.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/main/$MODEL_FILE"
        private const val MMPROJ_URL =
            "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/main/$MMPROJ_FILE"
        private const val MODEL_BYTES = 1_049_617_920L
        private const val MMPROJ_BYTES = 270_576_544L
    }
}
