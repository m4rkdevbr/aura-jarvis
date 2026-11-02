package app.dzz.copilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.dzz.copilot.hub
import app.dzz.copilot.mcp.AuraPlugin
import app.dzz.copilot.mcp.McpAuth
import app.dzz.copilot.mcp.McpCatalog
import app.dzz.copilot.mcp.McpCatalogEntry
import kotlinx.coroutines.launch

/** "Foco na sua voz": noise cleaning, calmer live listening and the voice print. */
@Composable
fun VoiceFocusCard() {
    val hub = LocalContext.current.hub()
    val noiseClean by hub.store.noiseClean.collectAsState()
    val liveCalm by hub.store.liveCalm.collectAsState()
    val onlyMe by hub.store.onlyMyVoice.collectAsState()
    val strictness by hub.store.voiceStrictness.collectAsState()
    val status by hub.aura.focus.status.collectAsState()
    val scope = rememberCoroutineScope()
    val samples = remember { mutableStateListOf<FloatArray>() }
    var result by remember { mutableStateOf("") }

    SectionCard("Foco na sua voz") {
        Text(
            "Para lugares com TV ou gente conversando. Tudo roda no celular; sua voz não sai dele.",
            style = mutedStyle(),
        )
        SwitchLine("Limpar ruído (ventilador, rua, chiado)", noiseClean) { hub.store.setNoiseClean(it) }
        SwitchLine("Ignorar vozes distantes na conversa ao vivo", liveCalm) { hub.store.setLiveCalm(it) }
        SwitchLine("Ouvir só a minha voz", onlyMe && status.enrolled) { on ->
            if (status.enrolled) hub.store.setOnlyMyVoice(on) else result = "Cadastre sua voz primeiro."
        }
        if (status.enrolled) {
            Text("Rigor do filtro", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Leve", "Normal", "Forte").forEachIndexed { i, label ->
                    TextButton(onClick = { hub.store.setVoiceStrictness(i) }) {
                        Text(if (i == strictness) "● $label" else label)
                    }
                }
            }
            status.lastScore?.let {
                Text("Última semelhança com sua voz: ${(it * 100).toInt()}%", style = mutedStyle())
            }
        }

        Text(
            if (status.enrolled) "Sua voz está cadastrada." else "Cadastro da voz: grave 3 trechos de 6 segundos falando normalmente, " +
                "de preferência com os óculos e num lugar calmo. A Aura precisa estar ligada.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!status.enrolled || samples.isNotEmpty()) {
            Text(
                when (samples.size) {
                    0 -> "Trecho 1: \"Aura, qual é a minha agenda de hoje e como está o tempo lá fora?\""
                    1 -> "Trecho 2: \"Quero que você me lembre de ligar para o banco amanhã às dez horas.\""
                    else -> "Trecho 3: conte em voz alta o que você fez hoje, do seu jeito."
                },
                style = mutedStyle(),
            )
            WideButton(if (status.busy) "Gravando…" else "Gravar trecho ${samples.size + 1} de 3", enabled = !status.busy) {
                scope.launch {
                    hub.aura.focus.recordSample().onSuccess { emb ->
                        samples += emb
                        if (samples.size >= 3) {
                            val consistency = hub.aura.focus.saveEnrollment(samples.toList())
                            samples.clear()
                            result = if (consistency < 0.5f) {
                                "Voz cadastrada, mas os trechos ficaram diferentes entre si. Se o filtro errar, refaça num lugar calmo."
                            } else {
                                "Voz cadastrada. O filtro \"só a minha voz\" está ligado."
                            }
                        }
                    }
                }
            }
        }
        if (status.enrolled && samples.isEmpty()) {
            TextButton(onClick = { hub.aura.focus.clearEnrollment(); result = "" }) { Text("Apagar e cadastrar de novo") }
        }
        val line = result.ifBlank { status.message }
        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodyMedium, color = Copper)
    }
}

