package app.dzz.copilot.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.dzz.copilot.copilot.LocalLlmService
import app.dzz.copilot.glasses.GlassesService
import app.dzz.copilot.hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PHONE_PERMISSIONS = buildList {
    add(android.Manifest.permission.READ_CONTACTS)
    add(android.Manifest.permission.SEND_SMS)
    add(android.Manifest.permission.CALL_PHONE)
    add(android.Manifest.permission.READ_CALENDAR)
    add(android.Manifest.permission.WRITE_CALENDAR)
    add(android.Manifest.permission.READ_SMS)
    add(android.Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT <= 32) add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun phonePermissionsGranted(context: android.content.Context): Boolean = PHONE_PERMISSIONS.all {
    androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private val languages = listOf("pt", "en", "es", "fr", "zh")
private val voices = listOf("Leda", "Kore", "Aoede", "Puck", "Zephyr", "Charon", "Fenrir", "Orus")

private val settingsTitles = mapOf(
    "oculos" to "Óculos",
    "voz" to "Voz e conversa",
    "conexoes" to "Conexões e plugins",
    "celular" to "Celular e iniciativa",
    "mensagens" to "E-mail e Telegram",
    "voce" to "Você e memória",
    "cerebro" to "Cérebro e nuvem",
    "diagnostico" to "Diagnóstico",
)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hub = context.hub()
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = section != null) { section = null }
    val keep by hub.store.keepAlive.collectAsState()
    val speak by hub.store.speakAloud.collectAsState()
    val places by hub.store.placesArmed.collectAsState()
    val alternate by hub.store.alternateChannel.collectAsState()
    val aura by hub.store.auraEnabled.collectAsState()
    val autoImport by hub.store.autoImport.collectAsState()
    val voiceName by hub.store.voiceName.collectAsState()
    val voiceBoost by hub.store.voiceBoost.collectAsState()
    val from by hub.store.translateFrom.collectAsState()
    val to by hub.store.translateTo.collectAsState()
    val key by hub.store.geminiKey.collectAsState()
    val routerKey by hub.store.openRouterKey.collectAsState()
    val groqKey by hub.store.groqKey.collectAsState()
    val siliconKey by hub.store.siliconFlowKey.collectAsState()
    val ollamaKey by hub.store.ollamaKey.collectAsState()
    val localLfm by hub.store.localLfmEnabled.collectAsState()
    val localLfmUrl by hub.store.localLfmUrl.collectAsState()
    val localLfmModel by hub.store.localLfmModel.collectAsState()
    val privateReasoning by hub.store.privateReasoning.collectAsState()
    var facts by remember { mutableStateOf(hub.copilot.memory.all()) }
    var draftFact by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf(hub.copilot.mind.profile()) }
    var profileSaved by remember { mutableStateOf(true) }
    val onlyLocal by hub.store.onlyLocal.collectAsState()
    val log by hub.glasses.diagnostics.collectAsState()
    val healthIssues by hub.aura.health.issues.collectAsState()
    val glassesMicOn by hub.store.glassesMic.collectAsState()
    val glassesAlways by hub.store.glassesMicAlways.collectAsState()
    val conversationMic by hub.store.conversationMic.collectAsState()
    val localWake by hub.store.localWake.collectAsState()
    val glassesMicStatus by hub.aura.glassesMic.status.collectAsState()
    val liveVoice by hub.store.liveVoice.collectAsState()
    val liveModel by hub.store.liveModel.collectAsState()
    var draftLiveModel by remember(liveModel) { mutableStateOf(liveModel) }
    val emailAddress by hub.store.emailAddress.collectAsState()
    var draftEmail by remember(emailAddress) { mutableStateOf(emailAddress) }
    var draftEmailPass by remember { mutableStateOf("") }
    var emailStatus by remember { mutableStateOf("") }
    val emailScope = rememberCoroutineScope()
    val proactiveOn by hub.store.proactive.collectAsState()
    val announceOn by hub.store.announceMessages.collectAsState()
    val briefHour by hub.store.briefHour.collectAsState()
    var phoneGranted by remember { mutableStateOf(phonePermissionsGranted(context)) }
    val askPhone = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { phoneGranted = phonePermissionsGranted(context) }
    val tgToken by hub.store.telegramToken.collectAsState()
    val tgChat by hub.store.telegramChatId.collectAsState()
    var draftTg by remember(tgToken) { mutableStateOf(tgToken) }
    var tgStatus by remember { mutableStateOf(hub.telegram.bot.status) }
    var tgCode by remember { mutableStateOf(hub.telegram.bot.pairingCode) }
    LaunchedEffect(Unit) {
        while (true) {
            tgStatus = hub.telegram.bot.status
            tgCode = hub.telegram.bot.pairingCode
            delay(1_500)
        }
    }
    var draftKey by remember(key) { mutableStateOf(key) }
    var draftRouter by remember(routerKey) { mutableStateOf(routerKey) }
    var draftGroq by remember(groqKey) { mutableStateOf(groqKey) }
    var draftSilicon by remember(siliconKey) { mutableStateOf(siliconKey) }
    var draftOllama by remember(ollamaKey) { mutableStateOf(ollamaKey) }
    var draftLocalUrl by remember(localLfmUrl) { mutableStateOf(localLfmUrl) }
    var draftLocalModel by remember(localLfmModel) { mutableStateOf(localLfmModel) }
    var localStatus by remember { mutableStateOf("") }
    val deviceLlm by hub.onDeviceLlm.status.collectAsState()
    val llmLog by hub.onDeviceLlm.diagnostics.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { hub.onDeviceLlm.refresh() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        if (section == null) {
            item {
                PageHeader(
                    title = "Ajustes",
                    subtitle = "Toque numa categoria para abrir",
                )
            }
            item {
                ActionRow(
                    title = "Óculos",
                    detail = "Conexão, microfone e palavra \"Aura\"",
                    icon = Icons.Filled.Visibility,
                    accent = false,
                    onClick = { section = "oculos" },
                )
            }
            item {
                ActionRow(
                    title = "Voz e conversa",
                    detail = "Voz da Aura, conversa ao vivo, foco na sua voz",
                    icon = Icons.Filled.RecordVoiceOver,
                    accent = false,
                    onClick = { section = "voz" },
                )
            }
            item {
                ActionRow(
                    title = "Conexões e plugins",
                    detail = "MCPs (GitHub, Notion, Google…) e plugins",
                    icon = Icons.Filled.Hub,
                    accent = false,
                    onClick = { section = "conexoes" },
                )
            }
            item {
                ActionRow(
                    title = "Celular e iniciativa",
                    detail = "Permissões, acesso ao celular, avisos da Aura",
                    icon = Icons.Filled.PhoneAndroid,
                    accent = false,
                    onClick = { section = "celular" },
                )
            }
            item {
                ActionRow(
                    title = "E-mail e Telegram",
                    detail = "Caixa de e-mail e bot do Telegram",
                    icon = Icons.Filled.Email,
                    accent = false,
                    onClick = { section = "mensagens" },
                )
            }
            item {
                ActionRow(
                    title = "Você e memória",
                    detail = "Sobre você e o que a Aura lembra",
                    icon = Icons.Filled.Person,
                    accent = false,
                    onClick = { section = "voce" },
                )
            }
            item {
                ActionRow(
                    title = "Cérebro e nuvem",
                    detail = "Modelo local no celular e chaves de nuvem",
                    icon = Icons.Filled.Cloud,
                    accent = false,
                    onClick = { section = "cerebro" },
                )
            }
            item {
                ActionRow(
                    title = "Diagnóstico",
                    detail = "Registro de conexão e alarmes",
                    icon = Icons.Filled.BugReport,
                    accent = false,
                    onClick = { section = "diagnostico" },
                )
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeaderIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") { section = null }
                    Text(
                        settingsTitles[section ?: ""].orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        color = Paper,
                        modifier = Modifier.padding(start = Space.sm),
                    )
                }
            }
            when (section) {
                "oculos" -> {
                item {
                    SectionCard("Conexão") {
                        ToggleRow("Manter conexão em segundo plano", keep) { enabled ->
                            hub.store.setKeepAlive(enabled)
                            if (enabled && !hub.store.deviceAddress.value.isNullOrBlank()) {
                                GlassesService.start(context)
                            } else if (!enabled) {
                                GlassesService.stop(context)
                            }
                        }
                        ToggleRow("Tentar canal alternativo", alternate) { hub.store.setAlternateChannel(it) }
                        ToggleRow("Escutar a palavra Aura", aura) { enabled ->
                            hub.store.setAuraEnabled(enabled)
                            if (enabled) GlassesService.start(context) else hub.aura.stop()
                        }
                        Text(
                            "Aura ouve pelo microfone do celular (nunca pelo mic dos óculos — isso evitava o LED piscando). As respostas saem no som dos óculos via A2DP. Diga Aura; com escuta ao vivo desligada, o próximo pedido cabe em ~25s.",
                            style = mutedStyle(),
                        )
                        Text(
                            "O canal alternativo só deve ser ligado se o óculos não mostrar de5bf72a e você quiser testar. Pode confundir outro aparelho Bluetooth.",
                            style = mutedStyle(),
                        )
                        TextButton(onClick = { hub.glasses.forget() }) { Text("Esquecer óculos pareado") }
                    }
                }
                item {
                    SectionCard("Microfone e palavra \"Aura\"") {
                        Text("Microfone durante a conversa", style = MaterialTheme.typography.titleSmall)
                        listOf(
                            app.dzz.copilot.data.MIC_CALL to "Óculos como fone Bluetooth (recomendado: contínuo, sem bipes)",
                            app.dzz.copilot.data.MIC_GLASSES to "Microfone de IA dos óculos (um bipe a cada vez que você fala)",
                            app.dzz.copilot.data.MIC_PHONE to "Microfone do celular",
                        ).forEach { (code, label) ->
                            TextButton(onClick = { hub.store.setConversationMic(code) }) {
                                Text(if (code == conversationMic) "● $label" else "○ $label")
                            }
                        }
                        Text(
                            "Como fone, a voz da Aura sai pelo canal de chamada dos óculos (um pouco menos encorpada) e o microfone " +
                                "fica aberto a conversa inteira. Os óculos limitam o microfone de IA a uns 5 segundos por vez e apitam " +
                                "cada vez que ele abre; por isso ele só é usado se a chamada não estiver disponível.",
                            style = mutedStyle(),
                        )
                        Text(
                            "Ouve você pelo microfone dos óculos (o mesmo que o app original usa) em vez do celular. " +
                                "Gasta mais bateria dos óculos. Se os óculos pararem de mandar áudio, a Aura volta para o microfone do celular sozinha.",
                            style = mutedStyle(),
                        )
                        ToggleRow("Ouvir pelos óculos na conversa", glassesMicOn) { hub.store.setGlassesMic(it) }
                        if (glassesMicOn) {
                            Text("Status: $glassesMicStatus", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Os óculos apitam cada vez que o microfone deles abre ou fecha. Por isso, esperando o \"Aura\" quem escuta " +
                                    "é o celular; os óculos abrem o microfone só na conversa e só na sua vez de falar. " +
                                    "O botão de IA dos óculos também abre uma conversa.",
                                style = mutedStyle(),
                            )
                            ToggleRow("Microfone dos óculos sempre aberto (apita)", glassesAlways) { hub.store.setGlassesMicAlways(it) }
                        }
                        ToggleRow("Reconhecer \"Aura\" no próprio celular", localWake) { hub.store.setLocalWake(it) }
                        Text(
                            "Ligado: o celular entende \"Aura\" sem internet e só manda para a nuvem o que vem depois. " +
                                "Sem internet, a Aura usa o que o celular entendeu. Modelo local: ${hub.aura.localAsr.status}.",
                            style = mutedStyle(),
                        )
                    }
                }
                }
                "voz" -> {
                item {
                    SectionCard("Copiloto") {
                        ToggleRow("Falar as respostas", speak) { hub.store.setSpeakAloud(it) }
                        ToggleRow("Importar mídia ao conectar", autoImport) { enabled ->
                            hub.store.setAutoImport(enabled)
                            if (enabled && hub.glasses.snapshot.value.phase == app.dzz.copilot.glasses.LinkPhase.Ready) {
                                hub.media.import(manualNetwork = false)
                            }
                        }
                        ToggleRow("Avisar quando eu passar por um lugar salvo", places) { enabled ->
                            hub.store.setPlacesArmed(enabled)
                            if (enabled) GlassesService.start(context)
                        }
                        Text(
                            "Com importação automática, ao ficar Ready a Aura pede o Wi‑Fi dos óculos e baixa fotos/vídeos para o álbum.",
                            style = mutedStyle(),
                        )
                        Text("Voz da Aura (Gemini TTS)", style = mutedStyle())
                        VoiceRow(voiceName) { hub.store.setVoiceName(it) }
                        Text("Volume nos óculos", style = mutedStyle())
                        BoostRow(voiceBoost) { hub.store.setVoiceBoost(it) }
                        Text(
                            "Alto e Máximo amplificam o áudio em STREAM_MUSIC (A2DP). O canal SCO/HFP fica desligado de propósito para o mic dos óculos não piscar.",
                            style = mutedStyle(),
                        )
                        Text("Traduzir de", style = mutedStyle())
                        LanguageRow(from) { hub.store.setLanguages(it, to) }
                        Text("para", style = mutedStyle())
                        LanguageRow(to) { hub.store.setLanguages(from, it) }
                        Text(
                            "Diga Aura, traduz para entrar no modo conversa. Idiomas acima valem para o modo e para Aura, traduz [frase].",
                            style = mutedStyle(),
                        )
                    }
                }
                item {
                    SectionCard("Conversa ao vivo") {
                        Text(
                            "Depois de \"Aura\", a conversa segue natural com a voz escolhida acima: você fala, ela responde na hora e você pode interromper. " +
                                "Usa a chave do Gemini e as mesmas ferramentas da Aura. Diga tchau para encerrar; depois de 20 segundos de silêncio ela volta a esperar \"Aura\".",
                            style = mutedStyle(),
                        )
                        ToggleRow("Conversa ao vivo (Gemini Live)", liveVoice) { hub.store.setLiveVoice(it) }
                        OutlinedTextField(
                            value = draftLiveModel,
                            onValueChange = { draftLiveModel = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Modelo ao vivo") },
                            singleLine = true,
                        )
                        WideButton("Salvar modelo") { hub.store.setLiveModel(draftLiveModel) }
                        Text(
                            when {
                                onlyLocal -> "Desligada enquanto “Tudo só no celular” estiver ativo."
                                key.isBlank() -> "Falta a chave do Gemini em Ajustes."
                                else -> "Se o modelo não abrir, a Aura tenta outros e volta ao jeito antigo por 2 minutos."
                            },
                            style = mutedStyle(),
                        )
                    }
                }
                item { VoiceFocusCard() }
                }
                "conexoes" -> {
                item { ConnectionsCard() }
                item { PluginsCard() }
                }
                "celular" -> {
                item {
                    SectionCard("Acesso ao celular") {
                        Text(
                            "Deixa a Aura ver contatos, mandar SMS, ligar, responder conversas pelas notificações, criar compromissos e alarmes, " +
                                "achar arquivos e ler a tela. Antes de mandar algo ou ligar para alguém ela sempre pergunta.",
                            style = mutedStyle(),
                        )
                        Text(
                            if (phoneGranted) "Permissões do celular: concedidas." else "Permissões do celular: faltando alguma.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        WideButton("Dar permissões") { askPhone.launch(PHONE_PERMISSIONS.toTypedArray()) }
                        WideButton("Ler notificações (para responder)") {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                        WideButton("Acessibilidade (ler a tela)") {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        WideButton("Sobrepor outros apps (abrir apps e alarmes)") {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")),
                            )
                        }
                        Text(
                            "Acessibilidade: " + if (app.dzz.copilot.phone.AuraAccessibility.enabled) "ligada." else "desligada.",
                            style = mutedStyle(),
                        )
                    }
                }
                item {
                    SectionCard("Iniciativa da Aura") {
                        Text(
                            "A Aura fala sem você chamar: resumo da manhã (agenda e mensagens), aviso 10 minutos antes de cada compromisso " +
                                "e bateria fraca dos óculos. Nunca entre 22h e 7h. Depois de um aviso, dá para responder sem dizer \"Aura\".",
                            style = mutedStyle(),
                        )
                        ToggleRow("Falar por iniciativa própria", proactiveOn) { hub.store.setProactive(it) }
                        ToggleRow("Avisar mensagens novas (WhatsApp, Telegram, SMS)", announceOn) { hub.store.setAnnounceMessages(it) }
                        Text("Resumo da manhã a partir de", style = mutedStyle())
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(6, 7, 8, 9, 10).forEach { hour ->
                                TextButton(onClick = { hub.store.setBriefHour(hour) }) {
                                    Text(if (hour == briefHour) "● ${hour}h" else "${hour}h")
                                }
                            }
                        }
                    }
                }
                }
                "mensagens" -> {
                item {
                    SectionCard("E-mail") {
                        Text(
                            "Para a Aura ler e mandar e-mail. Use uma senha de app (no Gmail: Conta Google, Segurança, Senhas de app), nunca a senha da conta. " +
                                "Ela sempre pergunta antes de mandar.",
                            style = mutedStyle(),
                        )
                        OutlinedTextField(
                            value = draftEmail,
                            onValueChange = { draftEmail = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Endereço") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = draftEmailPass,
                            onValueChange = { draftEmailPass = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (hub.store.emailPassword.value.isBlank()) "Senha de app" else "Senha de app (salva; digite para trocar)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        WideButton("Salvar e testar") {
                            hub.store.setEmail(draftEmail, draftEmailPass.ifBlank { hub.store.emailPassword.value })
                            draftEmailPass = ""
                            emailStatus = "Testando..."
                            emailScope.launch {
                                val error = withContext(Dispatchers.IO) {
                                    app.dzz.copilot.email.EmailClient(hub.store.emailAddress.value, hub.store.emailPassword.value).test()
                                }
                                emailStatus = if (error == null) "Funcionando." else "Falhou: $error"
                            }
                        }
                        if (emailStatus.isNotBlank()) Text(emailStatus, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    SectionCard("Telegram") {
                        Text(
                            "Fale com a Aura pelo Telegram: texto, foto, áudio e arquivos. Crie um bot no @BotFather, cole o token aqui, " +
                                "gere o código e mande /start CÓDIGO para o bot. Só esse chat passa a ser obedecido.",
                            style = mutedStyle(),
                        )
                        OutlinedTextField(
                            value = draftTg,
                            onValueChange = { draftTg = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Token do bot") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        WideButton("Salvar token") { hub.store.setTelegramToken(draftTg) }
                        Text(
                            "Status: $tgStatus" + if (tgChat != 0L) " · pareado" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (tgToken.isNotBlank() && tgChat == 0L) {
                            WideButton("Gerar código de pareamento") { tgCode = hub.telegram.bot.newPairingCode() }
                            tgCode?.let {
                                Text("Mande para o bot:  /start $it", fontFamily = FontFamily.Monospace, color = Copper)
                            }
                        }
                        if (tgChat != 0L) {
                            TextButton(onClick = { hub.store.setTelegramChatId(0L) }) { Text("Desparear este chat") }
                        }
                        if (onlyLocal) Text("Desligado enquanto “Tudo só no celular” estiver ativo.", style = mutedStyle())
                    }
                }
                }
                "voce" -> {
                item {
                    SectionCard("Sobre você") {
                        Text(
                            "A Aura lê isto em toda conversa: quem você é, seus projetos, pessoas importantes, o que é urgente e como prefere as respostas. Fica só neste celular, mas vai junto com o pedido para o modelo que responder.",
                            style = mutedStyle(),
                        )
                        OutlinedTextField(
                            value = profile,
                            onValueChange = {
                                profile = it
                                profileSaved = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            label = { Text("Seu arquivo de contexto") },
                        )
                        WideButton(if (profileSaved) "Salvo" else "Salvar", enabled = !profileSaved) {
                            hub.copilot.mind.setProfile(profile)
                            profileSaved = true
                        }
                    }
                }
                item {
                    SectionCard("Memória da Aura") {
                        Text(
                            "Fica neste celular. Diga Aura, lembra que, e o fato. Depois pergunte: Aura, o que combinei com o João? Para usar um fato na resposta, só o trecho relevante vai ao modelo, que pode ser da nuvem.",
                            style = mutedStyle(),
                        )
                        OutlinedTextField(
                            value = draftFact,
                            onValueChange = { draftFact = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Novo fato") },
                        )
                        WideButton("Guardar", enabled = draftFact.isNotBlank()) {
                            hub.copilot.memory.add(draftFact)
                            draftFact = ""
                            facts = hub.copilot.memory.all()
                        }
                        if (facts.isEmpty()) {
                            Text("Nada guardado ainda.", style = mutedStyle())
                        }
                        facts.forEach { fact ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(fact.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = {
                                    hub.copilot.memory.delete(fact.id)
                                    facts = hub.copilot.memory.all()
                                }) { Text("Apagar") }
                            }
                        }
                        if (facts.isNotEmpty()) {
                            TextButton(onClick = {
                                hub.copilot.memory.clear()
                                facts = hub.copilot.memory.all()
                            }) { Text("Apagar toda a memória") }
                        }
                    }
                }
                }
                "cerebro" -> {
                item {
                    SectionCard("Cérebro local no celular (LFM3-Omni)") {
                        Text(
                            "Tudo no Android: o app baixa llama-server + LFM3-Omni Q4 (~1,4 GB) e sobe em 127.0.0.1:11434. Tool calling, texto e visão. Ordem: local → nuvem.",
                            style = mutedStyle(),
                        )
                        ToggleRow("Usar LFM local primeiro", localLfm) { hub.store.setLocalLfmEnabled(it) }
                        ToggleRow("Decisões e análises só no celular", privateReasoning) { hub.store.setPrivateReasoning(it) }
                        ToggleRow("Tudo só no celular (sem nuvem)", onlyLocal) { hub.store.setOnlyLocal(it) }
                        Text(
                            "Desligado: dilemas e análises vão primeiro para a nuvem, que raciocina melhor, e o celular fica para quando não houver internet. Ligado: nada disso sai do aparelho, mas a resposta fica mais lenta e mais simples.",
                            style = mutedStyle(),
                        )
                        Text(
                            "${deviceLlm.phase} · ${deviceLlm.detail}",
                            style = mutedStyle(),
                        )
                        if (deviceLlm.progress > 0f && deviceLlm.progress < 1f) {
                            Text(
                                "Progresso ${(deviceLlm.progress * 100).toInt()}%",
                                style = mutedStyle(),
                            )
                        }
                        WideButton("Instalar e iniciar no celular") {
                            LocalLlmService.installAndStart(context)
                        }
                        WideButton("Só iniciar (já instalado)") {
                            LocalLlmService.start(context)
                        }
                        WideButton("Parar servidor local") {
                            LocalLlmService.stop(context)
                        }
                        WideButton("Testar 127.0.0.1") {
                            hub.store.setLocalLfmUrl("http://127.0.0.1:11434")
                            hub.store.setLocalLfmModel("LFM3-Omni-2B")
                            localStatus = "Testando…"
                            scope.launch {
                                localStatus = withContext(Dispatchers.IO) { hub.localLfm.ping() }
                            }
                        }
                        WideButton("Atualizar status / ler server.log") {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    hub.onDeviceLlm.refresh()
                                    hub.onDeviceLlm.appendServerLogTail()
                                }
                            }
                        }
                        if (localStatus.isNotBlank()) {
                            Text(localStatus, style = mutedStyle())
                        }
                        Text("Log do cérebro local", style = mutedStyle())
                        Text(
                            llmLog.ifBlank { "Sem eventos ainda. Toque em Iniciar e depois em Atualizar status." },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { clipboard.setText(AnnotatedString(llmLog)) }) {
                            Text("Copiar log do LFM")
                        }
                        OutlinedTextField(
                            value = draftLocalUrl,
                            onValueChange = { draftLocalUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("URL (avançado)") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = draftLocalModel,
                            onValueChange = { draftLocalModel = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Nome do modelo (API)") },
                            singleLine = true,
                        )
                        WideButton("Salvar URL avançada") {
                            hub.store.setLocalLfmUrl(draftLocalUrl)
                            hub.store.setLocalLfmModel(draftLocalModel)
                        }
                        Text(
                            "Wi‑Fi estável na 1ª instalação. Depois funciona offline. Note 10+ precisa de ~2 GB livres.",
                            style = mutedStyle(),
                        )
                    }
                }
                item {
                    SectionCard("Nuvem") {
                        Text(
                            "Fallback: Groq → Gemini → SiliconFlow → Ollama Cloud → OpenRouter. Visão: LFM local → Gemini. Chaves criptografadas neste celular.",
                            style = mutedStyle(),
                        )
                        OutlinedTextField(
                            value = draftKey,
                            onValueChange = { draftKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Google AI Studio / Gemini") },
                            singleLine = true,
                        )
                        WideButton("Salvar Gemini") { hub.store.setGeminiKey(draftKey) }
                        OutlinedTextField(
                            value = draftGroq,
                            onValueChange = { draftGroq = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Chave do Groq") },
                            singleLine = true,
                        )
                        WideButton("Salvar Groq") { hub.store.setGroqKey(draftGroq) }
                        OutlinedTextField(
                            value = draftSilicon,
                            onValueChange = { draftSilicon = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Chave do SiliconFlow") },
                            singleLine = true,
                        )
                        WideButton("Salvar SiliconFlow") { hub.store.setSiliconFlowKey(draftSilicon) }
                        OutlinedTextField(
                            value = draftOllama,
                            onValueChange = { draftOllama = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Chave do Ollama Cloud") },
                            singleLine = true,
                        )
                        WideButton("Salvar Ollama") { hub.store.setOllamaKey(draftOllama) }
                        OutlinedTextField(
                            value = draftRouter,
                            onValueChange = { draftRouter = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Chave do OpenRouter") },
                            singleLine = true,
                        )
                        WideButton("Salvar OpenRouter") { hub.store.setOpenRouterKey(draftRouter) }
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("Permitir leitura das notificações") }
                    }
                }
                }
                "diagnostico" -> {
                item {
                    SectionCard("Autodiagnóstico") {
                        Text(
                            "A Aura confere óculos, Bluetooth, fotos, internet e ferramentas a cada 30 segundos e corrige " +
                                "sozinha o que for seguro (reconectar, reabrir o canal). Você também pode dizer \"Aura, faz um autodiagnóstico\".",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (healthIssues.isEmpty()) "Nenhum problema encontrado até agora." else hub.aura.health.text(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    SectionCard("Diagnóstico") {
                        Text(
                            log.ifBlank { "Nada registrado ainda." },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { clipboard.setText(AnnotatedString(log)) }) { Text("Copiar registro") }
                        if (Build.VERSION.SDK_INT >= 31) {
                            TextButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                            }) { Text("Permitir alarmes no horário exato") }
                        }
                    }
                }
                item {
                    Text(
                        "Aura Glasses fala com óculos do canal QC deste aparelho. Sem o canal QC o status fica “Sem canal QC” (só bateria padrão). Foto, visão e importação exigem QC. Visão contínua em vídeo, OTA e Opus nativo do mic QC ficam fora deste firmware/app. Escuta = mic do celular; fala = A2DP nos óculos (sem SCO, para o LED do mic não entrar em loop).",
                        style = mutedStyle(),
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LanguageRow(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        languages.forEach { code ->
            TextButton(onClick = { onSelect(code) }) {
                Text(if (code == selected) code.uppercase() else code)
            }
        }
    }
}

@Composable
private fun VoiceRow(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        voices.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { name ->
                    TextButton(onClick = { onSelect(name) }) {
                        Text(if (name == selected) "● $name" else name)
                    }
                }
            }
        }
    }
}

private val boostLevels = listOf(
    "normal" to "Normal",
    "alto" to "Alto",
    "max" to "Máximo",
)

@Composable
private fun BoostRow(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        boostLevels.forEach { (code, label) ->
            TextButton(onClick = { onSelect(code) }) {
                Text(if (code == selected) "● $label" else label)
            }
        }
    }
}
