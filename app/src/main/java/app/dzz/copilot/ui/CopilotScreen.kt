package app.dzz.copilot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.dzz.copilot.agenda.TaskText
import app.dzz.copilot.copilot.LookResult
import app.dzz.copilot.glasses.LinkPhase
import app.dzz.copilot.hub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

@Composable
fun CopilotScreen() {
    val context = LocalContext.current
    val hub = context.hub()
    val scope = rememberCoroutineScope()
    val snap by hub.glasses.snapshot.collectAsState()
    val meetingOn by hub.copilot.meetingOn.collectAsState()
    val lines by hub.copilot.meetingLines.collectAsState()
    val partial by hub.copilot.partial.collectAsState()
    var useGlasses by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var result by remember { mutableStateOf<LookResult?>(null) }
    var status by remember { mutableStateOf("Aponte e pergunte o que está acontecendo.") }
    var busy by remember { mutableStateOf(false) }
    val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasCamera by remember { mutableStateOf(cameraGranted) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) hub.copilot.listenOnce(
            onText = { heard ->
                val parsed = TaskText.parse(heard)
                if (parsed != null) {
                    hub.vault.addTask(parsed.title, parsed.dueAt)
                    val whenText = TaskText.formatWhen(parsed.dueAt)
                    hub.copilot.speak("Anotei ${parsed.title}. $whenText.")
                    status = "Tarefa: ${parsed.title}"
                }
            },
            onError = { status = it },
        )
    }

    LaunchedEffect(follow, hasCamera, useGlasses) {
        if (!follow || useGlasses || !hasCamera) return@LaunchedEffect
        while (true) {
            delay(12_000)
            val shot = capture
            if (shot != null && !busy) {
                busy = true
                takePhoto(
                    context.cacheDir,
                    shot,
                    onFile = { file ->
                        scope.launch {
                            val look = hub.copilot.describeFile(file)
                            val previous = result?.labels?.toSet()
                            result = look
                            if (previous != look.labels.toSet()) {
                                status = look.speech
                                hub.copilot.speak(look.speech)
                            }
                            busy = false
                        }
                    },
                    onError = { message ->
                        scope.launch {
                            status = message
                            busy = false
                        }
                    },
                )
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Copiloto", style = MaterialTheme.typography.titleLarge)
            Text("Visão sob demanda. O acompanhamento pela câmera do celular só fala quando a cena muda.", style = mutedStyle())
        }
        item {
            if (!useGlasses && hasCamera) {
                CameraPreview(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(22.dp))) { capture = it }
            } else if (result?.bitmap != null) {
                Image(
                    bitmap = result!!.bitmap!!.asImageBitmap(),
                    contentDescription = "Última cena",
                    modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(22.dp)),
                )
            }
        }
        item {
            SectionCard("Olhar") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(if (useGlasses) "Celular" else "Celular · agora") { useGlasses = false }
                    QuietButton(if (useGlasses) "Óculos · agora" else "Óculos") { useGlasses = true }
                }
                Text(status)
                WideButton(if (busy) "Olhando…" else "O que está acontecendo?", !busy) {
                    if (useGlasses) {
                        if (snap.phase != LinkPhase.Ready || !(snap.qcChannel || snap.alternateChannel)) {
                            status = "Os óculos ainda não estão prontos para uma foto."
                            return@WideButton
                        }
                        busy = true
                        scope.launch {
                            val bytes = hub.glasses.captureThumbnail()
                            if (bytes == null) {
                                status = "A miniatura não chegou. Tente de novo ou use a câmera do celular."
                            } else {
                                val look = hub.copilot.describeBytes(bytes)
                                result = look
                                status = look.speech
                                hub.copilot.speak(look.speech)
                            }
                            busy = false
                        }
                    } else {
                        if (!hasCamera) {
                            askCamera.launch(Manifest.permission.CAMERA)
                            return@WideButton
                        }
                        val shot = capture ?: return@WideButton
                        busy = true
                        takePhoto(
                            context.cacheDir,
                            shot,
                            onFile = { file ->
                                scope.launch {
                                    val look = hub.copilot.describeFile(file)
                                    result = look
                                    status = look.speech
                                    hub.copilot.speak(look.speech)
                                    busy = false
                                }
                            },
                            onError = { message ->
                                scope.launch {
                                    status = message
                                    busy = false
                                }
                            },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton("Explicar melhor", result?.bitmap != null && !busy) {
                        val current = result ?: return@QuietButton
                        busy = true
                        scope.launch {
                            val richer = hub.copilot.explain(current.bitmap, current.text, current.labels)
                            status = richer?.let { "${it.provider}: ${it.text}" }
                                ?: "Sem chave do Gemini ou do OpenRouter, ou a nuvem não respondeu. A leitura local continua acima."
                            if (richer != null) hub.copilot.speak(richer.text)
                            busy = false
                        }
                    }
                    QuietButton(if (follow) "Parar acompanhamento" else "Acompanhar", !useGlasses && hasCamera) {
                        follow = !follow
                    }
                }
                if (result?.text?.isNotBlank() == true) {
                    Text("Texto lido", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(result?.text.orEmpty())
                    WideButton("Traduzir texto") {
                        val text = result?.text.orEmpty()
                        scope.launch {
                            status = "Baixando o idioma, se ainda não estiver no celular…"
                            val translated = runCatching {
                                hub.copilot.translate(text, hub.store.translateFrom.value, hub.store.translateTo.value)
                            }.getOrElse { it.message ?: "A tradução falhou" }
                            status = translated
                            hub.copilot.speak(translated)
                        }
                    }
                    WideButton("Explicar o texto", !busy) {
                        val text = result?.text.orEmpty()
                        busy = true
                        scope.launch {
                            val explained = hub.copilot.explainText(text)
                            status = explained?.let { "${it.provider}: ${it.text}" }
                                ?: "Sem chave, ou a nuvem não respondeu."
                            if (explained != null) hub.copilot.speak(explained.text)
                            busy = false
                        }
                    }
                }
            }
        }
        item {
            SectionCard("Voz e tarefas") {
                Text("Ou diga: Aura, me lembre de comprar leite amanhã às 9.", style = mutedStyle())
                WideButton("Anotar por voz") {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        hub.copilot.listenOnce(
                            onText = { heard ->
                                val parsed = TaskText.parse(heard)
                                if (parsed != null) {
                                    hub.vault.addTask(parsed.title, parsed.dueAt)
                                    hub.copilot.speak("Anotei ${parsed.title}.")
                                    status = "Tarefa: ${parsed.title}"
                                } else {
                                    status = heard
                                }
                            },
                            onError = { status = it },
                        )
                    }
                }
            }
        }
        item {
            SectionCard(if (meetingOn) "Reunião em andamento" else "Reunião ou aula") {
                if (partial.isNotBlank()) Text(partial, style = mutedStyle())
                if (lines.isEmpty()) Text("O áudio fica no celular. No fim, dá para extrair tarefas.", style = mutedStyle())
                lines.takeLast(6).forEach { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(if (meetingOn) "Encerrar" else "Começar") {
                        if (meetingOn) {
                            val body = hub.copilot.stopMeeting()
                            if (body.isNotBlank()) {
                                hub.vault.addMeeting("Reunião", body)
                                status = "Reunião salva na agenda."
                            }
                        } else {
                            val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            if (mic != PackageManager.PERMISSION_GRANTED) {
                                askMic.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                hub.copilot.startMeeting()
                            }
                        }
                    }
                    QuietButton("Virar tarefas", lines.isNotEmpty()) {
                        TaskText.extractActionLines(lines).forEach { line ->
                            val parsed = TaskText.parse(line)
                            if (parsed != null) hub.vault.addTask(parsed.title, parsed.dueAt, notes = line)
                        }
                        status = "Tarefas extraídas para a agenda."
                    }
                    QuietButton("Resumir", lines.isNotEmpty() && !meetingOn && !busy) {
                        busy = true
                        scope.launch {
                            val brief = hub.copilot.summarizeMeeting(lines.joinToString("\n"))
                            if (brief == null) {
                                status = "Sem chave, ou a nuvem não resumiu a reunião."
                            } else {
                                brief.tasks.forEach { hub.vault.addTask(it, null) }
                                status = brief.summary
                                hub.copilot.speak(brief.summary)
                            }
                            busy = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(modifier: Modifier, onReady: (ImageCapture) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(owner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val listener = Runnable {
            val provider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            onReady(imageCapture)
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private val photoExecutor = Executors.newSingleThreadExecutor()

private fun takePhoto(
    cacheDir: File,
    capture: ImageCapture,
    onFile: (File) -> Unit,
    onError: (String) -> Unit = {},
) {
    val file = File(cacheDir, "look-${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    capture.takePicture(
        options,
        photoExecutor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onFile(file)
            override fun onError(exception: ImageCaptureException) {
                onError(exception.message ?: "A câmera não conseguiu fotografar")
            }
        },
    )
}
