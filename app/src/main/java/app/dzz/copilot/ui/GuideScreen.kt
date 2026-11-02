package app.dzz.copilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.dzz.copilot.hub

@Composable
fun GuideScreen(onTasks: () -> Unit, onAlbum: () -> Unit) {
    val hub = LocalContext.current.hub()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        item {
            PageHeader(
                title = "Guia",
                subtitle = "Toque no card ou diga Aura. Depois da resposta, continue sem repetir o nome.",
            )
        }
        item {
            SectionCard("Pelos óculos") {
                FeatureGrid {
                    glasses.forEach { row ->
                        FeatureCard(row.phrase, row.detail) { hub.aura.runPhrase(row.phrase) }
                    }
                }
            }
        }
        item {
            SectionCard("Pelo celular, ouvido nos óculos") {
                FeatureGrid {
                    phone.forEach { row ->
                        FeatureCard(row.phrase, row.detail) {
                            if (row.phrase.contains("…") || row.phrase.contains("frase livre")) return@FeatureCard
                            hub.aura.runPhrase(row.phrase)
                        }
                    }
                }
            }
        }
        item {
            SectionCard("Na tela do celular") {
                FeatureGrid {
                    screen.forEach { row ->
                        FeatureCard(row.phrase, row.detail)
                    }
                }
            }
        }
        item {
            SectionCard("O limite físico") {
                Text(
                    "Estes óculos não têm tela na lente. Foto, vídeo, bateria e a câmera são deles. Agenda, busca, mensagens, tradução, conversa, página e imagem criada usam o celular e voltam como voz. O que é isso e explica olham um quadro sob demanda — não há visão contínua em vídeo (bateria/firmware). WhatsApp entra como notificação ou ao compartilhar do Álbum. OTA, gestos Meta e Opus nativo do mic QC não existem neste app. A Aura ouve pelo celular e fala pelos óculos em A2DP — sem SCO — para o microfone dos óculos não ligar/desligar em loop.",
                    style = mutedStyle(),
                )
            }
        }
        item {
            SectionCard("Estado atual") {
                Text(
                    "Com escuta ao vivo desligada (padrão), depois de Aura você tem cerca de 25 segundos para o próximo pedido sem repetir o nome. Ligue Ao vivo na Home para manter o diálogo ~55s. Foto/bateria e chat usam Groq/Gemini. Volume Alto/Máximo em Ajustes melhora o áudio nos óculos.",
                    style = mutedStyle(),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Agenda", onClick = onTasks)
                QuietButton("Álbum", onClick = onAlbum)
            }
        }
    }
}

private data class Spoken(val phrase: String, val detail: String)

private val glasses = listOf(
    Spoken("Aura, tira uma foto", "Dispara a câmera dos óculos."),
    Spoken("Aura, grava um vídeo", "Começa a gravar. Aura, parar o vídeo encerra."),
    Spoken("Aura, gravar áudio", "Grava o som. Aura, parar o áudio encerra."),
    Spoken("Aura, o que está acontecendo", "Descreve o quadro atual, no próprio celular."),
    Spoken("Aura, o que é isso", "Identifica objeto, placa ou letreiro com a nuvem."),
    Spoken("Aura, lê a placa", "Foca em placa ou letreiro na cena."),
    Spoken("Aura, explica", "Olha de novo e explica a cena, com a nuvem."),
    Spoken("Aura, explica o texto", "Explica o texto que acabou de ser lido na imagem."),
    Spoken("Aura, bateria", "Diz a carga dos óculos."),
    Spoken("Aura, onde estão os óculos", "Faz os óculos se anunciarem."),
    Spoken("Aura, importar", "Puxa as fotos quando o celular está na rede deles."),
)

private val phone = listOf(
    Spoken("Aura", "Abre a conversa. Nas próximas falas você não precisa repetir Aura."),
    Spoken("Aura, traduz", "Entra no modo tradução. Fale a frase; parar tradução sai."),
    Spoken("Aura, traduz good morning", "Traduz uma frase na hora, com os idiomas dos Ajustes."),
    Spoken("Aura, pesquisa clima", "Busca na web e lê a resposta."),
    Spoken("Aura, agenda", "Compromissos das próximas horas. Não vai para a nuvem."),
    Spoken("Aura, mensagens", "SMS e notificações permitidas. O texto não vai para a nuvem."),
    Spoken("Aura, me lembre de beber água em 10 minutos", "Anota uma tarefa e avisa na hora."),
    Spoken("frase livre depois do Aura", "Pergunta natural vira conversa; comandos fortes (foto, bateria) ainda disparam ação."),
)

private val screen = listOf(
    Spoken("Aura, abre clima de Brasília", "Abre a busca no celular. A página fica aqui."),
    Spoken("Aura, cria uma imagem de um farol", "Gera a imagem e guarda no álbum."),
    Spoken("Aba Aura", "A conversa escrita e a falada ficam no mesmo histórico."),
    Spoken("Álbum", "Segure uma foto para compartilhar no WhatsApp ou outro app."),
    Spoken("Ajustes", "Volume nos óculos, voz TTS, idiomas e importação automática."),
)
