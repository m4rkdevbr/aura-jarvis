package app.dzz.copilot.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dzz.copilot.brain.Analysis
import app.dzz.copilot.brain.Reasoning
import app.dzz.copilot.glasses.LinkPhase
import app.dzz.copilot.hub
import app.dzz.copilot.voice.AuraPhase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone as the glasses' monitor: screen kept on (even over the lock screen), big text of
 * what was heard and answered, the last photo from the glasses and the full framework analyses
 * that the voice only summarizes.
 */
@Composable
fun PanelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hub = context.hub()
    val view = LocalView.current
    val aura by hub.aura.state.collectAsState()
    val snap by hub.glasses.snapshot.collectAsState()
    val lines by hub.chat.lines.collectAsState()
    val scope = rememberCoroutineScope()
    var analyses by remember { mutableStateOf<List<Analysis>>(emptyList()) }
    var chaining by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        view.keepScreenOn = true
        if (Build.VERSION.SDK_INT >= 27) activity?.setShowWhenLocked(true)
        onDispose {
            view.keepScreenOn = false
            if (Build.VERSION.SDK_INT >= 27) activity?.setShowWhenLocked(false)
        }
    }
    LaunchedEffect(lines.size) {
        analyses = withContext(Dispatchers.IO) { hub.copilot.analyses.recent(5) }
    }
    val lastImage = lines.lastOrNull { !it.imagePath.isNullOrBlank() }?.imagePath
    val bitmap = remember(lastImage) {
        lastImage?.let { path -> runCatching { BitmapFactory.decodeFile(File(path).absolutePath) }.getOrNull() }
    }
    val phase = when (aura.phase) {
        AuraPhase.Listening -> "Ouvindo"
        AuraPhase.Awake -> "Pode falar"
        AuraPhase.Running -> "Pensando"
        AuraPhase.Off -> "Parada"
    }
    val glassesLine = if (snap.phase == LinkPhase.Ready) {
        listOfNotNull(snap.name ?: "Óculos", snap.battery?.let { "$it%" }).joinToString(" · ")
    } else {
        "Óculos: ${snap.status}"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        item {
            PageHeader(
                title = "Painel",
                subtitle = glassesLine,
                action = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        StatusPill(phase, accent = aura.phase != AuraPhase.Off)
                        HeaderIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onBack)
                    }
                },
            )
        }
        if (aura.heard.isNotBlank()) {
            item {
                GlassPanel {
                    Text("VOCÊ", style = MaterialTheme.typography.labelMedium, color = MutedField)
                    Text(aura.heard, color = Paper, fontSize = 22.sp, lineHeight = 28.sp)
                }
            }
        }
        item {
            HeroCard {
                Text(
                    "AURA",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    aura.reply.ifBlank { "Diga Aura e o pedido." },
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 26.sp,
                    lineHeight = 33.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (bitmap != null) {
            item {
                GlassPanel {
                    Text("ÚLTIMA CENA DOS ÓCULOS", style = MaterialTheme.typography.labelMedium, color = MutedField)
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Última cena dos óculos",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        if (analyses.isNotEmpty()) {
            item { Text("Análises", style = MaterialTheme.typography.titleMedium, color = Paper) }
            items(analyses, key = { it.id }) { analysis ->
                AnalysisCard(
                    analysis = analysis,
                    busy = chaining,
                    onNext = {
                        if (chaining) return@AnalysisCard
                        chaining = true
                        scope.launch {
                            val outcome = runCatching { hub.copilot.reasoning.chain(analysis.id) }.getOrNull()
                            if (outcome is Reasoning.Done) {
                                hub.chat.add("aura", outcome.analysis.spoken)
                                analyses = withContext(Dispatchers.IO) { hub.copilot.analyses.recent(5) }
                            }
                            chaining = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AnalysisCard(analysis: Analysis, busy: Boolean, onNext: () -> Unit) {
    var open by remember(analysis.id) { mutableStateOf(false) }
    val clock = remember { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")) }
    SectionCard(title = analysis.title) {
        Text(clock.format(Date(analysis.createdAt)) + " · " + analysis.question.take(120), style = mutedStyle())
        Text(analysis.summary, color = Paper, style = MaterialTheme.typography.bodyLarge)
        if (open) {
            renderResult(analysis.result).forEach { line ->
                Text(line, color = Paper, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            TextButton(onClick = { open = !open }) { Text(if (open) "Fechar" else "Ver tudo", color = Copper) }
            if (analysis.next.isNotEmpty()) {
                TextButton(onClick = onNext, enabled = !busy) {
                    Text(if (busy) "Analisando…" else "Próximo passo", color = Copper)
                }
            }
        }
    }
}

/** Flattens the framework JSON into readable lines: "Causa raiz: …", "• item". */
private fun renderResult(result: JSONObject, depth: Int = 0): List<String> {
    val out = ArrayList<String>()
    val pad = "  ".repeat(depth)
    result.keys().forEach { key ->
        val label = key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
        when (val value = result.opt(key)) {
            is JSONObject -> {
                out += "$pad$label:"
                out += renderResult(value, depth + 1)
            }
            is JSONArray -> {
                out += "$pad$label:"
                for (i in 0 until value.length()) {
                    when (val item = value.opt(i)) {
                        is JSONObject -> out += renderResult(item, depth + 1).mapIndexed { index, line ->
                            if (index == 0) "$pad  • ${line.trimStart()}" else line
                        }
                        else -> out += "$pad  • $item"
                    }
                }
            }
            else -> out += "$pad$label: $value"
        }
    }
    return out
}
