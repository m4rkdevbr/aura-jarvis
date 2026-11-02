# Segurança

A Aura tem acesso a mensagens, e-mails, agenda, contatos, câmera e microfone. Levamos falhas de segurança muito a sério.

## Como relatar uma falha

**Não abra uma issue pública.** Use o relato privado do GitHub:
[Relatar uma vulnerabilidade](https://github.com/m4rkdevbr/aura-jarvis/security/advisories/new).

Conte:
- o que um atacante consegue fazer;
- como reproduzir (passos, versão, aparelho);
- se você já sabe como corrigir.

Respondemos em até 7 dias e combinamos com você quando publicar a correção. Damos o crédito a quem relatou, se você quiser.

## O que nos interessa mais

- **Injeção de instruções:** um e-mail, notificação, página, arquivo ou resposta de MCP que faz a Aura agir sem o usuário pedir.
- Ação que sai em nome do usuário (mensagem, e-mail, SMS, ligação, compra) **sem confirmação**.
- Toque por acessibilidade em botões sensíveis.
- Vazamento de chaves, tokens, memória ou histórico para fora do aparelho.
- Comandos aceitos de quem não é o dono (Telegram, Bluetooth, voz de outra pessoa).
- Falhas no protocolo dos óculos que permitam controle por terceiros por perto.

## Boas práticas para quem usa

- Use chaves de API só suas e troque-as se acha que vazaram.
- Não cole chaves, senhas ou tokens em issues, prints ou registros.
- Ligue a acessibilidade da Aura só se for usar leitura e toque na tela.
