<#
Invia un file via croc e apre il QR del link di ricezione.

USO:
  .\send_apk.ps1                  -> invia l'APK piu' NUOVO fra debug e release
  .\send_apk.ps1 -Variante debug  -> forza la build debug
  .\send_apk.ps1 -Variante release-> forza la build release
  .\send_apk.ps1 C:\percorso\file -> invia un file qualsiasi
  .\send_apk.ps1 -Prova           -> mostra quale APK sceglierebbe e esce (nessun invio)

Poi sul telefono / altro PC:
  - scansiona il QR che si apre (o apri il link mostrato), oppure
  - usa croc con il codice mostrato.

Doppio-click: usa send_apk.cmd (stessa cartella).
#>
param(
    [string]$File = "",
    [ValidateSet('auto', 'debug', 'release')][string]$Variante = 'auto',
    [switch]$Prova
)

$ErrorActionPreference = 'Continue'

# ---- 0. Dove sta il progetto e dove finiscono gli APK ----
$project = $PSScriptRoot
if (-not (Test-Path (Join-Path $project 'settings.gradle.kts'))) {
    $project = "$env:USERPROFILE\Music\miraglio_9"
}
$outRoot = Join-Path $project 'app\build\outputs\apk'

$rootSlash = $project.TrimEnd('\') + '\'
# Percorso del file relativo al progetto: serve solo a far capire da quale
# cartella arriva (debug o release).
function Get-Sigla([string]$path) {
    if ($path -like "*$rootSlash*") { return $path.Substring($rootSlash.Length) }
    return $path
}

# L'APK piu' recente di una variante (debug / release), o $null se assente.
# Gli APK senza firma (`*-unsigned.apk`, che escono quando manca la chiave di
# rilascio) non si installano sul telefono: restano fuori dalla scelta.
function Get-ApkVariante([string]$nome) {
    $dir = Join-Path $outRoot $nome
    if (-not (Test-Path $dir)) { return $null }
    return Get-ChildItem $dir -Recurse -Filter *.apk -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*unsigned*' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

# Tolleranza in secondi fra le due date di modifica: sotto questa soglia i
# due APK si considerano compilati insieme.
$SogliaPariSecondi = 2

$scelto = $null
$varianteScelta = ''
$scartato = $null

# ---- 1. Trova il file da inviare ----
if ($File) {
    # Path indicato a mano: si usa quello, senza discutere.
} else {
    $debug = Get-ApkVariante 'debug'
    $release = Get-ApkVariante 'release'

    switch ($Variante) {
        'debug' { $scelto = $debug; $varianteScelta = 'debug' }
        'release' { $scelto = $release; $varianteScelta = 'release' }
        default {
            if ($debug -and $release) {
                $diff = [math]::Abs(($release.LastWriteTime - $debug.LastWriteTime).TotalSeconds)
                if ($diff -lt $SogliaPariSecondi) {
                    # Compilati insieme: vince la release, che e' quella da
                    # installare sul telefono.
                    $scelto = $release; $varianteScelta = 'release'; $scartato = $debug
                } elseif ($release.LastWriteTime -gt $debug.LastWriteTime) {
                    $scelto = $release; $varianteScelta = 'release'; $scartato = $debug
                } else {
                    $scelto = $debug; $varianteScelta = 'debug'; $scartato = $release
                }
            } elseif ($debug) {
                $scelto = $debug; $varianteScelta = 'debug'
            } elseif ($release) {
                $scelto = $release; $varianteScelta = 'release'
            } else {
                # Nessun APK compilato: ripiego sul vecchio percorso noto.
                $vecchio = Join-Path $outRoot 'debug\app-debug.apk'
                if (-not (Test-Path $vecchio)) {
                    $vecchio = Join-Path $project 'app\build\outputs\apk'
                }
                if (Test-Path $vecchio) {
                    $scelto = Get-ChildItem $vecchio -Recurse -Filter *.apk -File -ErrorAction SilentlyContinue |
                        Where-Object { $_.Name -notlike '*unsigned*' } |
                        Sort-Object LastWriteTime -Descending |
                        Select-Object -First 1
                }
            }
        }
    }
    if ($scelto) { $File = $scelto.FullName }
}

if (-not $File) {
    $File = Read-Host "Percorso del file da inviare"
}
# Percorso vuoto (INVIO a vuoto) o inesistente: Test-Path su stringa vuota
# darebbe errore invece di fermarsi.
if (-not $File -or -not (Test-Path $File)) {
    $mostra = if ($File) { $File } else { 'nessun percorso indicato' }
    Write-Host "File non trovato: $mostra" -ForegroundColor Red
    Read-Host "Premi INVIO per chiudere"
    exit 1
}
$File = (Resolve-Path $File).Path
$info = Get-Item $File

# Se il file e' arrivato da un -Variante o da un percorso, lo si etichetta
# comunque in base a dove si trova.
if (-not $varianteScelta) {
    if ($File -like "*$rootSlash*release\*") { $varianteScelta = 'release' }
    elseif ($File -like "*$rootSlash*debug\*") { $varianteScelta = 'debug' }
}

$dataOra = $info.LastWriteTime.ToString("dd/MM/yyyy HH:mm", [System.Globalization.CultureInfo]::InvariantCulture)

# Dimensione in MB con la virgola decimale, come nel resto dell'app,
# indipendente dalla lingua del sistema.
function Format-MB([long]$byte) {
    $mb = [math]::Round($byte / 1MB, 1)
    return $mb.ToString('0.0', [System.Globalization.CultureInfo]::InvariantCulture).Replace('.', ',')
}

function Show-File([string]$etichetta, $f) {
    $quando = $f.LastWriteTime.ToString("dd/MM/yyyy HH:mm:ss", [System.Globalization.CultureInfo]::InvariantCulture)
    Write-Host ("{0,-9} {1}  ({2} MB, {3})" -f $etichetta, $f.Name, (Format-MB $f.Length), $quando)
}

Write-Host ""
if ($varianteScelta -eq 'release') {
    Write-Host "APK scelto: RELEASE (il piu' recente)" -ForegroundColor Green
} elseif ($varianteScelta -eq 'debug') {
    Write-Host "APK scelto: DEBUG (il piu' recente)" -ForegroundColor Green
} else {
    Write-Host "File scelto:" -ForegroundColor Green
}
Write-Host ""
Show-File "SCELTO:" $info
if ($scartato) {
    Show-File "ALTRO:" $scartato
    Write-Host ""
    $altraVar = if ($varianteScelta -eq 'release') { 'debug' } else { 'release' }
    Write-Host "Per inviare l'altro:  .\send_apk.ps1 -Variante $altraVar" -ForegroundColor DarkGray
}
Write-Host ""
Write-Host "Nome:       $($info.Name)" -ForegroundColor Cyan
if ($varianteScelta) {
    Write-Host "Variante:   $varianteScelta  ($(Get-Sigla $File))" -ForegroundColor Cyan
} else {
    Write-Host "Variante:   non ricavata (file indicato a mano)" -ForegroundColor Cyan
}
Write-Host "Dimensione: $(Format-MB $info.Length) MB ($($info.Length) byte)" -ForegroundColor Cyan
Write-Host "Data:       $dataOra" -ForegroundColor Cyan
Write-Host "Percorso:   $($info.FullName)" -ForegroundColor DarkGray
Write-Host ""

if ($Prova) {
    Write-Host "Prova: nessun file inviato." -ForegroundColor Yellow
    exit 0
}

# ---- 2. Trova croc.exe ----
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

# ---- 3. Codice e link ----
$code = "miraglio-$((Get-Random -Minimum 1000 -Maximum 9999))"
$link = "https://getcroc.com/?code=$code"

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
