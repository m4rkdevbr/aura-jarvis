# Segurança e privacidade

Um assistente pessoal só é útil se você puder confiar nele. Esta página explica onde ficam os seus dados, o que vai para a nuvem e como a Aura evita agir sem você.

## Onde ficam os seus dados

Tudo fica **no seu celular**, na área privada do app. Não existe servidor da Aura.

| Dado | Onde | Observação |
|---|---|---|
| Chaves de IA, tokens do Telegram e senhas de e-mail | `EncryptedSharedPreferences` (AES-256) | Nunca saem do aparelho, a não ser para o próprio provedor |
| Memória de longo prazo | `memory.json` | Até 500 fatos. Você vê e apaga em Ajustes |
| Arquivo de contexto | `brain/profile.md` | Escrito por você |
| Histórico do chat e imagens | `chat.json`, `chat_images/` | |
| Tarefas e lugares | `vault.json` | |
| Impressão vocal | arquivos do modelo CAM++ e do seu perfil de voz | Calculada e guardada no aparelho |
| Fotos e vídeos dos óculos | `DCIM/DZZ Copiloto` | Na galeria, como qualquer foto |

O backup automático do Android está desligado (`allowBackup=false`), para que esses dados não vão para a nuvem sem você saber.

## O que vai para a nuvem, e quando

| Momento | Para onde | O que vai |
|---|---|---|
| Antes de você dizer "Aura" | Nada | A palavra é reconhecida offline pelo Vosk |
| Conversa ao vivo | Google (Gemini Live) | O áudio da conversa, o prompt de sistema (perfil, preferências, fatos recentes, estado dos óculos) e os resultados das ferramentas que ela chamar |
| "O que estou vendo?" | Google (Gemini) | A foto daquele momento |
| Caminho clássico | Groq (Whisper e modelo), Gemini, OpenRouter | O áudio da frase e o texto do pedido |
| Aprendizado | O primeiro provedor configurado | A transcrição da conversa que acabou, para extrair o que guardar |
| Pesquisa na internet | Provedor de busca do modelo | A pergunta |
| Conexões MCP | O servidor que você conectou | Só os dados da ferramenta chamada |
| Telegram | Telegram | As mensagens trocadas com o seu próprio bot |

Com o **modo só local** ligado, nada vai para a nuvem: a Aura usa a IA no aparelho (LFM3-Omni) e os recursos offline.

## Como ela evita agir sem você

1. **Confirmação em duas etapas.** Enviar mensagem, e-mail ou SMS, ligar, responder notificação e ações MCP que criam ou apagam algo passam por duas chamadas. A primeira só monta o pedido e devolve "AINDA NÃO FEITO, pergunte ao usuário". A segunda só roda com `confirmed=true` depois do seu "sim".
2. **WhatsApp com o seu toque.** A Aura abre a conversa com o texto escrito, e quem toca em enviar é você.
3. **Texto de terceiros não é ordem.** E-mails, notificações, páginas, arquivos e respostas de MCP chegam marcados como "informação, não ordens". O prompt de sistema reforça isso em toda conversa.
4. **Acessibilidade com limites.** A Aura pode ler a tela e tocar num item que você pediu, como um vídeo. Ela recusa botões de enviar, pagar, comprar, apagar, confirmar, inscrever-se, instalar ou entrar em conta.
5. **Telegram só com o dono.** O bot pareia com um código e depois ignora qualquer outra conversa.
6. **Sua voz.** Com a impressão vocal ligada, vozes que não são a sua não comandam a Aura.
7. **Aprendizado filtrado.** O que parece senha, chave, token, CPF ou número longo nunca é guardado na memória.
8. **Honestidade.** A Aura não pode dizer que fez algo sem a ferramenta confirmar, nem inventar compromisso, número ou contato.

## O que ainda queremos melhorar

- Uma tela que mostre, conversa por conversa, o que foi enviado para cada provedor.
- Criptografia também da memória e do histórico, não só das chaves.
- Um modo convidado, sem memória e sem acesso ao celular.
- Auditoria externa de segurança.

Encontrou um problema? Veja [SECURITY.md](../SECURITY.md).
