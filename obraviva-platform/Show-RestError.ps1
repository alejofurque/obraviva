# Muestra el cuerpo de una respuesta HTTP de error.
#
# Uso:  . .\Show-RestError.ps1
#       try { ... } catch { Show-RestError $_ }
#
# Windows PowerShell 5.1 no expone el cuerpo de un error en Invoke-RestMethod:
# solo el codigo. Sin esto, un 409 o un 400 llegan sin explicacion.

function global:Show-RestError {
    param($ErrorRecord)

    $response = $ErrorRecord.Exception.Response
    if ($null -eq $response) {
        Write-Host $ErrorRecord.Exception.Message -ForegroundColor Red
        return
    }

    $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
    $body = $reader.ReadToEnd()
    $reader.Close()

    if ([string]::IsNullOrWhiteSpace($body)) {
        Write-Host "Sin cuerpo. Codigo: $([int]$response.StatusCode)" -ForegroundColor Yellow
    } else {
        Write-Host $body -ForegroundColor Yellow
    }
}