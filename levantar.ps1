# Levanta ObraViva completo en la compu: Postgres y MinIO en Docker, y el backend y
# ng serve, cada uno en su ventana. Al final muestra la direccion.
#
# Uso, desde la raiz del repo, sin administrador:
#   .\levantar.ps1              # interpretacion de audios con Gemini real
#   .\levantar.ps1 -Simulado    # con los simuladores de transcripcion e interpretacion:
#                               # no hacen falta claves ni el modelo de whisper
#
# Que hace:
#   1. Verifica que el 8080 y el 4200 esten libres.
#   2. Abre Docker Desktop si esta cerrado, levanta Compose y espera a Postgres y MinIO.
#   3. Avisa si falta ffmpeg con el filtro whisper o el modelo de transcripcion.
#   4. Corre npm ci si falta node_modules, arranca el backend con run-dev.ps1 y ng serve,
#      y espera a /actuator/health.
#   5. Muestra que adaptadores quedaron activos y los creditos de KIRI.
#
# Nunca imprime claves: las del .env se leen solo para saber si estan y para consultar
# el saldo de KIRI.
#
# Texto ASCII a proposito: PowerShell 5.1 lee los .ps1 sin BOM con la codificacion ANSI
# de la maquina y rompe los acentos.

param([switch]$Simulado)

$ErrorActionPreference = "Stop"

$platform = Join-Path $PSScriptRoot "obraviva-platform"
$web = Join-Path $PSScriptRoot "web"
$backendLog = Join-Path $env:TEMP "obraviva-backend.log"
$kiriBalanceUrl = "https://api.kiriengine.app/api/v1/open/balance"

function Step($text) { Write-Host ""; Write-Host $text -ForegroundColor Cyan }
function Ok($text) { Write-Host ("  " + $text) -ForegroundColor Green }
function Warn($text) { Write-Host ("  " + $text) -ForegroundColor Yellow }
function Fail($text) { Write-Host ("  " + $text) -ForegroundColor Red; exit 1 }

# Un comando nativo que escribe en stderr corta el script en PowerShell 5.1 si
# ErrorActionPreference es Stop. Aca se corre con Continue y se mira el codigo de salida.
function Run-Native([scriptblock]$command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $command 2>&1 | Out-String
        return [pscustomobject]@{ Code = $LASTEXITCODE; Output = $output }
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Listener($port) {
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
}

function Wait-Until([int]$seconds, [scriptblock]$condition) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (& $condition) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# El .env, como lo lee run-dev.ps1: solo para saber que hay, nunca para mostrarlo.
function Read-EnvFile($path) {
    $values = @{}
    foreach ($raw in Get-Content $path) {
        $line = $raw.Trim()
        if ($line -eq "" -or $line.StartsWith("#")) { continue }
        $separator = $line.IndexOf("=")
        if ($separator -lt 1) { continue }
        $value = $line.Substring($separator + 1).Trim()
        if ($value.StartsWith('"') -and $value.EndsWith('"') -and $value.Length -ge 2) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$line.Substring(0, $separator).Trim()] = $value
    }
    return $values
}

$envFile = Join-Path $platform ".env"
if (-not (Test-Path $envFile)) {
    Copy-Item (Join-Path $platform ".env.example") $envFile
    Write-Host "Se creo obraviva-platform\.env a partir de .env.example."
}
$dotenv = Read-EnvFile $envFile
if (-not $Simulado -and -not $dotenv["GEMINI_API_KEY"]) {
    Fail "GEMINI_API_KEY esta vacia en el .env. Cargala o arranca con -Simulado."
}

# --- 1. Puertos -----------------------------------------------------------------------

Step "Puertos"
foreach ($port in 8080, 4200) {
    $busy = Listener $port
    if ($busy) {
        $process = Get-Process -Id $busy.OwningProcess -ErrorAction SilentlyContinue
        Fail ("El {0} ya esta en uso ({1}, PID {2}). Cerra ese backend o ng serve y volve a correr el script." -f $port, $process.ProcessName, $busy.OwningProcess)
    }
}
Ok "8080 y 4200 libres."

# --- 2. Docker ------------------------------------------------------------------------

