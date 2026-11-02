# Funcionalidades

Tudo o que a Aura faz hoje, módulo por módulo. Cada item aponta para o código, para você conferir e melhorar.

## 1. Voz e conversa

### Palavra de ativação "Aura"
- Reconhecimento **offline** com Vosk (modelo `vosk-model-small-pt-0.3`). Nada é enviado para a nuvem antes de você dizer "Aura". Código: [`voice/LocalAsr.kt`](../app/src/main/java/app/dzz/copilot/voice/LocalAsr.kt).
- Detecção de fala por energia e segmentação em frases ([`voice/SpeechSegmenter.kt`](../app/src/main/java/app/dzz/copilot/voice/SpeechSegmenter.kt)).
- Comandos curtos ("Aura, foto", "Aura, que horas são") resolvidos na hora por regras, sem IA ([`voice/AuraCommands.kt`](../app/src/main/java/app/dzz/copilot/voice/AuraCommands.kt), [`voice/CommandBrain.kt`](../app/src/main/java/app/dzz/copilot/voice/CommandBrain.kt)).

### Conversa ao vivo (Gemini Live)
- Conversa em tempo real por WebSocket, com áudio de 16 kHz indo e 24 kHz voltando ([`voice/GeminiLive.kt`](../app/src/main/java/app/dzz/copilot/voice/GeminiLive.kt), [`voice/LiveProtocol.kt`](../app/src/main/java/app/dzz/copilot/voice/LiveProtocol.kt)).
- **Interrupção natural**: você fala por cima e ela para. O cancelamento de eco adaptativo evita que ela interrompa a si mesma.
- Todas as ferramentas do cérebro viram chamadas de função dentro da conversa.
- Ferramentas exclusivas da conversa: `end_conversation` (encerra quando você se despede) e `watch_mode` (manda imagens da câmera a cada poucos segundos por até 2 minutos).
- Encerra sozinha depois de 30 segundos de silêncio. Se a conversa ao vivo não abrir (sem chave, sem rede), cai para o caminho clássico por alguns minutos.
- Modelos configuráveis em Ajustes, com fallback automático entre eles.

### Os óculos como fone Bluetooth
- Modo padrão "Chamada": a Aura usa o perfil de chamada (HFP) dos óculos, como um fone. Microfone com cancelamento de eco do Android, som de voz pelos alto-falantes dos óculos e **nenhum bipe** de abrir e fechar microfone ([`voice/PhoneMicEngine.kt`](../app/src/main/java/app/dzz/copilot/voice/PhoneMicEngine.kt)).
- Alternativas: microfone dos óculos por BLE (Opus 16 kHz, [`voice/GlassesMic.kt`](../app/src/main/java/app/dzz/copilot/voice/GlassesMic.kt), [`voice/GlassesAudio.kt`](../app/src/main/java/app/dzz/copilot/voice/GlassesAudio.kt)) ou microfone do celular.
- Avisa os óculos quando a IA está falando (comando 0x48), como o app original faz.

### Foco na sua voz
- Redutor de ruído Wiener em Kotlin puro em cada quadro de áudio ([`voice/NoiseReducer.kt`](../app/src/main/java/app/dzz/copilot/voice/NoiseReducer.kt)).
- **Impressão vocal**: você grava 3 frases de 6 segundos e um modelo CAM++ (ONNX, roda no celular) aprende a sua voz. Em ambientes com outras pessoas, a Aura ignora vozes que não são a sua ([`voice/SpeakerId.kt`](../app/src/main/java/app/dzz/copilot/voice/SpeakerId.kt), [`voice/VoiceFocus.kt`](../app/src/main/java/app/dzz/copilot/voice/VoiceFocus.kt)).
- Rigor ajustável: leve, normal ou forte.

