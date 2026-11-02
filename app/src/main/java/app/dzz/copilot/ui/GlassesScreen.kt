package app.dzz.copilot.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.dzz.copilot.glasses.GlassesService
import app.dzz.copilot.glasses.LinkPhase
import app.dzz.copilot.hub
import app.dzz.copilot.voice.AuraPhase

@Composable
fun GlassesScreen(onSettings: () -> Unit) {
    val context = LocalContext.current
    val hub = context.hub()
    val snap by hub.glasses.snapshot.collectAsState()
    val devices by hub.glasses.devices.collectAsState()
    val paired by hub.glasses.paired.collectAsState()
    val aura by hub.aura.state.collectAsState()
    val progress by hub.media.progress.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) afterPermission?.invoke()
    }

    fun requestThen(permissions: List<String>, block: () -> Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) block() else {
            afterPermission = block
            permission.launch(missing.toTypedArray())
        }
    }

    fun ensureScan() {
        requestThen(blePermissions()) {
            val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                hub.glasses.startScan(showAll)
            }
        }
    }

    LaunchedEffect(Unit) {
        requestThen(blePermissions() + listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.READ_SMS,
        )) {
            hub.glasses.connectPairedIfIdle()
            if (hub.store.keepAlive.value || hub.store.auraEnabled.value) {
                GlassesService.start(context)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        item {
            PageHeader(
                title = "Óculos",
                subtitle = if (paired.any { it.audioRoute } && snap.phase != LinkPhase.Ready) {
                    "Som no fone dos óculos. Canal de foto ainda abrindo."
                } else {
                    "Feche o app DZZ oficial antes de conectar."
                },
                action = { HeaderIconButton(Icons.Filled.Settings, "Ajustes", onSettings) },
            )
        }
        item {
            SectionCard("Aura") {
                PillRow {
                    StatusPill(
                        when (aura.phase) {
                            AuraPhase.Listening -> "Ouvindo"
                            AuraPhase.Awake -> "Pode falar"
                            AuraPhase.Running -> "Executando"
                            AuraPhase.Off -> "Parada"
                        },
                        accent = aura.phase == AuraPhase.Listening || aura.phase == AuraPhase.Awake,
                    )
                    if (aura.glassesMic) StatusPill("Mic óculos", accent = true)
                }
                Text(
                    aura.reply,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Paper,
                )
                if (aura.heard.isNotBlank()) {
                    Text("Ouvi: ${aura.heard}", style = mutedStyle())
                }
                Text(
                    "Diga Aura e o pedido. Com escuta ao vivo, continue sem repetir o nome.",
                    style = mutedStyle(),
                )
            }
        }
        item {
            SectionCard("Comandos") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    TwoCol(
                        first = {
                            FeatureCard("Foto", "Aura, tira uma foto") { hub.aura.runPhrase("Aura tira uma foto") }
                        },
                        second = {
                            FeatureCard("Vídeo", "Aura, grava um vídeo") { hub.aura.runPhrase("Aura grava um vídeo") }
                        },
                    )
                    TwoCol(
                        first = {
                            FeatureCard("O que é isso", "Objeto, placa ou letreiro") { hub.aura.runPhrase("Aura o que é isso") }
                        },
                        second = {
                            FeatureCard("Explica", "Olha a cena pelos óculos") { hub.aura.runPhrase("Aura explica") }
                        },
                    )
                    TwoCol(
                        first = {
                            FeatureCard("Traduz", "Modo tradução por voz") { hub.aura.runPhrase("Aura traduz") }
                        },
                        second = {
                            FeatureCard("Olhar", "Descrição local da cena") { hub.aura.runPhrase("Aura o que está acontecendo") }
                        },
                    )
                    TwoCol(
                        first = {
                            FeatureCard("Agenda", "Compromissos do celular") { hub.aura.runPhrase("Aura agenda") }
                        },
                        second = {
                            FeatureCard("Mensagens", "SMS e notificações") { hub.aura.runPhrase("Aura mensagens") }
                        },
                    )
                    TwoCol(
                        first = {
                            FeatureCard("Bateria", "Carga dos óculos") { hub.aura.runPhrase("Aura bateria") }
                        },
                        second = {
                            FeatureCard("Achar", "Faz os óculos se anunciarem") { hub.aura.runPhrase("Aura onde estão os óculos") }
                        },
                    )
                }
            }
        }
        item {
            SectionCard(snap.name ?: "Nenhum óculos") {
                Text(snap.status, style = MaterialTheme.typography.bodyLarge)
                Text(
                    batteryLine(snap.battery, snap.charging),
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (!snap.firmware.isNullOrBlank()) Text(snap.firmware.orEmpty(), style = mutedStyle())
                Text("Modo: ${snap.mode}", style = mutedStyle())
                if (snap.audioPackets > 0) Text("Pacotes de áudio: ${snap.audioPackets}", style = mutedStyle())
                if (!snap.lastError.isNullOrBlank()) {
                    Text(snap.lastError.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Buscar", snap.phase != LinkPhase.Connecting) { ensureScan() }
                    QuietButton("Desconectar", snap.phase != LinkPhase.Idle) {
                        hub.glasses.disconnect()
                    }
                }
                if (snap.phase == LinkPhase.Linked ||
                    (snap.phase == LinkPhase.Ready && !snap.qcChannel)
                ) {
                    WideButton("Reabrir canal QC") {
                        hub.glasses.reopenQcChannel()
                    }
                    WideButton("Forçar reconexão QC") {
                        hub.glasses.forceQcReconnect(manual = true)
                    }
                    Text(
                        "Sem QC não há foto, visão nem importação. Feche o app DZZ oficial por completo, aproxime os óculos e use Reabrir ou Forçar reconexão. O app também tenta sozinho.",
                        style = mutedStyle(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mostrar aparelhos sem nome", modifier = Modifier.weight(1f))
                    Switch(checked = showAll, onCheckedChange = { showAll = it })
                }
            }
        }
        if (paired.isNotEmpty()) {
            item { Text("Já pareado no Android", style = MaterialTheme.typography.titleMedium) }
            items(paired, key = { "paired-${it.address}" }) { hit ->
                SectionCard(hit.name) {
                    Text(hit.address, style = mutedStyle())
                    Text(
                        if (hit.audioRoute) "Som do celular já sai neste óculos" else "Pareado neste celular",
                        style = mutedStyle(),
                    )
                    WideButton("Conectar o canal") {
                        hub.glasses.connect(hit.address, hit.name)
                        GlassesService.start(context)
                    }
                }
            }
        }
        if (devices.isNotEmpty()) {
            item { Text("Por perto", style = MaterialTheme.typography.titleMedium) }
            items(devices, key = { it.address }) { hit ->
                SectionCard(hit.name) {
                    Text("${hit.address} · ${hit.rssi} dBm", style = mutedStyle())
                    Text(if (hit.likely) "Parece um óculos compatível" else "Toque só se souber que é o seu óculos", style = mutedStyle())
                    WideButton("Conectar") {
                        hub.glasses.connect(hit.address, hit.name)
                        GlassesService.start(context)
                    }
                }
            }
        }
        item {
            val ready = snap.phase == LinkPhase.Ready && (snap.qcChannel || snap.alternateChannel)
            SectionCard("Controles") {
                Text(
                    if (ready) "Os comandos vão pelo canal Bluetooth dos óculos."
                    else "Os controles acendem quando o canal de comando estiver pronto.",
                    style = mutedStyle(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Foto", ready) { hub.glasses.takePhoto() }
                    ActionButton("Vídeo", ready) { hub.glasses.startVideo() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton("Parar vídeo", ready) { hub.glasses.stopVideo() }
                    QuietButton("Achar", ready) { hub.glasses.findGlasses() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton("Bateria", ready) { hub.glasses.refreshBattery() }
                    QuietButton("Hora", ready) { hub.glasses.syncTime() }
                }
            }
        }
        item {
            SectionCard("Fotos e vídeos") {
                Text(progress.message)
                if (!snap.wifiSsid.isNullOrBlank()) {
                    Text("Rede ${snap.wifiSsid}", style = mutedStyle())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        "Importar",
                        snap.phase == LinkPhase.Ready && (snap.qcChannel || snap.alternateChannel),
                    ) {
                        requestThen(wifiPermissions()) { hub.media.import(manualNetwork = false) }
                    }
                    QuietButton("Rede atual") { hub.media.import(manualNetwork = true) }
                }
                if (progress.running) {
                    TextButton(onClick = { hub.media.cancel() }) { Text("Cancelar importação") }
                }
            }
        }
    }
}

private fun batteryLine(level: Int?, charging: Boolean): String {
    if (level == null) return "Bateria ainda não lida"
    return if (charging) "Bateria $level% · carregando" else "Bateria $level%"
}

private fun blePermissions(): List<String> {
    val items = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 31) {
        items += Manifest.permission.BLUETOOTH_SCAN
        items += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        items += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= 33) items += Manifest.permission.POST_NOTIFICATIONS
    return items
}

private fun wifiPermissions(): List<String> {
    val items = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) items += Manifest.permission.NEARBY_WIFI_DEVICES
    return items
}
