# Levantar ObraViva

Desde la raíz del repo, en una consola común (sin administrador):

```powershell
.\levantar.ps1              # audios con Gemini y whisper reales
.\levantar.ps1 -Simulado    # audios con los simuladores: sin claves ni modelo de whisper
```

El script:

1. Crea `obraviva-platform\.env` desde `.env.example` si no existe. Verifica que el 8080 y
   el 4200 estén libres.
2. Abre Docker Desktop si está cerrado, corre `docker compose up -d` y espera a Postgres
   (5433) y a MinIO (9000).
3. Sin `-Simulado`, avisa si falta ffmpeg con el filtro whisper o el modelo de
   `OBRAVIVA_WHISPER_MODEL`. La transcripción no es un servicio aparte: el backend lanza
   ffmpeg por cada audio.
4. Corre `npm ci` si falta `web\node_modules`. Abre dos ventanas: `ObraViva backend`
   (`run-dev.ps1`) y `ObraViva web` (`ng serve`). Espera a `/actuator/health` y al 4200.
   La primera vez espera más, porque Maven baja todo y compila.
5. Muestra qué adaptadores quedaron activos y, con KIRI real, cuántos créditos quedan.

Al final muestra `http://localhost:4200`. Para apagar: Ctrl+C en las dos ventanas.
Postgres y MinIO siguen corriendo en Docker.

## Una sola vez

- **Docker Desktop** instalado. El script lo abre si está cerrado y espera hasta tres
  minutos. Si activás "Start Docker Desktop when you sign in" en sus ajustes, ya está
  listo al prender la compu.
- **ffmpeg con el filtro whisper**: la compilación full (`winget install Gyan.FFmpeg`).
- **El modelo de whisper** (`ggml-small.bin`), con su ruta en `OBRAVIVA_WHISPER_MODEL`
  dentro de `obraviva-platform\.env`.
- **El `.env`** con `GEMINI_API_KEY` y, si se usa KIRI, `KIRI_API_KEY`.

Con `-Simulado` no hace falta nada de lo anterior salvo Docker Desktop.

## Gemini y KIRI

- `levantar.ps1` arranca con Gemini real: define `SPRING_AI_MODEL_CHAT=google-genai` y
  `OBRAVIVA_AI_EXTRACTION_PROVIDER=gemini` sólo para la ventana del backend. Con
  `-Simulado`, además, `OBRAVIVA_AI_TRANSCRIPTION_PROVIDER=mock`.
  `application.yml` sigue en el simulador, así que los tests y `run-dev.ps1` solo no
  cambian.
- KIRI queda como diga `OBRAVIVA_RECONSTRUCTION_PROVIDER` en el `.env`. Con KIRI real,
  cada video que se sube consume un crédito.

## Si algo no anda

| Síntoma | Qué hacer |
|---|---|
| "El 8080 ya está en uso" | Quedó un backend abierto de antes: cerralo con Ctrl+C |
| "El backend no arrancó" | Mirá la ventana `ObraViva backend` |
| Audios: el log no dice REAL | Revisá `GEMINI_API_KEY` en el `.env` |
| Los audios fallan al transcribir | El script avisa si falta ffmpeg con whisper o el modelo |

El log del backend queda en `%TEMP%\obraviva-backend.log`.
