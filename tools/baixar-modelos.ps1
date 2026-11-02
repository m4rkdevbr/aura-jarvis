# Baixa o modelo de voz offline (Vosk, português) para app\src\main\assets\vosk-pt.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root 'app\src\main\assets\vosk-pt'
$url = 'https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip'
if ((Test-Path (Join-Path $dest 'final.mdl')) -or (Test-Path (Join-Path $dest 'am\final.mdl'))) {
    Write-Host "Modelo já está em $dest"; exit 0
}
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("vosk-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Write-Host "Baixando $url"
    Invoke-WebRequest -Uri $url -OutFile (Join-Path $tmp 'model.zip')
    Expand-Archive -Path (Join-Path $tmp 'model.zip') -DestinationPath $tmp
    $src = Get-ChildItem $tmp -Directory | Where-Object { $_.Name -like 'vosk-model-*' } | Select-Object -First 1
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item -Path (Join-Path $src.FullName '*') -Destination $dest -Recurse -Force
    Write-Host "Pronto: modelo em $dest"
} finally {
    Remove-Item -Recurse -Force $tmp
}
