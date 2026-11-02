# Compilar, instalar e configurar

## Requisitos

- JDK 17. O Android Studio já traz um.
- Android SDK 35, pelo Android Studio ou pelas command-line tools.
- Um celular Android 8.0 (API 26) ou mais novo, com a depuração USB ligada. O APK é gerado para `arm64-v8a`.
- Uns 2 GB livres para o build. O APK fica com ~130 MB por causa dos modelos de voz e visão.

## 1. Baixar o código e os modelos

```bash
git clone https://github.com/m4rkdevbr/aura-jarvis.git
cd aura-jarvis
```

O modelo de voz offline (Vosk, ~31 MB) não fica no Git. Baixe com:

```bash
./tools/baixar-modelos.sh           # Linux / macOS
.\tools\baixar-modelos.ps1          # Windows (PowerShell)
```

Ele coloca o modelo `vosk-model-small-pt-0.3` em `app/src/main/assets/vosk-pt/`.

Os outros modelos são baixados pelo próprio celular quando você ativa cada recurso: impressão vocal (CAM++, ~30 MB), IA local (LFM3-Omni GGUF e `llama-server`) e idiomas de tradução do ML Kit.

## 2. Compilar

No Android Studio, abra a pasta e rode no celular.

Pela linha de comando:

```bash
gradlew.bat assembleDebug testDebugUnitTest      # Windows
./gradlew assembleDebug testDebugUnitTest        # Linux / macOS (veja a nota abaixo)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Ainda não há o script `gradlew` para Linux e macOS no repositório. Gere uma vez com `gradle wrapper --gradle-version 8.10.2`, se tiver o Gradle instalado. Uma contribuição que o adicione é muito bem-vinda.

Se o caminho da pasta tiver acento, tudo bem: o projeto já define `android.overridePathCheck=true`.

## 3. Primeiro uso

1. **Chaves de IA:** em **Ajustes > Cérebro e nuvem**.

   | Chave | Para quê | Obrigatória? |
   |---|---|---|
   | Gemini ([aistudio.google.com](https://aistudio.google.com/apikey)) | Conversa ao vivo, cérebro, visão, voz | Recomendada |
   | Groq | Transcrição (Whisper) e cérebro rápido | Opcional |
   | OpenRouter, Ollama Cloud, SiliconFlow | Provedores extras | Opcional |

   As chaves ficam criptografadas no aparelho (EncryptedSharedPreferences, AES-256).
2. **Permissões:** em **Ajustes > Celular e iniciativa**, toque em "Dar permissões". Microfone, contatos, agenda, SMS, telefone, notificações, sobreposição e, se quiser, a acessibilidade (para ler a tela e tocar no item que você pedir).
3. **Óculos:** feche o app oficial dos óculos. Na aba **Óculos**, procure e conecte. Pareie também o Bluetooth clássico dos óculos nas configurações do Android, para eles funcionarem como fone.
4. **Microfone da conversa:** em **Ajustes > Óculos**, deixe "Óculos como fone Bluetooth" (recomendado).
5. **Sua voz (opcional):** em **Ajustes > Voz e conversa**, grave a impressão vocal.
6. **Contexto:** em **Ajustes > Você e memória**, escreva quem você é, seus projetos e contatos importantes.
7. **Telegram e e-mail (opcional):** em **Ajustes > E-mail e Telegram**.

Diga **"Aura"**.

## Problemas comuns

| Sintoma | O que fazer |
|---|---|
| Não conecta nos óculos | Feche o app oficial. Em **Ajustes > Diagnóstico**, veja os serviços encontrados. Na aba Óculos, use "Reabrir canal QC" ou "Forçar reconexão QC". |
| Bipes seguidos nos óculos | Use o microfone "Óculos como fone Bluetooth" e confira se os óculos estão pareados como fone. |
| "Conversa ao vivo indisponível" | Confira a chave do Gemini e a internet. A Aura cai para o caminho clássico por alguns minutos. |
| Foto não chega | Diga "Aura, faz um autodiagnóstico". Veja os tempos de foto no Diagnóstico. |
| Ela não toca o vídeo do YouTube | Ligue a acessibilidade da Aura nas configurações do Android. |

Para relatar um problema, abra uma issue com o modelo dos óculos, o celular, a versão do Android e o texto de **Ajustes > Diagnóstico** (use "Copiar registro").
