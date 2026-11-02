package app.dzz.copilot.health

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import app.dzz.copilot.brain.Schema
import app.dzz.copilot.brain.SimpleTool
import app.dzz.copilot.brain.Tool
import app.dzz.copilot.data.PreferencesStore
import app.dzz.copilot.glasses.GlassesClient
import app.dzz.copilot.glasses.LinkPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One thing the monitor noticed, and what it did about it. */
data class HealthIssue(val at: Long, val area: String, val problem: String, val action: String = "")

/**
 * Self-diagnosis and self-repair. Every 30 s it looks at the glasses link, BLE write drops, photo
 * failures, internet and keys; when something is stuck it repairs what is safe to repair (reconnect,
 * reopen the command channel) and writes the diagnosis to Ajustes > Diagnóstico. Aura can also run it
 * on request through the self_check tool.
 */
class HealthMonitor(
    context: Context,
    private val store: PreferencesStore,
    private val glasses: GlassesClient,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _issues = MutableStateFlow<List<HealthIssue>>(emptyList())
    val issues: StateFlow<List<HealthIssue>> = _issues.asStateFlow()

    /** Spoken line when a repair brought something back (set by the app; may stay silent). */
    @Volatile var say: (String) -> Unit = {}

    private var job: Job? = null
    private var downSince = 0L
    private var noChannelSince = 0L
    private var lastRepairAt = 0L
    private var lastDrops = 0
    private var repairedLink = false
    private var lastNet = true
    private var liveFailStreak = 0
    private val toolFails = HashMap<String, Int>()
    private val toolTimes = HashMap<String, ArrayDeque<Long>>()

    fun start() {
        if (job != null) return
        job = scope.launch {
            delay(15_000)
            while (isActive) {
                runCatching { check() }.onFailure { Log.w(TAG, "check: ${it.message}") }
                delay(30_000)
            }
        }
    }

    fun note(area: String, problem: String, action: String = "") {
        val issue = HealthIssue(System.currentTimeMillis(), area, problem, action)
        Log.i(TAG, "$area: $problem ${if (action.isNotBlank()) "→ $action" else ""}")
        _issues.value = (_issues.value + issue).takeLast(MAX_ISSUES)
    }

    @Synchronized
    fun toolDone(name: String, ms: Long, ok: Boolean) {
        val times = toolTimes.getOrPut(name) { ArrayDeque() }
        times.addLast(ms)
        while (times.size > 5) times.removeFirst()
        val streak = if (ok) 0 else (toolFails[name] ?: 0) + 1
        toolFails[name] = streak
        if (!ok && HealthRules.toolBroken(streak)) {
            note("Ferramenta", "$name falhou $streak vezes seguidas", "vou avisar e sugerir outro caminho")
        }
    }

    fun liveEnded(reason: String, opened: Boolean) {
        if (opened && !reason.startsWith("falha") && reason != "a conexão caiu") {
            liveFailStreak = 0
            return
        }
        liveFailStreak += 1
        val action = if (opened) "a próxima chamada abre uma conversa nova" else "usando o cérebro antigo por alguns minutos"
        note("Conversa ao vivo", "terminou com problema: $reason", action)
        if (liveFailStreak >= 2 && !online()) note("Internet", "a conversa ao vivo caiu e o celular está sem internet")
    }

    private fun check() {
        val snap = glasses.snapshot.value
        val now = SystemClock.elapsedRealtime()
        val hasDevice = !store.deviceAddress.value.isNullOrBlank()
        downSince = if (snap.phase == LinkPhase.Idle || snap.phase == LinkPhase.Failed) downSince.takeIf { it > 0 } ?: now else 0L
        noChannelSince = if (snap.phase == LinkPhase.Linked) noChannelSince.takeIf { it > 0 } ?: now else 0L
        val drops = glasses.writeDrops
        val newDrops = drops - lastDrops
        lastDrops = drops
        if (snap.phase == LinkPhase.Ready && repairedLink) {
            repairedLink = false
            note("Óculos", "conexão restabelecida")
            say("Os óculos voltaram a conectar.")
        }
        val repair = HealthRules.glassesRepair(
            hasDevice = hasDevice && store.auraEnabled.value,
            userDisconnected = glasses.userDisconnected,
            phase = snap.phase,
            downForMs = if (downSince > 0) now - downSince else 0,
            noChannelForMs = if (noChannelSince > 0) now - noChannelSince else 0,
            newDrops = newDrops,
            photoFailStreak = glasses.photoFailStreak,
            sinceLastRepairMs = if (lastRepairAt == 0L) Long.MAX_VALUE else now - lastRepairAt,
        )
        when (repair) {
            Repair.Reconnect -> {
                lastRepairAt = now
                repairedLink = true
                note("Óculos", "desconectados há mais de ${(now - downSince) / 1000} s", "reconectando sozinha")
                glasses.reconnectSaved()
            }
            Repair.ReopenChannel -> {
                lastRepairAt = now
                repairedLink = true
                val why = when {
                    snap.phase == LinkPhase.Linked -> "Bluetooth ligado mas sem canal de comando"
                    newDrops >= HealthRules.DROPS_PER_CHECK -> "$newDrops comandos perdidos no Bluetooth"
                    else -> "${glasses.photoFailStreak} fotos seguidas não chegaram"
                }
                note("Óculos", why, "reabrindo o canal de comando")
                glasses.reopenQcChannel()
            }
            Repair.None -> Unit
        }
        val net = online()
        if (net != lastNet) {
            lastNet = net
            if (net) note("Internet", "voltou") else note("Internet", "sem conexão", "respostas pelo modelo local até voltar")
        }
    }

    private fun online(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    /** Full check, spoken by Aura when the user asks "faz um autodiagnóstico". */
    fun report(fix: Boolean): String = buildString {
        val snap = glasses.snapshot.value
        append("Autodiagnóstico da Aura:\n")
        val hasDevice = !store.deviceAddress.value.isNullOrBlank()
        append("- Óculos: ").append(
            when {
                !hasDevice -> "nenhum óculos salvo"
                snap.phase == LinkPhase.Ready -> "conectados" + (snap.battery?.let { ", bateria $it%" } ?: "")
                snap.phase == LinkPhase.Linked -> "Bluetooth ligado, mas sem canal de comando"
                else -> "desconectados (${snap.status})"
            },
        ).append('\n')
        if (glasses.writeDrops > 0) append("- Comandos perdidos no Bluetooth desde que abriu: ${glasses.writeDrops}\n")
        if (glasses.photoFailStreak > 0) append("- Fotos seguidas que falharam: ${glasses.photoFailStreak}\n")
        append("- Internet: ").append(if (online()) "ok" else "sem conexão").append('\n')
        val keys = listOf(
            "Gemini" to store.geminiKey.value,
            "Groq" to store.groqKey.value,
            "OpenRouter" to store.openRouterKey.value,
        ).filter { it.second.isNotBlank() }.map { it.first }
        append("- Chaves de IA: ").append(if (keys.isEmpty()) "nenhuma configurada (só o modelo local)" else keys.joinToString()).append('\n')
        if (store.onlyLocal.value) append("- Modo só local ligado: sem internet para a IA\n")
        synchronized(this@HealthMonitor) {
            toolFails.filterValues { HealthRules.toolBroken(it) }.keys.takeIf { it.isNotEmpty() }?.let {
                append("- Ferramentas falhando: ${it.joinToString()}\n")
            }
            toolTimes.mapValues { (_, t) -> t.average().toLong() }.filterValues { HealthRules.toolSlow(it) }
                .takeIf { it.isNotEmpty() }?.let { slow ->
                    append("- Ferramentas lentas: ${slow.entries.joinToString { "${it.key} ${it.value / 1000}s" }}\n")
                }
        }
        val recent = _issues.value.takeLast(5)
        if (recent.isNotEmpty()) {
            val clock = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
            append("Últimos registros:\n")
            recent.forEach { append("- ${clock.format(Date(it.at))} ${it.area}: ${it.problem}${if (it.action.isNotBlank()) " (${it.action})" else ""}\n") }
        }
        if (fix && hasDevice && !glasses.userDisconnected) {
            when (snap.phase) {
                LinkPhase.Ready -> if (glasses.photoFailStreak > 0) {
                    glasses.reopenQcChannel()
                    append("Correção: reabri o canal de comando dos óculos.\n")
                }
                LinkPhase.Linked -> {
                    glasses.reopenQcChannel()
                    append("Correção: reabrindo o canal de comando dos óculos.\n")
                }
                LinkPhase.Idle, LinkPhase.Failed -> {
                    glasses.reconnectSaved()
                    append("Correção: reconectando os óculos.\n")
                }
                else -> Unit
            }
            lastRepairAt = SystemClock.elapsedRealtime()
        }
    }

    /** Lines for Ajustes > Diagnóstico. */
    fun text(): String {
        val clock = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
        return _issues.value.reversed().joinToString("\n") {
            "${clock.format(Date(it.at))} ${it.area}: ${it.problem}${if (it.action.isNotBlank()) " → ${it.action}" else ""}"
        }
    }

    fun tools(): List<Tool> = listOf(
        SimpleTool(
            "self_check",
            "Fazer um autodiagnóstico da Aura (óculos, Bluetooth, fotos, internet, chaves, ferramentas lentas ou " +
                "falhando) e corrigir sozinha o que der. Use quando o usuário pedir diagnóstico ou algo não funcionar.",
            Schema.obj("fix" to org.json.JSONObject().put("type", "boolean").put("description", "true tenta corrigir")),
        ) { args -> report(args.optBoolean("fix", true)) },
    )

    private companion object {
        const val TAG = "AuraHealth"
        const val MAX_ISSUES = 60
    }
}