/** MCP connections: catalog, manual address and the list of what is connected. */
@Composable
fun ConnectionsCard() {
    val hub = LocalContext.current.hub()
    val servers by hub.mcp.servers.collectAsState()
    var adding by remember { mutableStateOf<McpCatalogEntry?>(null) }
    var key by remember { mutableStateOf("") }
    var personalUrl by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var mName by remember { mutableStateOf("") }
    var mUrl by remember { mutableStateOf("") }
    var mKey by remember { mutableStateOf("") }
    var mHeader by remember { mutableStateOf("") }
    var mOAuth by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    SectionCard("Conexões (MCP)") {
        Text(
            "Cada conexão dá novas habilidades à Aura, por voz e no chat. Ela confirma com você antes de criar, enviar ou apagar algo, " +
                "e trata o que vem das conexões como informação, nunca como ordem.",
            style = mutedStyle(),
        )
        if (servers.isEmpty()) Text("Nenhuma conexão ainda.", style = MaterialTheme.typography.bodyMedium)
        servers.forEach { state ->
            val s = state.server
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold, color = Paper)
                        Text(
                            state.status.ifBlank { if (s.tools.isNotEmpty()) "${s.tools.size} ferramentas" else s.url },
                            style = mutedStyle(),
                        )
                    }
                    Switch(checked = s.enabled, onCheckedChange = { hub.mcp.setEnabled(s.id, it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.auth == McpAuth.OAuth) {
                        TextButton(enabled = !state.busy, onClick = { hub.mcp.login(s.id) }) {
                            Text(if (s.needsLogin) "Entrar" else "Entrar de novo")
                        }
                    }
                    TextButton(enabled = !state.busy, onClick = { hub.mcp.refresh(s.id) }) { Text("Atualizar") }
                    TextButton(onClick = { hub.mcp.remove(s.id) }) { Text("Remover") }
                }
            }
        }

        Text("Adicionar", style = MaterialTheme.typography.titleSmall, color = Paper)
        McpCatalog.entries.filter { e -> servers.none { it.server.catalogId == e.id } }.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, color = Paper)
                    Text(entry.description, style = mutedStyle())
                }
                TextButton(onClick = {
                    if (entry.auth == McpAuth.Token || entry.personalUrl) {
                        adding = entry; key = ""; personalUrl = ""
                    } else {
                        val id = hub.mcp.addFromCatalog(entry)
                        if (id != null && entry.auth == McpAuth.OAuth) hub.mcp.login(id)
                    }
                }) { Text(if (entry.auth == McpAuth.OAuth) "Conectar" else "Adicionar") }
            }
            if (adding?.id == entry.id) {
                Text(entry.keyHint, style = mutedStyle())
                if (entry.personalUrl) {
                    OutlinedTextField(personalUrl, { personalUrl = it }, label = { Text("Endereço (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(
                        key, { key = it }, label = { Text("Chave / token") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                }
                WideButton("Salvar ${entry.name}", enabled = key.isNotBlank() || personalUrl.startsWith("https://")) {
                    note = if (hub.mcp.addFromCatalog(entry, key, personalUrl) == null) "Endereço inválido (precisa começar com https://)." else ""
                    adding = null
                }
            }
        }

        TextButton(onClick = { manual = !manual }) { Text(if (manual) "Fechar conexão manual" else "Conexão manual (qualquer MCP remoto)") }
        if (manual) {
            OutlinedTextField(mName, { mName = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(mUrl, { mUrl = it }, label = { Text("Endereço MCP (https://…/mcp)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchLine("Login pelo navegador (OAuth)", mOAuth) { mOAuth = it }
            if (!mOAuth) {
                OutlinedTextField(
                    mKey, { mKey = it }, label = { Text("Chave (opcional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    mHeader, { mHeader = it }, label = { Text("Cabeçalho da chave (vazio = Authorization: Bearer)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            WideButton("Adicionar conexão", enabled = mUrl.trim().startsWith("https://")) {
                val auth = when {
                    mOAuth -> McpAuth.OAuth
                    mKey.isNotBlank() -> McpAuth.Token
                    else -> McpAuth.None
                }
                val id = hub.mcp.add(mName, mUrl, auth, mKey, mHeader)
                if (auth == McpAuth.OAuth) hub.mcp.login(id)
                mName = ""; mUrl = ""; mKey = ""; mHeader = ""; manual = false
            }
            Text("Só MCPs remotos (HTTP). Os que rodam como programa no computador não funcionam no celular.", style = mutedStyle())
        }
        if (note.isNotBlank()) Text(note, color = Copper)
    }
}

/** Plugins: connections plus instructions that teach Aura to use them. */
@Composable
fun PluginsCard() {
    val hub = LocalContext.current.hub()
    var installed by remember { mutableStateOf(hub.mcp.installedPlugins()) }
    var importing by remember { mutableStateOf(false) }
    var json by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    SectionCard("Plugins") {
        Text(
            "Um plugin junta conexões e instruções prontas. Depois de instalar, entre nas conexões que pedirem login ou chave.",
            style = mutedStyle(),
        )
        AuraPlugin.builtIn.forEach { plugin ->
            val on = plugin.id in installed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plugin.name, color = Paper, fontWeight = FontWeight.SemiBold)
                    Text(plugin.description, style = mutedStyle())
                }
                TextButton(onClick = {
                    if (on) hub.mcp.removePlugin(plugin.id) else hub.mcp.installPlugin(plugin)
                    installed = hub.mcp.installedPlugins()
                }) { Text(if (on) "Remover" else "Instalar") }
            }
        }
        installed.filter { id -> AuraPlugin.builtIn.none { it.id == id } }.forEach { id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(id, color = Paper, modifier = Modifier.weight(1f))
                TextButton(onClick = { hub.mcp.removePlugin(id); installed = hub.mcp.installedPlugins() }) { Text("Remover") }
            }
        }
        TextButton(onClick = { importing = !importing }) { Text(if (importing) "Fechar" else "Importar plugin (JSON)") }
        if (importing) {
            OutlinedTextField(
                json, { json = it }, label = { Text("{\"name\":…, \"instructions\":…, \"servers\":[{\"name\":…,\"url\":…}]}") },
                modifier = Modifier.fillMaxWidth(), minLines = 4,
            )
            WideButton("Instalar plugin", enabled = json.isNotBlank()) {
                val plugin = AuraPlugin.parse(json)
                if (plugin == null) {
                    note = "JSON inválido."
                } else {
                    hub.mcp.installPlugin(plugin)
                    installed = hub.mcp.installedPlugins()
                    json = ""; importing = false; note = "Plugin ${plugin.name} instalado."
                }
            }
        }
        if (note.isNotBlank()) Text(note, color = Copper)
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
