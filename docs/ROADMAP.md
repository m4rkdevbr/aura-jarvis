# Roadmap

O que já existe, o que está em teste e o que vem. As issues no GitHub seguem estes marcos. Quer pegar um item? Comente na issue ou abra uma nova.

Legenda: ✅ pronto · 🧪 pronto, em teste no hardware · 🔜 próximo · 💡 ideia aberta

## Fundação (versão 1.x)

| | Item |
|---|---|
| ✅ | Conexão BLE com os óculos QC/oudmon: bateria, hora, foto, vídeo, áudio, encontrar, Wi-Fi e importação de mídia |
| ✅ | Protocolo documentado do zero, com testes |
| ✅ | Palavra "Aura" offline (Vosk) |
| ✅ | Conversa ao vivo com interrupção (Gemini Live) |
| ✅ | Óculos como fone Bluetooth na conversa, sem bipes |
| 🧪 | Foto IA com retomada de pedaços e captura compartilhada |
| ✅ | Foco na voz: redutor de ruído e impressão vocal no aparelho |
| ✅ | Cérebro único com cerca de 50 ferramentas |
| ✅ | 20 frameworks de raciocínio e 11 skills |
| ✅ | Memória de longo prazo e arquivo de contexto |
| 🧪 | Aprendizado automático depois de cada conversa |
| ✅ | IA local (LFM3-Omni com llama.cpp) |
| ✅ | Celular: agenda, SMS, ligação, notificações, arquivos, tela, apps |
| 🧪 | Busca dentro de apps (YouTube, Spotify, Maps, Play Store) e toque na tela |
| ✅ | E-mail, WhatsApp e Telegram |
| ✅ | Conexões MCP com OAuth e plugins |
| 🧪 | Proatividade: manhã, compromissos com rota, noite, mensagens, bateria |
| 🧪 | Autodiagnóstico e autocorreção |

## Próximos passos (versão 2.0)

### Qualidade e base
- 🔜 Dividir `AuraSession` e `GlassesClient` em peças menores
- 🔜 Simulador dos óculos (BLE falso) para testar sem hardware
- 🔜 Testes instrumentados e CI com emulador
- 🔜 Build reproduzível para Linux e macOS (script `gradlew` e modelos baixados automaticamente)
- 🔜 Lançamentos assinados no GitHub Releases e no F-Droid
- 🔜 Telemetria local de latência por etapa (ouvir, pensar, ferramenta, falar), visível no Diagnóstico

### Inteligência
- 🔜 Memória semântica com embeddings no aparelho e busca por significado
- 🔜 Linha do tempo pessoal: "o que eu fiz ontem à tarde?", "onde eu estava quando falei com o João?"
- 🔜 Reflexão diária: a Aura revisa o dia, consolida a memória e propõe melhorias no próprio comportamento
- 🔜 Tarefas longas: acompanhar algo por dias (um pedido, uma resposta, um prazo) e voltar a você
- 🔜 Roteador de modelos: escolher o modelo pelo tipo de pedido, custo e latência
- 💡 Planejamento com várias etapas e checkpoints confirmados por você

### Visão
- 🔜 "Onde deixei minhas chaves?": memória visual das últimas horas, opcional e só no aparelho
- 🔜 Modo observador opcional: dicas proativas pela câmera, com controle de bateria
- 💡 Reconhecer pessoas que você cadastrou, com consentimento explícito
- 💡 Ler e preencher formulários do mundo real (contas, boletos) com confirmação

### Celular e apps
- 🔜 Mais buscas diretas: Netflix, iFood, Uber, Waze, Mercado Livre
- 🔜 Automação por acessibilidade com roteiros seguros e revisáveis (nunca pagar ou enviar sem você)
- 🔜 Integração com Google Agenda, Gmail e Drive sem passar pelo Zapier
- 💡 Casa inteligente: Home Assistant e Google Home por MCP

### Óculos
- 🔜 Camada de adaptadores, para cada modelo de óculos ser um plugin
- 💡 Adaptadores para Ray-Ban Meta, Brilliant Frame, Even Realities G1, Mentra e outros
- 💡 Mostrar texto em óculos com tela (HUD)

### Pessoas
- 🔜 Documentação e interface em inglês e espanhol
- 🔜 Voz em vários idiomas
- 🔜 Catálogo comunitário de skills e plugins
- 💡 Modo família: várias pessoas, cada uma com a própria memória

## Como ajudar agora

As formas mais valiosas de contribuir hoje:

1. **Testar com os seus óculos** e relatar o que aconteceu, com o registro de Diagnóstico.
2. **Documentar um modelo de óculos** que ainda não está na lista.
3. **Escrever uma skill** em Markdown para uma rotina do seu dia a dia.
4. **Traduzir** a documentação e a interface.
5. **Pegar uma issue** marcada com `boa primeira issue`.

Veja [CONTRIBUTING.md](../CONTRIBUTING.md).