### Fala da Aura
- Voz do Gemini Live na conversa; Gemini TTS ou o TTS do Android no caminho clássico, sempre com voz feminina ([`copilot/AuraVoice.kt`](../app/src/main/java/app/dzz/copilot/copilot/AuraVoice.kt), [`copilot/LivePlayer.kt`](../app/src/main/java/app/dzz/copilot/copilot/LivePlayer.kt)).
- Transcrição por Whisper (Groq) quando a conversa ao vivo não está ativa.

## 2. Visão

- **"O que estou vendo?"**: foto pela câmera dos óculos, transferida em pedaços por BLE, com retomada automática do pedaço que se perdeu ([`glasses/GlassesClient.kt`](../app/src/main/java/app/dzz/copilot/glasses/GlassesClient.kt), [`protocol/QcProtocol.kt`](../app/src/main/java/app/dzz/copilot/protocol/QcProtocol.kt)).
- Na conversa ao vivo, a foto vai direto para o Gemini, que descreve na hora. Fora dela, ML Kit lê textos e rótulos no aparelho e a nuvem descreve a cena.
- Leitura de texto (OCR) em latim e chinês, rótulos de objetos e tradução, tudo no aparelho com ML Kit.
- Câmera do celular com acompanhamento que só fala quando a cena muda ([`ui/CopilotScreen.kt`](../app/src/main/java/app/dzz/copilot/ui/CopilotScreen.kt)).
- Importação das fotos e vídeos guardados nos óculos pelo Wi-Fi deles, para `DCIM/DZZ Copiloto` ([`glasses/MediaSync.kt`](../app/src/main/java/app/dzz/copilot/glasses/MediaSync.kt)).
- Geração de imagens a partir de uma descrição ([`copilot/ImageStudio.kt`](../app/src/main/java/app/dzz/copilot/copilot/ImageStudio.kt)).

## 3. Cérebro

### Um agente com ferramentas
- Um só cérebro para voz, chat digitado e Telegram: um agente que chama ferramentas em até 6 rodadas por pedido ([`brain/Agent.kt`](../app/src/main/java/app/dzz/copilot/brain/Agent.kt), [`brain/Brain.kt`](../app/src/main/java/app/dzz/copilot/brain/Brain.kt)).
- Fala para o ouvido: no máximo 3 frases curtas, sem listas nem markdown, a não ser que você peça detalhes.
- Provedores em ordem, com fallback automático: Gemini, Groq, OpenRouter e IA local.

### Raciocínio com método
- 20 frameworks em [`assets/frameworks.json`](../app/src/main/assets/frameworks.json): 5 porquês, Ishikawa, SWOT, MECE, primeiros princípios, Eisenhower, campo de forças, matriz de decisão, custo-benefício, OODA, caminho crítico, cenários, Ansoff, BCG, cinco forças de Porter, oceano azul, pre-mortem, teste de hipóteses, seis chapéus e pensamento lateral.
- O motor escolhe o framework certo, pergunta o que falta de contexto e devolve um veredito falado, sem citar o nome do método ([`brain/ReasoningEngine.kt`](../app/src/main/java/app/dzz/copilot/brain/ReasoningEngine.kt), [`brain/Frameworks.kt`](../app/src/main/java/app/dzz/copilot/brain/Frameworks.kt)).
- As análises ficam guardadas e aparecem no Painel.

### Skills
Rotinas em Markdown que ensinam a Aura sem programar. Cada uma declara frases de ativação e os dados de que precisa ([`brain/Skills.kt`](../app/src/main/java/app/dzz/copilot/brain/Skills.kt)). Prontas: checagem cética, entrevista, estudar, pesquisa, preparar reunião, responder mensagem, resumo do dia, resumo para a memória, status, triagem de mensagens e viagem. Você pode criar as suas em `files/skills`.

### IA local
- LFM3-Omni 2B (GGUF) rodando no celular com `llama-server` do llama.cpp, baixados pelo próprio app ([`copilot/OnDeviceLlmRuntime.kt`](../app/src/main/java/app/dzz/copilot/copilot/OnDeviceLlmRuntime.kt), [`copilot/LocalLlmService.kt`](../app/src/main/java/app/dzz/copilot/copilot/LocalLlmService.kt), [`copilot/LocalLfmBrain.kt`](../app/src/main/java/app/dzz/copilot/copilot/LocalLfmBrain.kt)).
- Usada sem internet ou no modo "só local". Num Note 10+ gera de 4 a 11 tokens por segundo.