Step "Docker"
if ((Run-Native { docker info }).Code -ne 0) {
    $desktop = Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
    if (-not (Test-Path $desktop)) { Fail "Docker no responde y no se encontro Docker Desktop en $desktop." }
    Write-Host "  Docker no responde: abriendo Docker Desktop..."
    Start-Process $desktop
    if (-not (Wait-Until 180 { (Run-Native { docker info }).Code -eq 0 })) {
        Fail "Docker Desktop no respondio en 3 minutos. Abrilo a mano y volve a correr el script."
    }
}
Ok "Docker responde."

$compose = Run-Native { docker compose -f (Join-Path $platform "docker-compose.yml") up -d }
if ($compose.Code -ne 0) { Write-Host $compose.Output; Fail "docker compose up -d fallo." }

$postgresReady = Wait-Until 90 {
    (Run-Native { docker inspect -f "{{.State.Health.Status}}" obraviva-postgres }).Output.Trim() -eq "healthy"
}
if (-not $postgresReady) { Fail "Postgres no quedo sano en 90 segundos: docker logs obraviva-postgres" }
Ok "Postgres listo en el 5433."

$minioReady = Wait-Until 60 {
    try { (Invoke-WebRequest "http://localhost:9000/minio/health/live" -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200 } catch { $false }
}
if (-not $minioReady) { Fail "MinIO no respondio en 60 segundos: docker logs obraviva-minio" }
Ok "MinIO listo en el 9000."

# --- 3. Transcripcion -----------------------------------------------------------------

Step "Transcripcion"
if ($Simulado) {
    Ok "Simulada: no hace falta el modelo de whisper."
    if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
        Warn "No esta ffmpeg en el PATH: las fotos nuevas van sin miniaturas y no se pueden subir videos."
    }
} else {
    if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
        Warn "No esta ffmpeg en el PATH: los audios no se van a poder transcribir."
    } elseif ((Run-Native { ffmpeg -hide_banner -filters }).Output -notmatch "\swhisper\s") {
        Warn "El ffmpeg del PATH no trae el filtro whisper: hace falta una compilacion full."
    } else {
        Ok "ffmpeg con el filtro whisper."
    }
    $model = $dotenv["OBRAVIVA_WHISPER_MODEL"]
    if (-not $model) {
        Warn "OBRAVIVA_WHISPER_MODEL no esta en el .env: los audios no se van a poder transcribir."
    } elseif (-not (Test-Path $model -PathType Leaf)) {
        Warn ("No existe el modelo que indica OBRAVIVA_WHISPER_MODEL ({0})." -f (Split-Path $model -Leaf))
    } else {
        Ok ("Modelo {0}." -f (Split-Path $model -Leaf))
    }
}

# --- 4. Backend y frontend ------------------------------------------------------------

Step "Backend y frontend"
if (-not (Test-Path (Join-Path $web "node_modules"))) {
    Write-Host "  Instalando las dependencias del frontend (npm ci). La primera vez tarda unos minutos..."
    $install = Run-Native { npm.cmd ci --prefix $web }
    if ($install.Code -ne 0) { Write-Host $install.Output; Fail "npm ci fallo." }
    Ok "Dependencias del frontend instaladas."
}
# La primera vez Maven baja todo y compila: con el tope de siempre el script se rendiria antes.
$firstRun = -not (Test-Path (Join-Path $platform "target"))
$backendWait = $(if ($firstRun) { 900 } else { 300 })
$webWait = $(if ($firstRun) { 300 } else { 180 })
# Las variables se definen en esta consola solo para que las herede la ventana del
# backend; despues se restauran. run-dev.ps1 agrega encima las del .env.
$childEnv = [ordered]@{
    SPRING_AI_MODEL_CHAT               = $(if ($Simulado) { "none" } else { "google-genai" })
    OBRAVIVA_AI_EXTRACTION_PROVIDER    = $(if ($Simulado) { "mock" } else { "gemini" })
    OBRAVIVA_AI_TRANSCRIPTION_PROVIDER = $(if ($Simulado) { "mock" } else { "whisper" })
    # El backend tambien escribe el log en un archivo: de ahi se leen los adaptadores.
    LOGGING_FILE_NAME                  = $backendLog
}
$saved = @{}
foreach ($name in $childEnv.Keys) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
    [Environment]::SetEnvironmentVariable($name, $childEnv[$name], "Process")
}
Remove-Item $backendLog -ErrorAction SilentlyContinue
try {
    $backendCommand = "`$host.UI.RawUI.WindowTitle = 'ObraViva backend'; & '{0}'" -f (Join-Path $platform "run-dev.ps1")
    Start-Process powershell.exe -WorkingDirectory $platform -ArgumentList "-NoExit", "-Command", $backendCommand | Out-Null
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], "Process") }
}
$webCommand = "`$host.UI.RawUI.WindowTitle = 'ObraViva web'; npx ng serve"
Start-Process powershell.exe -WorkingDirectory $web -ArgumentList "-NoExit", "-Command", $webCommand | Out-Null
Write-Host "  Se abrieron dos ventanas: 'ObraViva backend' y 'ObraViva web'. Esperando..."

