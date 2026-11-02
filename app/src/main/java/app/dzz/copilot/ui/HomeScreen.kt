package app.dzz.copilot.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import app.dzz.copilot.R
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dzz.copilot.agenda.TaskText
import app.dzz.copilot.glasses.LinkPhase
import app.dzz.copilot.hub
import app.dzz.copilot.voice.AuraPhase
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    onGlasses: () -> Unit,
    onAura: () -> Unit,
    onSee: () -> Unit,
    onTasks: () -> Unit,
    onAlbum: () -> Unit,
    onGuide: () -> Unit,
    onSettings: () -> Unit,
    onPanel: () -> Unit = {},
) {
    val context = LocalContext.current
    val hub = context.hub()
    val snap by hub.glasses.snapshot.collectAsState()
    val aura by hub.aura.state.collectAsState()
    val glassesMicOn by hub.store.glassesMic.collectAsState()
    val tasks by hub.vault.tasks.collectAsState()
    val open = tasks.filterNot { it.done }.take(3)
    val ready = snap.phase == LinkPhase.Ready
    val locale = Locale("pt", "BR")
    val day = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", locale))
        .replaceFirstChar { it.titlecase(locale) }
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Bom dia"
        in 12..17 -> "Boa tarde"
        else -> "Boa noite"
    }
    val link = when (snap.phase) {
        LinkPhase.Ready -> "Óculos conectados"
        LinkPhase.Linked -> "Conectando o canal"
        LinkPhase.Connecting, LinkPhase.Discovering -> "Conectando"
        LinkPhase.Scanning -> "Procurando"
        LinkPhase.Failed -> "Falha na conexão"
        LinkPhase.Idle -> "Óculos desconectados"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Image(
                    painter = painterResource(R.drawable.aura_logo),
                    contentDescription = "Aura",
                    modifier = Modifier.size(width = 42.dp, height = 46.dp),
                )
                PageHeader(
                    title = greeting,
                    subtitle = day,
                    modifier = Modifier.weight(1f),
                    action = { HeaderIconButton(Icons.Filled.Tv, "Painel", onPanel) },
                )
            }
        }

        // Aura herself: tap to talk.
        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                AuraOrb(aura.phase, onClick = { hub.aura.talk() })
                Text(
                    orbLabel(aura.phase),
                    style = MaterialTheme.typography.titleMedium,
                    color = Paper,
                )
                AnimatedContent(
                    targetState = aura.reply,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "reply",
                ) { reply ->
                    Text(
                        reply,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedField,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Space.lg),
                    )
                }
                Text(
                    if (aura.phase == AuraPhase.Awake) "Toque para encerrar" else "Toque ou diga \"Aura\"",
                    style = MaterialTheme.typography.labelMedium,
                    color = Copper,
                )
            }
        }

        // Glasses at a glance.
        item {
            ActionRow(
                title = snap.name ?: "Nenhum óculos pareado",
                detail = listOfNotNull(
                    link,
                    snap.battery?.let { "bateria $it%" + if (snap.charging) " carregando" else "" },
                ).joinToString(" · "),
                icon = Icons.Filled.Visibility,
                accent = ready,
                trailing = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusDot(if (ready) "Online" else "Offline", ready)
                        StatusDot(if (glassesMicOn && ready) "Mic óculos" else "Mic celular", glassesMicOn && ready)
                    }
                },
                onClick = onGlasses,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    ActionTile("Foto", Icons.Filled.CameraAlt, Modifier.weight(1f)) { hub.aura.runPhrase("Aura tira uma foto") }
                    ActionTile("O que é?", Icons.Filled.Visibility, Modifier.weight(1f)) { hub.aura.runPhrase("Aura o que é isso") }
                    ActionTile("Traduzir", Icons.Filled.Translate, Modifier.weight(1f)) { hub.aura.runPhrase("Aura traduz") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    ActionTile("Mensagens", Icons.AutoMirrored.Filled.Message, Modifier.weight(1f)) { hub.aura.runPhrase("Aura mensagens") }
                    ActionTile("Agenda", Icons.Filled.CalendarMonth, Modifier.weight(1f)) { hub.aura.runPhrase("Aura agenda") }
                    ActionTile("Importar", Icons.Filled.Download, Modifier.weight(1f)) { hub.aura.runPhrase("Aura importar") }
                }
            }
        }

        item {
            SectionCard("Próximas tarefas") {
                if (open.isEmpty()) {
                    Text("Nada pendente. Diga: Aura, me lembre de…", style = mutedStyle())
                } else {
                    open.forEach { task ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                task.title,
                                color = Paper,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(TaskText.formatWhen(task.dueAt), style = mutedStyle())
                        }
                    }
                }
                WideButton("Ver agenda") { onTasks() }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Box(Modifier.weight(1f)) {
                    FeatureCard(title = "Câmera", detail = "Ver e reunião", icon = Icons.Filled.Visibility, onClick = onSee)
                }
                Box(Modifier.weight(1f)) {
                    FeatureCard(title = "Álbum", detail = "Fotos e vídeos", icon = Icons.Filled.PhotoLibrary, onClick = onAlbum)
                }
            }
        }

        item {
            ActionRow(
                title = "O que a Aura sabe fazer",
                detail = "Comandos de voz e exemplos",
                icon = Icons.Filled.AutoAwesome,
                accent = false,
                onClick = onGuide,
            )
        }
    }
}