## 4. Memória e aprendizado

- **Memória de longo prazo só no celular** ([`brain/Memory.kt`](../app/src/main/java/app/dzz/copilot/brain/Memory.kt)): até 500 fatos, busca por palavras, sem modelo extra nem RAM extra.
- **Arquivo de contexto** (`profile.md`): quem você é, projetos, contatos prioritários, o que é urgente. Lido em toda conversa.
- **Aprendizado automático** ([`brain/Learner.kt`](../app/src/main/java/app/dzz/copilot/brain/Learner.kt)): depois de cada conversa, a Aura relê o que foi dito e guarda até 5 itens de três tipos.
  - Fatos sobre você.
  - Preferências de como ela deve agir. Entram em todas as conversas seguintes.
  - Lições do que deu errado.
- Nunca guarda senhas, chaves, códigos ou números de documento. Você vê e apaga tudo em Ajustes.
- Ferramentas: `remember`, `recall`, `forget`.

## 5. Ferramentas

A IA escolhe sozinha qual chamar. As que agem em nome de você para fora (mensagem, e-mail, SMS, ligação, ações MCP que criam ou apagam) **só executam depois do seu "sim"**.

| Área | Ferramenta | O que faz |
|---|---|---|
| Óculos | `look` | Olha pela câmera e descreve a cena, com foco na sua pergunta |
| | `glasses_photo`, `glasses_video_start`, `glasses_video_stop`, `glasses_audio_start` | Foto, vídeo e áudio guardados nos óculos |
| | `glasses_find` | Faz os óculos tocarem para você achá-los |
| | `glasses_import_media` | Traz as fotos e vídeos dos óculos para o celular |
| | `glasses_status` | Conexão, bateria e modo |
| Agenda | `agenda`, `calendar_add`, `add_task` | Lê a agenda, cria compromissos e lembretes em linguagem natural ("beber água em dez minutos") |
| | `set_alarm`, `set_timer` | Alarmes e timers do relógio |
| Comunicação | `contacts_find`, `phone_call`, `send_sms` | Contatos, ligação e SMS, com confirmação |
| | `notifications`, `reply_message` | Lê notificações e responde pelo botão de resposta delas |
| | `whatsapp_message`, `media_send` | Abre o WhatsApp com o texto pronto; manda a última foto dos óculos |
| | `email_inbox`, `email_read`, `email_send` | E-mail por IMAP/SMTP com senha de app |
| | `telegram_send`, `telegram_send_photo`, `telegram_send_file` | Manda para o seu próprio Telegram |
| Celular | `open_app`, `open_on_phone` | Abre apps, sites e buscas |
| | `app_search` | Abre o app já com a busca feita: YouTube (e toca o primeiro vídeo), YouTube Music, Spotify (toca a música), Maps, rota, Play Store, Google |
| | `screen_read`, `screen_tap`, `phone_navigate` | Lê a tela, toca no item pedido, volta, início, recentes. Nunca toca em enviar, pagar, comprar, apagar ou confirmar |
| | `phone_status`, `files_find`, `file_read`, `file_send_telegram` | Bateria, rede, localização e arquivos |
| Conhecimento | `web_search` | Pesquisa atual na internet |
| | `translate` | Tradução entre idiomas |
| | `create_image` | Cria uma imagem |
| Mente | `analyze_problem` | Aplica o framework de raciocínio certo |
| | `remember`, `recall`, `forget`, `load_skill` | Memória e skills |
| Sistema | `self_check` | Autodiagnóstico com correção automática |
| MCP | `mcp_ferramentas`, `mcp_usar`, `mcp_<servidor>_<ferramenta>` | Usa qualquer servidor MCP conectado |

