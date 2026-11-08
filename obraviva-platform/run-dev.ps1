# Arranca el backend de ObraViva cargando el archivo .env.
#
# Existe porque el .env solo lo lee el depurador de VS Code: con
# "mvnw spring-boot:run" las variables no llegan al proceso y la aplicacion
# arranca sin claves de API, fallando despues de un modo que no se parece a
# "falta una credencial".
#
# Nunca imprime valores, solo la longitud de cada variable: alcanza para ver de
# un vistazo si alguna quedo vacia, sin que una clave termine en el historial de
# la consola ni en una captura de pantalla.
#
# El texto de este script es ASCII a proposito: Windows PowerShell 5.1 lee los
# .ps1 sin BOM con la codificacion ANSI de la maquina y los acentos salen rotos.

$ErrorActionPreference = "Stop"

$envFile = Join-Path $PSScriptRoot ".env"

if (-not (Test-Path $envFile)) {
    Write-Host "No se encontro .env en $envFile" -ForegroundColor Red
    Write-Host "Sin el, las claves de API quedan vacias." -ForegroundColor Red
    exit 1
}

Write-Host "Cargando variables desde .env" -ForegroundColor Cyan

Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()

    if ($line -eq "" -or $line.StartsWith("#")) {
        return
    }

    $separator = $line.IndexOf("=")
    if ($separator -lt 1) {
        return
    }

    $name = $line.Substring(0, $separator).Trim()
    $value = $line.Substring($separator + 1).Trim()

    # Se admiten valores entrecomillados, pero las comillas no forman parte del
    # valor: una clave con comillas alrededor falla con un error de autenticacion
    # que no menciona las comillas.
    if ($value.StartsWith('"') -and $value.EndsWith('"') -and $value.Length -ge 2) {
        $value = $value.Substring(1, $value.Length - 2)
    }

    Set-Item -Path "Env:$name" -Value $value

    if ($value.Length -eq 0) {
        Write-Host ("  {0}: VACIA" -f $name) -ForegroundColor Yellow
    } else {
        Write-Host ("  {0}: {1} caracteres" -f $name, $value.Length) -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "Arrancando el backend. Ctrl+C para detenerlo." -ForegroundColor Cyan
Write-Host ""

& (Join-Path $PSScriptRoot "mvnw.cmd") spring-boot:run