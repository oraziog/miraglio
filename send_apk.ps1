<#
Invia un file via croc e apre il QR del link di ricezione.

USO:
  .\send_apk.ps1                  -> invia l'APK debug di miraglio (default) se esiste
  .\send_apk.ps1 C:\percorso\file  -> invia un file qualsiasi

Poi sul telefono / altro PC:
  - scansiona il QR che si apre (o apri il link mostrato), oppure
  - usa croc con il codice mostrato.

Doppio-click: usa send_apk.cmd (stessa cartella).
#>
param([string]$File = "")

$ErrorActionPreference = 'Continue'

# ---- 1. Trova croc.exe ----
$croc = ''
if (Test-Path (Join-Path $PSScriptRoot 'croc.exe')) {
    $croc = Join-Path $PSScriptRoot 'croc.exe'
}
if (-not $croc) {
    $candidates = @(
        "$env:USERPROFILE\Music\croc_v11.5.3_Windows-64bit\croc.exe",
        "$env:USERPROFILE\Music\croc.exe"
    )
    $croc = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $croc) {
    $found = Get-ChildItem "$env:USERPROFILE\Music" -Recurse -Filter croc.exe -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    if ($found) { $croc = $found }
}
if (-not $croc) {
    Write-Host "croc.exe non trovato. Mettici la cartella con croc.exe se non e' in ~\Music." -ForegroundColor Red
    Read-Host "Premi INVIO per chiudere"
    exit 1
}

# ---- 2. Trova il file da inviare ----
if (-not $File) {
    $apk = "$env:USERPROFILE\Music\miraglio_9\app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $apk)) {
        $apk = Get-ChildItem "$env:USERPROFILE\Music\miraglio_9\app\build\outputs\apk" -Recurse -Filter app-debug.apk -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
    if ($apk) { $File = $apk }
}
if (-not $File -or -not (Test-Path $File)) {
    $File = Read-Host "Percorso del file da inviare"
}
if (-not (Test-Path $File)) {
    Write-Host "File non trovato: $File" -ForegroundColor Red
    Read-Host "Premi INVIO per chiudere"
    exit 1
}
$File = (Resolve-Path $File).Path

# ---- 3. Codice e link ----
$code = "miraglio-$((Get-Random -Minimum 1000 -Maximum 9999))"
$link = "https://getcroc.com/?code=$code"
# Info del file: nome, dimensione e data di modifica, per riconoscere a
# colpo d'occhio quale build si sta inviando.
$info = Get-Item $File
$sizeMB = [math]::Round($info.Length / 1MB, 1)
$dataOra = $info.LastWriteTime.ToString("dd/MM/yyyy HH:mm", [System.Globalization.CultureInfo]::InvariantCulture)

Write-Host ""
Write-Host "Nome:       $($info.Name)" -ForegroundColor Cyan
Write-Host "Dimensione: $sizeMB MB ($($info.Length) byte)" -ForegroundColor Cyan
Write-Host "Data:       $dataOra" -ForegroundColor Cyan
Write-Host "Percorso:   $($info.FullName)" -ForegroundColor DarkGray
Write-Host "Codice:     $code" -ForegroundColor Cyan
Write-Host ""

# ---- 4. QR del link di ricezione (si apre da solo) ----
$qrDir = Join-Path $env:USERPROFILE 'Downloads'
if (-not (Test-Path $qrDir)) { $qrDir = $PSScriptRoot }
$qr = Join-Path $qrDir "qrcode_$code.png"
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $data = [uri]::EscapeDataString($link)
    Invoke-WebRequest -Uri "https://api.qrserver.com/v1/create-qr-code/?size=256x256&margin=16&data=$data" `
        -OutFile $qr -UseBasicParsing -TimeoutSec 30
    Write-Host "QR generato e aperto: $qr" -ForegroundColor Green
    Invoke-Item $qr
} catch {
    Write-Host "QR non generato (niente internet?): $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "LINK DI RICEZIONE (scansiona il QR / apri il link sul telefono):" -ForegroundColor Green
Write-Host "  $link" -ForegroundColor White
Write-Host "Oppure con croc usa il codice: $code" -ForegroundColor Green
Write-Host ""
Write-Host "In attesa del ricevente..." -ForegroundColor Cyan
Write-Host ""

# ---- 5. Invio (--yes: nessuna conferma, va prima di 'send') ----
& $croc --yes send --code $code $File
$rc = $LASTEXITCODE

Write-Host ""
if ($rc -eq 0) {
    Write-Host "Invio completato. Verifica che il file sia arrivato." -ForegroundColor Green
} else {
    Write-Host "croc chiuso con codice $rc (probabile timeout: il ricevente non si e' connesso in tempo). Rilancia e scansiona subito il QR." -ForegroundColor Yellow
}
Write-Host ""
Read-Host "Premi INVIO per chiudere"