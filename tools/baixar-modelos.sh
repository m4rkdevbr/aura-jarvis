#!/usr/bin/env bash
# Baixa o modelo de voz offline (Vosk, português) para app/src/main/assets/vosk-pt.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/assets/vosk-pt
URL=https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip
if [ -f "$DEST/final.mdl" ] || [ -f "$DEST/am/final.mdl" ]; then
  echo "Modelo já está em $DEST"; exit 0
fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
echo "Baixando $URL"
curl -fL "$URL" -o "$TMP/model.zip"
unzip -q "$TMP/model.zip" -d "$TMP"
SRC=$(find "$TMP" -maxdepth 1 -type d -name 'vosk-model-*' | head -1)
mkdir -p "$DEST"
cp -R "$SRC"/. "$DEST"/
echo "Pronto: $(find "$DEST" -type f | wc -l) arquivos em $DEST"
