# Renueva la sesión de desarrollo y deja $headers listo en la consola actual.
#
# Uso:  . .\login-dev.ps1        <-- el punto y el espacio del principio son
#                                    necesarios: sin eso el script corre en su
#                                    propio alcance y $headers no sobrevive.
#
# El token de acceso dura 15 minutos, asi que esto se repite seguido durante una
# sesion de verificacion manual.
#
# Texto ASCII a proposito: PowerShell 5.1 lee los .ps1 sin BOM con la codificacion
# ANSI de la maquina y rompe los acentos.

$ErrorActionPreference = "Stop"

$login = Invoke-RestMethod -Uri http://localhost:8080/api/auth/login -Method Post `
    -ContentType "application/json" `
    -Body '{"email":"admin@obraviva.local","password":"admin123"}'

$global:headers = @{ Authorization = "Bearer $($login.accessToken)" }

Write-Host "Sesion renovada. `$headers listo." -ForegroundColor Green