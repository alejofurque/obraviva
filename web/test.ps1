# Corre la suite del frontend y la cierra cuando vitest imprime el resumen.
#
# Uso, desde web/:
#   .\test.ps1                                         # toda la suite
#   .\test.ps1 -Include "src/app/core/**/*.spec.ts"    # una parte
#
# Existe porque "ng test --watch=false" no termina solo: imprime el resumen y el proceso
# queda vivo (verificado en noviembre de 2026, tambien sobre codigo ya commiteado). La causa
# esta pendiente. Este script espera el resumen, cierra el proceso y
# muestra los totales y las fallas. Sale con 1 si fallo algun test o no hubo resumen.
#
# Texto ASCII a proposito: PowerShell 5.1 lee los .ps1 sin BOM con la codificacion ANSI de
# la maquina y rompe los acentos.

param([string]$Include = "")

$log = Join-Path $env:TEMP "obraviva-ng-test.log"
Remove-Item $log -ErrorAction SilentlyContinue

$arguments = "/c npx ng test --watch=false"
if ($Include) { $arguments += " --include `"$Include`"" }
$arguments += " > `"$log`" 2>&1"

$process = Start-Process -FilePath "cmd.exe" -ArgumentList $arguments -WorkingDirectory $PSScriptRoot -PassThru -WindowStyle Hidden
$deadline = (Get-Date).AddMinutes(10)
$summary = $false
while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
    Start-Sleep -Seconds 2
    if ((Test-Path $log) -and (Select-String -Path $log -Pattern "Duration|bundle generation failed" -Quiet)) {
        # El resumen termina de escribirse enseguida despues de "Duration".
        Start-Sleep -Seconds 2
        $summary = $true
        break
    }
}
if (-not $process.HasExited) { & taskkill.exe /PID $process.Id /T /F | Out-Null }

$text = [regex]::Replace((Get-Content $log -Raw -Encoding UTF8), "\x1b\[[0-9;]*m", "")
$lines = $text -split "`r?`n"
$lines | Where-Object { $_ -match "Test Files|Tests |FAIL|ERROR\]|AssertionError|bundle generation failed" } | Select-Object -First 80

if (-not $summary) {
    Write-Host "No hubo resumen de vitest (se corto por tiempo o fallo al compilar). Log: $log" -ForegroundColor Red
    exit 1
}
if ($lines | Where-Object { $_ -match "Tests .* failed|bundle generation failed" }) {
    Write-Host "Hay fallas. Log completo: $log" -ForegroundColor Red
    exit 1
}
exit 0
