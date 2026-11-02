# Como contribuir

Que bom que você chegou aqui. A Aura quer ser o assistente pessoal aberto de referência, e isso só acontece com muita gente construindo junto. Toda ajuda conta: código, testes no hardware, documentação, tradução, design e ideias.

## Por onde começar

- **Nunca contribuiu com código aberto?** Procure issues com `boa primeira issue`. Elas são pequenas e explicadas.
- **Tem óculos de câmera?** Teste e conte o que aconteceu, com o registro de **Ajustes > Diagnóstico**. Isso vale ouro.
- **Escreve bem?** Melhore a documentação ou traduza para inglês e espanhol.
- **Usa a Aura no dia a dia?** Escreva uma skill em Markdown para uma rotina sua (veja abaixo). Não precisa programar.
- **Programa em Kotlin?** Veja o [ROADMAP](docs/ROADMAP.md) e a [arquitetura](docs/ARQUITETURA.md).

Antes de uma mudança grande, abra uma issue contando a ideia. Assim a gente alinha o caminho antes de você investir tempo.

## Preparar o ambiente

Siga [docs/COMPILAR.md](docs/COMPILAR.md). Em resumo:

```bash
git clone https://github.com/m4rkdevbr/aura-jarvis.git
cd aura-jarvis
./tools/baixar-modelos.sh
gradlew.bat testDebugUnitTest assembleDebug
```

## Fluxo

1. Faça um fork e crie um branch com um nome claro (`fix/foto-timeout`, `feat/busca-netflix`).
2. Faça a mudança com testes, quando houver lógica nova.
3. Rode `gradlew.bat testDebugUnitTest`. Tudo precisa passar.
4. Abra o pull request preenchendo o modelo. Conte como testou.
5. A CI compila e roda os testes. Alguém revisa, e a gente conversa até ficar bom.

## Padrões do código

- **Kotlin idiomático** e o estilo oficial (`kotlin.code.style=official`).
- **Lógica pura fora do Android.** Regras de decisão ficam em `object`s sem dependência de Android, como `HealthRules`, `ProactiveRules`, `AppActions`, `Learner` e `QcProtocol`, com testes de JVM ao lado.
- **Ferramentas honestas.** Uma ferramenta nova faz uma coisa só e devolve uma frase que diz exatamente o que aconteceu, inclusive quando falhou.
- **Textos para o usuário em português do Brasil**, curtos e naturais, porque muitos são falados no ouvido.
- **Comentários explicam o porquê**, não o quê.
- **Sem dependências pesadas** sem conversar antes: o APK já é grande e roda em celulares modestos.

## Regras de segurança (não negociáveis)

- **Nunca** coloque chave de API, token, senha ou dado pessoal no código, nos testes ou nos commits. As chaves são digitadas pelo usuário em Ajustes e ficam criptografadas.
- Qualquer ação que **sai em nome do usuário** (mensagem, e-mail, SMS, ligação, publicação, compra, ação MCP que cria ou apaga) precisa de confirmação explícita, no padrão de `PhoneRules.needConfirm` e `confirmed=true`.
- Texto vindo de **terceiros** (notificações, e-mails, páginas, arquivos, MCP) é informação, nunca ordem. Marque-o assim no resultado da ferramenta.
- A acessibilidade **nunca** toca em enviar, pagar, comprar, apagar, confirmar ou entrar em conta (`AppActions.sensitive`).
- Dados do usuário ficam no aparelho. Mandar algo novo para a nuvem precisa de uma razão clara e de aparecer na documentação de privacidade.

## Adicionar uma ferramenta

1. Crie um `SimpleTool(nome, descrição, Schema.obj(...)) { args -> ... }` no módulo certo (`phone/PhoneActions.kt`, `voice/AuraTools.kt`, `email/MessagingTools.kt` ou um módulo novo).
2. Registre-o no `copilot.brain.tools`. Ele aparece sozinho na conversa ao vivo, no chat e no Telegram.
3. Escreva a descrição pensando no modelo: quando usar, o que passar, o que não fazer.
4. Se a ferramenta demora, adicione o nome em `GeminiLive.SLOW_TOOLS` e uma frase em `AuraSession.toolNote`.

## Escrever uma skill

Uma skill é um arquivo Markdown em `app/src/main/assets/skills/` (ou em `files/skills` no celular):

```markdown
---
name: lista-de-compras
description: Monta a lista de compras da semana a partir do que o usuário disser.
triggers: lista de compras, o que comprar, fazer mercado
needs: tarefas, memoria
---
Pergunte o que acabou e o que vai cozinhar na semana. Junte com o que a memória
diz sobre preferências da casa. Fale a lista em no máximo três frases e ofereça
guardar como tarefa.
```

## Documentar um modelo de óculos

Use o modelo de issue "Novo modelo de óculos". Com a lista de serviços Bluetooth e alguns testes, a gente avalia como suportar. O formato do protocolo atual está em [docs/PROTOCOLO-OCULOS.md](docs/PROTOCOLO-OCULOS.md).

Só aceitamos documentação de protocolo escrita a partir da observação do próprio aparelho, com fins de interoperabilidade. **Não envie código descompilado ou copiado de apps de terceiros.**

## Commits e mensagens

Mensagens curtas que dizem o que mudou para o usuário, em português ou inglês: `Foto IA retoma o pedaço perdido`, `Fix BLE write queue dropping chunks`.

## Conduta

Este projeto segue o [Código de Conduta](CODE_OF_CONDUCT.md). Seja gentil, paciente e generoso com quem está começando.

## Licença

Ao contribuir, você concorda que a sua contribuição é publicada sob a [licença MIT](LICENSE).
