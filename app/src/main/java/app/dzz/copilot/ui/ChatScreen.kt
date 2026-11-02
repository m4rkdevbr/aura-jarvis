package app.dzz.copilot.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import app.dzz.copilot.voice.AuraPhase
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.dzz.copilot.data.ChatLine
import app.dzz.copilot.hub
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val hub = context.hub()
    val lines by hub.chat.lines.collectAsState()
    val aura by hub.aura.state.collectAsState()
    val list = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    val thinking = aura.phase == AuraPhase.Running
    val suggestions = listOf("O que tenho hoje?", "Resuma minhas mensagens", "O que estou vendo?", "Me lembre de beber água em 1 hora")

    LaunchedEffect(lines.size, thinking) {
        val total = lines.size + if (thinking) 1 else 0
        if (total > 0) list.animateScrollToItem(total - 1)
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        draft = ""
        // Same brain as the voice; typed text gets a written (and spoken) answer.
        hub.aura.runPhrase(clean)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            AuraOrb(aura.phase, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text("Aura", style = MaterialTheme.typography.titleMedium, color = Paper)
                Text(orbLabel(aura.phase), style = mutedStyle())
            }
            TextButton(onClick = { hub.chat.clear() }) { Text("Limpar", color = Copper) }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (lines.isEmpty()) {
                item {
                    ElevatedEmptyHint("Escreva aqui ou toque no microfone para falar. O que você disser pelos óculos também aparece nesta conversa.")
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        suggestions.forEach { tip ->
                            TextButton(onClick = { send(tip) }) { Text(tip, color = Copper) }
                        }
                    }
                }
            }
            items(lines) { line -> Bubble(line) }
            if (thinking) {
                item {
                    Text(
                        aura.reply.ifBlank { "Pensando…" },
                        style = mutedStyle(),
                        modifier = Modifier.padding(start = Space.xs),
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.md, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensagem para a Aura") },
                shape = RoundedCornerShape(26.dp),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Copper,
                    unfocusedBorderColor = GlassEdge,
                    focusedContainerColor = GlassHigh,
                    unfocusedContainerColor = GlassHigh,
                    cursorColor = Copper,
                ),
            )
            val canSend = draft.isNotBlank()
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(CopperBrush)
                    .clickable { if (canSend) send(draft) else hub.aura.talk() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (canSend) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                    contentDescription = if (canSend) "Enviar" else "Falar",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ElevatedEmptyHint(text: String) {
    GlassPanel {
        Text(text, style = mutedStyle())
    }
}

@Composable
private fun Bubble(line: ChatLine) {
    val mine = line.role == "voce"
    val shape = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomStart = if (mine) 22.dp else 8.dp,
        bottomEnd = if (mine) 8.dp else 22.dp,
    )
    val bitmap = remember(line.imagePath) {
        line.imagePath
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { BitmapFactory.decodeFile(File(path).absolutePath) }.getOrNull() }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .shadow(8.dp, shape, spotColor = if (mine) Copper.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.35f))
                .clip(shape)
                .border(1.dp, if (mine) Copper.copy(alpha = 0.65f) else GlassEdge, shape)
                .background(if (mine) CopperBrush else GlassBrush)
                .then(if (mine) Modifier else Modifier.background(GlassHigh))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Cena dos óculos",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            if (line.text.isNotBlank()) {
                Text(
                    line.text,
                    color = Paper,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
