package app.dzz.copilot.glasses

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.MediaStore
import app.dzz.copilot.protocol.MediaManifest
import app.dzz.copilot.protocol.QcProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import kotlin.coroutines.resume

data class ImportProgress(
    val running: Boolean = false,
    val message: String = "Nada importado ainda",
    val done: Int = 0,
    val total: Int = 0,
)

class MediaSync(
    context: Context,
    private val glasses: GlassesClient,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    fun import(manualNetwork: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch { runImport(manualNetwork) }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun runImport(manualNetwork: Boolean) {
        var held: HeldNetwork? = null
        var saved = 0
        var total = 0
        try {
            _progress.value = ImportProgress(true, "Pedindo o modo de transferência…", 0, 0)
            if (!manualNetwork) {
                glasses.send(QcProtocol.CONTROL, QcProtocol.transfer(), 4_000)
                _progress.value = ImportProgress(true, "Aguardando Wi‑Fi dos óculos…", 0, 0)
                val creds = withTimeoutOrNull(14_000) {
                    var ssid: String? = null
                    var password: String? = null
                    repeat(28) {
                        val snap = glasses.snapshot.value
                        if (!snap.wifiSsid.isNullOrBlank()) {
                            ssid = snap.wifiSsid
                            password = snap.wifiPassword
                            return@withTimeoutOrNull ssid to password
                        }
                        kotlinx.coroutines.delay(500)
                    }
                    null
                }
                val ssid = creds?.first
                val password = creds?.second
                if (!ssid.isNullOrBlank() && Build.VERSION.SDK_INT >= 29) {
                    if (password.isNullOrBlank()) {
                        _progress.value = ImportProgress(
                            false,
                            "Os óculos não enviaram a senha do Wi‑Fi. Conecte manualmente e use “importar da rede atual”.",
                        )
                        return
                    }
                    _progress.value = ImportProgress(true, "Conectando ao Wi-Fi $ssid…", 0, 0)
                    held = withTimeoutOrNull(25_000) { joinWifi(ssid, password) }
                    if (held == null) {
                        _progress.value = ImportProgress(
                            false,
                            "O celular não entrou no Wi-Fi dos óculos. Conecte manualmente e use “importar da rede atual”.",
                        )
                        return
                    }
                } else if (ssid.isNullOrBlank()) {
                    _progress.value = ImportProgress(
                        false,
                        "Não recebi o SSID dos óculos a tempo. Conecte no Wi‑Fi deles e importe da rede atual.",
                    )
                    return
                }
            }
            val host = glasses.snapshot.value.wifiIp
            val network = held?.network
            val found = probe(network, hosts(host))
            if (found == null) {
                _progress.value = ImportProgress(
                    false,
                    "Não achei a lista de arquivos. Confirme que o celular está no Wi-Fi dos óculos.",
                )
                return
            }
            val (base, names) = found
            total = names.size
            _progress.value = ImportProgress(true, "Baixando 0 de $total", 0, total)
            val client = httpClient(network, readSeconds = 60)
            for ((index, name) in names.withIndex()) {
                if (job?.isActive != true) break
                val url = mediaUrl(base, name)
                val display = name.substringAfterLast('/')
                val mime = mimeOf(display)
                val ok = download(client, url, display, mime)
                if (ok) saved += 1
                _progress.value = ImportProgress(true, "Baixando ${index + 1} de $total", saved, total)
            }
            _progress.value = ImportProgress(false, "Importadas $saved de $total para a galeria.", saved, total)
        } catch (_: kotlinx.coroutines.CancellationException) {
            _progress.value = ImportProgress(false, "Importação cancelada. Salvas $saved de $total.", saved, total)
        } catch (error: Exception) {
            _progress.value = ImportProgress(false, error.message ?: "Falha na importação", saved, total)
        } finally {
            held?.close()
            glasses.fire(QcProtocol.CONTROL, QcProtocol.transferStop())
        }
    }

    private suspend fun probe(network: Network?, hosts: List<String>): Pair<String, List<String>>? {
        val client = httpClient(network, readSeconds = 6)
        val paths = listOf("/files/media.config", "/manifest.json", "/files/manifest.json")
        for (host in hosts) {
            for (path in paths) {
                val body = getText(client, "http://$host$path") ?: continue
                val names = MediaManifest.parse(body)
                if (names.isNotEmpty()) return host to names
            }
        }
        return null
    }

    private fun download(client: OkHttpClient, url: String, displayName: String, mime: String): Boolean {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val uri = insert(displayName, mime) ?: return false
            val resolver = appContext.contentResolver
            resolver.openOutputStream(uri)?.use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            } ?: return false
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            return true
        }
    }

    private fun insert(displayName: String, mime: String): android.net.Uri? {
        val resolver = appContext.contentResolver
        val image = mime.startsWith("image/")
        val video = mime.startsWith("video/")
        val collection = when {
            image && Build.VERSION.SDK_INT >= 29 -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            image -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            video && Build.VERSION.SDK_INT >= 29 -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            Build.VERSION.SDK_INT >= 29 -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Files.getContentUri("external")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                val folder = if (image || video) "DCIM/DZZ Copiloto" else "Download/DZZ Copiloto"
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return resolver.insert(collection, values)
    }

    private fun getText(client: OkHttpClient, url: String): String? {
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()?.take(200_000)
            }
        }.getOrNull()
    }

    private fun httpClient(network: Network?, readSeconds: Long): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .apply { if (network != null) socketFactory(network.socketFactory) }
            .build()
    }

    private suspend fun joinWifi(ssid: String, password: String): HeldNetwork? {
        if (Build.VERSION.SDK_INT < 29) return null
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return suspendCancellableCoroutine { continuation ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password.ifBlank { "123456789" })
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            lateinit var callback: ConnectivityManager.NetworkCallback
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!continuation.isActive) {
                        runCatching { connectivity.unregisterNetworkCallback(this) }
                        return
                    }
                    connectivity.bindProcessToNetwork(network)
                    continuation.resume(HeldNetwork(connectivity, network, callback))
                }

                override fun onUnavailable() {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            continuation.invokeOnCancellation {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                connectivity.bindProcessToNetwork(null)
            }
            connectivity.requestNetwork(request, callback, 20_000)
        }
    }

    private fun hosts(hint: String?): List<String> {
        return buildList {
            if (!hint.isNullOrBlank()) add(hint)
            add("192.168.4.1")
            add("192.168.43.1")
            add("192.168.49.1")
            add("192.168.31.1")
            add("192.168.1.1")
        }.distinct()
    }

    private fun mediaUrl(host: String, relative: String): String {
        val encoded = relative.split("/").joinToString("/") {
            URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
        }
        return "http://$host/files/$encoded"
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "wav" -> "audio/wav"
        "aac", "m4a" -> "audio/mp4"
        "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private class HeldNetwork(
        private val connectivity: ConnectivityManager,
        val network: Network,
        private val callback: ConnectivityManager.NetworkCallback,
    ) {
        fun close() {
            connectivity.bindProcessToNetwork(null)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }
}
