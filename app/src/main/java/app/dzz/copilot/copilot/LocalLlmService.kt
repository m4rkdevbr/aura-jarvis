package app.dzz.copilot.copilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.system.Os
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.dzz.copilot.MainActivity
import app.dzz.copilot.R
import app.dzz.copilot.hub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs llama-server on-device at 127.0.0.1:11434 so [LocalLfmBrain] can use LFM3-Omni.
 */
class LocalLlmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var watchdog: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startAsForeground("Preparando cérebro local…")
        val action = intent?.action ?: ACTION_START
        hub().onDeviceLlm.log("LocalLlmService action=$action")
        when (action) {
            ACTION_STOP -> {
                stopServer("Parado pelo usuário.")
                hub().onDeviceLlm.endStart()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_INSTALL -> scope.launch {
                try {
                    installThenServe()
                } finally {
                    hub().onDeviceLlm.endStart()
                }
            }
            else -> {
                if (process?.isAlive == true || hub().onDeviceLlm.probeHealth()) {
                    hub().onDeviceLlm.log("Servidor já ativo — ignorando START duplicado.")
                    hub().onDeviceLlm.markServer(true)
                    hub().onDeviceLlm.endStart()
                    return START_STICKY
                }
                scope.launch {
                    try {
                        serveOnly()
                    } finally {
                        hub().onDeviceLlm.endStart()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        hub().onDeviceLlm.log("FGS dataSync timeout — encerrando llama-server.")
        stopServer("Timeout do sistema.")
        hub().onDeviceLlm.endStart()
        stopSelf()
    }

    override fun onDestroy() {
        stopServer("Serviço destruído.")
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun installThenServe() {
        val runtime = hub().onDeviceLlm
        val ok = runtime.install()
        if (!ok) {
            updateNotification(runtime.status.value.detail)
            return
        }
        bindPrefs()
        startServer(runtime)
    }

    private suspend fun serveOnly() {
        val runtime = hub().onDeviceLlm
        runtime.refresh()
        if (!runtime.isInstalled()) {
            runtime.markServer(false, "Instale o modelo antes de iniciar o servidor.")
            updateNotification("Modelo não instalado.")
            stopSelf()
            return
        }
        bindPrefs()
        startServer(runtime)
    }

    private fun bindPrefs() {
        val store = hub().store
        // Enable local brain, but do not stomp a custom LAN URL/model the user set.
        store.setLocalLfmEnabled(true)
        val url = store.localLfmUrl.value.trim()
        if (url.isBlank() || url.contains("127.0.0.1") || url.contains("localhost")) {
            store.setLocalLfmUrl(OnDeviceLlmRuntime.BASE_URL)
        }
        if (store.localLfmModel.value.isBlank()) {
            store.setLocalLfmModel("LFM3-Omni-2B")
        }
    }

    private fun startServer(runtime: OnDeviceLlmRuntime) {
        stopServer(null)
        runtime.killStaleServers()
        if (runtime.probeHealth()) {
            runtime.log("Porta ainda ocupada após pkill — aguardando liberar…")
            Thread.sleep(1_200)
            runtime.killStaleServers()
        }
        val binary = runtime.serverBinary() ?: run {
            runtime.markServer(false, "llama-server ausente.")
            stopSelf()
            return
        }
        val model = runtime.modelFile()
        val mmproj = runtime.mmprojFile()
        val libDir = binary.parentFile ?: File(binary.absolutePath).parentFile
        // Android 10+ blocks exec from writable filesDir (error=13). Prefer linker64.
        runCatching { Os.chmod(binary.absolutePath, 448) } // 0700
        runCatching { binary.setExecutable(true, false) }
        libDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".so")) {
                runCatching { Os.chmod(file.absolutePath, 420) } // 0644
            }
        }
        val logFile = runtime.serverLogFile()
        runCatching { logFile.writeText("") }
        val args = listOf(
            "-m", model.absolutePath,
            "--mmproj", mmproj.absolutePath,
            "--host", "127.0.0.1",
            "--port", OnDeviceLlmRuntime.PORT.toString(),
            "-c", "4096",
            "-ngl", "0",
            // Measured on the Note 10+: 4 threads decode 65% faster than 8, which pull in the small cores.
            "-t", THREADS,
            "-tb", THREADS,
            "--reasoning", "off",
            "--jinja",
            "-np", "1",
        )
        val linker = File("/system/bin/linker64")
        val command = if (linker.canExecute()) {
            listOf(linker.absolutePath, binary.absolutePath) + args
        } else {
            listOf(binary.absolutePath) + args
        }
        runtime.log("cmd: ${command.joinToString(" ")}")
        runtime.log("cwd=${libDir.absolutePath}")
        val builder = ProcessBuilder(command)
            .directory(libDir)
            .redirectErrorStream(true)
        builder.environment()["LD_LIBRARY_PATH"] = listOfNotNull(
            libDir.absolutePath,
            builder.environment()["LD_LIBRARY_PATH"],
        ).joinToString(":")
        process = try {
            builder.start()
        } catch (error: Exception) {
            runtime.log("Exec direto falhou (${error.message}); tentando via sh…")
            process = try {
                val shellCmd = buildString {
                    append("export LD_LIBRARY_PATH='${libDir.absolutePath}'; ")
                    append("cd '${libDir.absolutePath}' && ")
                    if (linker.canExecute()) {
                        append("'${linker.absolutePath}' '${binary.absolutePath}' ")
                    } else {
                        append("'${binary.absolutePath}' ")
                    }
                    append(args.joinToString(" ") { "'$it'" })
                }
                runtime.log("sh -c $shellCmd")
                ProcessBuilder("sh", "-c", shellCmd).redirectErrorStream(true).start()
            } catch (fallback: Exception) {
                runtime.markServer(false, "Não iniciou: ${fallback.message}")
                runtime.appendServerLogTail()
                updateNotification("Falha ao iniciar llama-server.")
                stopSelf()
                return
            }
            process
        }
        // Tee stdout into server.log + in-app diagnostics.
        scope.launch {
            val proc = process ?: return@launch
            runCatching {
                proc.inputStream.bufferedReader().use { reader ->
                    logFile.outputStream().bufferedWriter().use { writer ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            writer.appendLine(line)
                            writer.flush()
                            runtime.log(line.take(240))
                        }
                    }
                }
            }
        }
        runtime.markServer(true, "Subindo LFM3-Omni…")
        updateNotification("LFM3-Omni iniciando…")
        watchdog?.cancel()
        watchdog = scope.launch {
            repeat(60) {
                delay(1_500)
                if (runtime.probeHealth()) {
                    runtime.markServer(true, "LFM3-Omni ativo em ${OnDeviceLlmRuntime.BASE_URL}")
                    updateNotification("LFM3-Omni ativo em ${OnDeviceLlmRuntime.BASE_URL}")
                    return@launch
                }
                if (process?.isAlive != true) {
                    runtime.markServer(false, "Processo llama-server morreu ao carregar.")
                    runtime.appendServerLogTail()
                    updateNotification("Cérebro local falhou — veja log em Ajustes.")
                    stopSelf()
                    return@launch
                }
            }
            if (!runtime.probeHealth()) {
                runtime.markServer(false, "Timeout aguardando /health.")
                runtime.appendServerLogTail()
                updateNotification("Timeout do cérebro local.")
            }
            while (isActive) {
                delay(5_000)
                val alive = process?.isAlive == true
                val health = runtime.probeHealth()
                if (!alive && !health) {
                    runtime.markServer(false, "Servidor local encerrou.")
                    runtime.appendServerLogTail()
                    updateNotification("Cérebro local parado.")
                    break
                }
                if (health) runtime.markServer(true)
            }
        }
    }

    private fun stopServer(reason: String?) {
        watchdog?.cancel()
        watchdog = null
        runCatching {
            process?.destroy()
            if (Build.VERSION.SDK_INT >= 26) {
                process?.waitFor(2, TimeUnit.SECONDS)
                if (process?.isAlive == true) process?.destroyForcibly()
            }
        }
        process = null
        runCatching { hub().onDeviceLlm.killStaleServers() }
        if (reason != null) {
            runCatching { hub().onDeviceLlm.markServer(false, reason) }
        }
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalLlmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Aura · cérebro local")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Parar", stop)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cérebro local LFM3",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "dzz_local_llm"
        private const val NOTIFICATION_ID = 47
        private const val THREADS = "4"
        const val ACTION_INSTALL = "app.dzz.copilot.llm.INSTALL"
        const val ACTION_START = "app.dzz.copilot.llm.START"
        const val ACTION_STOP = "app.dzz.copilot.llm.STOP"

        fun installAndStart(context: Context) {
            val intent = Intent(context, LocalLlmService::class.java).setAction(ACTION_INSTALL)
            ContextCompat.startForegroundService(context, intent)
        }

        fun start(context: Context) {
            val intent = Intent(context, LocalLlmService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocalLlmService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
