package app.dzz.copilot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.dzz.copilot.agenda.TaskText
import app.dzz.copilot.hub
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Composable
fun TasksScreen() {
    val context = LocalContext.current
    val hub = context.hub()
    val scope = rememberCoroutineScope()
    val tasks by hub.vault.tasks.collectAsState()
    val places by hub.vault.places.collectAsState()
    val meetings by hub.vault.meetings.collectAsState()
    var draft by remember { mutableStateOf("") }
    var placeName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message = "Sem a localização, o lembrete de lugar não funciona."
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Agenda", style = MaterialTheme.typography.titleLarge)
            Text("Compromissos ficam no celular. O horário dispara um aviso mesmo com o app fechado.", style = mutedStyle())
        }
        item {
            SectionCard("Nova tarefa") {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("O que precisa ser feito") },
                    placeholder = { Text("entregar o relatório amanhã às 14") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Hoje 18h") { draft = "$draft hoje às 18".trim() }
                    QuietButton("Amanhã 9h") { draft = "$draft amanhã às 9".trim() }
                }
                WideButton("Adicionar") {
                    val parsed = TaskText.parse(draft) ?: return@WideButton
                    hub.vault.addTask(parsed.title, parsed.dueAt)
                    draft = ""
                    message = "Tarefa salva."
                }
                TextButton(onClick = { message = TaskText.briefing(tasks.filter { !it.done }); hub.copilot.speak(message) }) {
                    Text("Ouvir o resumo do dia")
                }
                if (message.isNotBlank()) Text(message)
            }
        }
        item {
            SectionCard("Lugares") {
                Text("Salve um lugar e ligue o lembrete em Ajustes. O aviso sai quando você passar perto, com o serviço dos óculos ativo.", style = mutedStyle())
                OutlinedTextField(
                    value = placeName,
                    onValueChange = { placeName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nome do lugar") },
                    placeholder = { Text("Farmácia") },
                )
                WideButton("Salvar onde estou") {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        return@WideButton
                    }
                    if (placeName.isBlank()) {
                        message = "Dê um nome ao lugar."
                        return@WideButton
                    }
                    scope.launch {
                        val here = currentLocation(context)
                        if (here == null) {
                            message = "Não consegui a localização agora."
                        } else {
                            hub.vault.addPlace(placeName, here.latitude, here.longitude)
                            placeName = ""
                            message = "Lugar salvo."
                        }
                    }
                }
                places.forEach { place ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(place.name, modifier = Modifier.weight(1f))
                        TextButton(onClick = { hub.vault.deletePlace(place.id) }) { Text("Apagar") }
                    }
                }
            }
        }
        item { Text("Abertas", style = MaterialTheme.typography.titleMedium) }
        val open = tasks.filter { !it.done }
        if (open.isEmpty()) {
            item { Text("Nada pendente.", style = mutedStyle()) }
        }
        items(open.size) { index ->
            val task = open[index]
            TaskRow(task.title, TaskText.formatWhen(task.dueAt), task.done, places.map { it.name to it.id }, task.placeId, {
                hub.vault.toggle(task.id)
            }, {
                hub.vault.deleteTask(task.id)
            }, { placeId ->
                hub.vault.setPlace(task.id, placeId)
            })
        }
        val done = tasks.filter { it.done }
        if (done.isNotEmpty()) {
            item { Text("Concluídas", style = MaterialTheme.typography.titleMedium) }
            items(done.size) { index ->
                val task = done[index]
                TaskRow(task.title, TaskText.formatWhen(task.dueAt), true, emptyList(), null, {
                    hub.vault.toggle(task.id)
                }, { hub.vault.deleteTask(task.id) }, {})
            }
        }
        if (meetings.isNotEmpty()) {
            item { Text("Reuniões", style = MaterialTheme.typography.titleMedium) }
            items(meetings.size) { index ->
                val note = meetings[index]
                SectionCard(note.title) {
                    Text(note.body, style = mutedStyle())
                    TextButton(onClick = { hub.vault.deleteMeeting(note.id) }) { Text("Apagar") }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    title: String,
    whenText: String,
    done: Boolean,
    places: List<Pair<String, String>>,
    placeId: String?,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onPlace: (String?) -> Unit,
) {
    SectionCard(if (done) title else title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = done, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(title)
                Text(whenText, style = mutedStyle())
            }
            TextButton(onClick = onDelete) { Text("Apagar") }
        }
        if (places.isNotEmpty() && !done) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                places.take(3).forEach { (name, id) ->
                    TextButton(onClick = { onPlace(if (placeId == id) null else id) }) {
                        Text(if (placeId == id) "$name · ligado" else name)
                    }
                }
            }
        }
    }
}

private suspend fun currentLocation(context: android.content.Context): Location? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val provider = when {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> return null
    }
    val cached = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    if (cached != null && System.currentTimeMillis() - cached.time < 120_000) return cached
    return suspendCancellableCoroutine { continuation ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                if (continuation.isActive) continuation.resume(location)
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        runCatching {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
        }.onFailure {
            if (continuation.isActive) continuation.resume(cached)
        }
    }
}