## 6. Proatividade

[`proactive/Proactive.kt`](../app/src/main/java/app/dzz/copilot/proactive/Proactive.kt). Só quando você liga "Falar por iniciativa própria", e nunca entre 22h e 7h.

- Resumo da manhã com agenda e mensagens desde a noite anterior.
- Lembrete alguns minutos antes de cada compromisso, com o local e a oferta de rota.
- Às 20h, o primeiro compromisso de amanhã.
- Mensagens de apps de conversa lidas em voz alta, no máximo uma a cada 20 segundos, com a oferta de responder.
- Bateria baixa dos óculos e do celular (15% e 5%).
- Lembretes que tocam mesmo com o app fechado ([`reminder/`](../app/src/main/java/app/dzz/copilot/reminder/)).

## 7. Autodiagnóstico e autocorreção

[`health/HealthMonitor.kt`](../app/src/main/java/app/dzz/copilot/health/HealthMonitor.kt), regras puras e testadas em [`health/HealthRules.kt`](../app/src/main/java/app/dzz/copilot/health/HealthRules.kt).

- A cada 30 segundos confere: conexão dos óculos, canal de comando, comandos perdidos no Bluetooth, fotos que falharam, internet, chaves configuradas, ferramentas lentas ou falhando, quedas da conversa ao vivo.
- Corrige sozinha o que é seguro.
  - Óculos desconectados por mais de 90 segundos: ela reconecta.
  - Bluetooth ligado sem canal de comando por 60 segundos, 5 ou mais comandos perdidos, ou 2 fotos seguidas falhando: ela reabre o canal.
  - Ela espera 3 minutos entre uma correção e outra.
- Nunca reconecta se você desconectou de propósito.
- Mostra tudo em **Ajustes > Diagnóstico**. Você também pode dizer "Aura, faz um autodiagnóstico".

## 8. Conexões (MCP) e plugins

[`mcp/`](../app/src/main/java/app/dzz/copilot/mcp/)

- Cliente MCP por Streamable HTTP, com OAuth (PKCE e registro dinâmico de cliente) ou chave manual.
- Catálogo pronto: GitHub, Notion, Linear, Zapier (a ponte para Gmail, Google Agenda e Drive), Hugging Face, DeepWiki e Context7.
- Servidores com até 12 ferramentas viram funções diretas para a IA. Os maiores são acessados por `mcp_ferramentas` e `mcp_usar`.
- **Plugins** = conexões + uma skill que ensina a usá-las. Prontos: Desenvolvedor, Produtividade, Google pelo Zapier. Dá para importar plugins em JSON.

## 9. Telegram

[`telegram/`](../app/src/main/java/app/dzz/copilot/telegram/)

Converse com a Aura pelo Telegram de qualquer lugar. O bot só responde ao dono, aceita texto, foto e arquivos e usa o mesmo cérebro e a mesma memória. O token fica só em Ajustes, criptografado.

## 10. Interface

Jetpack Compose com Material 3 e o tema "Aurora" ([`ui/`](../app/src/main/java/app/dzz/copilot/ui/)).

- Abas: Início, Conversa, Óculos, Agenda, Ajustes.
- Início com o orbe da Aura, estado da conversa e atalhos.
- Conversa com histórico, imagens e chat digitado.
- Painel com as análises de raciocínio.
- Álbum das fotos dos óculos.
- Ajustes em 8 categorias: Óculos, Voz e conversa, Conexões e plugins, Celular e iniciativa, E-mail e Telegram, Você e memória, Cérebro e nuvem, Diagnóstico.
- Widget de status na tela inicial ([`widget/StatusWidget.kt`](../app/src/main/java/app/dzz/copilot/widget/StatusWidget.kt)).
- Serviço em primeiro plano que mantém os óculos conectados ([`glasses/GlassesService.kt`](../app/src/main/java/app/dzz/copilot/glasses/GlassesService.kt)).