$backendUp = Wait-Until $backendWait {
    if ((Test-Path $backendLog) -and (Select-String -Path $backendLog -Pattern "APPLICATION FAILED TO START" -Quiet)) {
        Fail "El backend no arranco: mira la ventana 'ObraViva backend'."
    }
    try { (Invoke-RestMethod "http://localhost:8080/actuator/health" -TimeoutSec 3).status -eq "UP" } catch { $false }
}
if (-not $backendUp) { Fail ("El backend no respondio en {0} minutos: mira la ventana 'ObraViva backend'." -f ($backendWait / 60)) }
Ok "Backend listo en el 8080."

if (-not (Wait-Until $webWait { [bool](Listener 4200) })) { Fail ("ng serve no abrio el 4200 en {0} minutos: mira la ventana 'ObraViva web'." -f ($webWait / 60)) }
Ok "ng serve listo en el 4200."

# --- 5. Adaptadores y creditos --------------------------------------------------------

Step "Adaptadores"
function Adapter($pattern) {
    $line = Select-String -Path $backendLog -Encoding UTF8 -Pattern $pattern | Select-Object -First 1
    if ($line) { return $line.Matches[0].Groups[1].Value }
    return $null
}
$audio = Adapter "Adaptador de interpretaci.n (REAL|SIMULADO) activo"
$expected = if ($Simulado) { "SIMULADO" } else { "REAL" }
if ($audio -eq $expected) {
    Ok ("Audios: Gemini {0}." -f $(if ($audio -eq "REAL") { "REAL (cada audio consume cuota)" } else { "SIMULADO" }))
} else {
    Warn ("Audios: se pidio {0} y el log dice '{1}'." -f $expected, $audio)
}

$transcriber = Adapter "Adaptador de transcripci.n (REAL|SIMULADO) activo"
if ($transcriber -eq $expected) {
    Ok ("Transcripcion: {0}." -f $(if ($transcriber -eq "REAL") { "whisper local" } else { "SIMULADA" }))
} else {
    Warn ("Transcripcion: se pidio {0} y el log dice '{1}'." -f $expected, $transcriber)
}

$kiri = Adapter "Adaptador de reconstrucci.n (REAL|SIMULADO) activo"
if ($kiri -eq "REAL") {
    $credits = "no se pudo leer el saldo"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $balance = Invoke-RestMethod $kiriBalanceUrl -Headers @{ Authorization = "Bearer " + $dotenv["KIRI_API_KEY"] } -TimeoutSec 15
        if ($balance.data.balance -ne $null) { $credits = "quedan {0} creditos" -f $balance.data.balance }
    } catch { }
    Warn ("Reconstruccion 3D: KIRI REAL, {0}. Cada video que se sube consume uno." -f $credits)
} else {
    Ok ("Reconstruccion 3D: {0}." -f $(if ($kiri) { "KIRI $kiri" } else { "sin dato en el log" }))
}

# --- Listo ----------------------------------------------------------------------------

Write-Host ""
Write-Host "Listo: http://localhost:4200" -ForegroundColor Green
Write-Host ("  Log del backend: {0}" -f $backendLog)
Write-Host "  Para apagar: Ctrl+C en las dos ventanas. Postgres y MinIO siguen en Docker."
