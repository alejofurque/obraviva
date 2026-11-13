# ObraViva

Plataforma web que convierte una propiedad en construcción en una experiencia digital. Quien compra una casa en pozo suele enterarse de la obra por fotos sueltas y mensajes, y quien la desarrolla reparte su tiempo entre avisar, ordenar información y controlar gastos en planillas separadas. ObraViva junta todo en un solo lugar: el encargado cuenta lo que pasó con un audio o una foto desde el teléfono, la IA lo ordena y una persona lo confirma, el desarrollista ve el avance físico contra el gasto, y el comprador recorre su casa en 3D y viaja en el tiempo entre las distintas capturas.

Es el Trabajo Final de Graduación de Ingeniería en Software. Corre completo en una sola computadora.

> **Video de demostración:** https://drive.google.com/file/d/1NJM5ixojFTSha6HkglitGvA3Cgkt_D3m/view?usp=drive_link

## Funcionalidades por rol

**Responsable del desarrollo (dueño)**
- Desarrollos y casas: alta de varias casas de una vez con una plantilla de 12 etapas, y obras comunes.
- Resumen por desarrollo y por casa, con lo que hay para revisar.
- Novedades: todo lo que publican los encargados, con lo que todavía no vio.
- Etapas e Historia de cada casa, y corrección de lo publicado.
- Panel económico: presupuesto contra gasto y avance por etapa, curva de gasto y costo final estimado.
- Ficha y galería, planos con ambientes, documentos, proyecto contra realidad y estado de venta.
- Revisión de los audios interpretados por la IA antes de publicarlos.
- Reportes de período redactados por la IA sobre cifras que calcula el sistema.
- Equipo y enlaces públicos para compartir una casa.

**Encargado de obra**
- Pantallas pensadas para el teléfono: Hoy, La casa y Mis publicaciones.
- Publicar avance con fotos y cambio de estado de la etapa.
- Contarlo con audio: la IA transcribe e interpreta, y la persona revisa antes de publicar.
- Recorrido de hoy, lugar por lugar, con fotos, videos y capturas 3D.

**Comprador**
- Mi casa: avance, entrega, lo que pasó este mes y lo que viene.
- Recorrido 3D en el navegador, con línea de tiempo por mes.
- Historia de la obra, planos, documentos de su casa y lugar por lugar.
- Enlaces para mostrar la casa a la familia, que se abren sin cuenta.

La IA extrae, interpreta y redacta; el sistema calcula; una persona confirma. Ninguna cifra sale de un modelo de lenguaje y nada generado se publica sin revisión.

## Tecnología

| Parte | Tecnología |
|---|---|
| Backend | Java 21, Spring Boot 4.1, Spring Data JPA, Flyway, Spring AI |
| Base de datos | PostgreSQL 16 |
| Almacenamiento | MinIO, compatible con S3 |
| Frontend | Angular 21 con signals y sin zone.js, Angular Material, SCSS |
| Visor 3D | Three.js y Spark (Gaussian Splatting) |
| IA | Gemini para interpretar y redactar, whisper.cpp local para transcribir |
| Reconstrucción 3D | KIRI Engine, gsbox para la conversión a SPZ |
| Tests | JUnit 5, Testcontainers, Vitest |

Cada servicio externo está detrás de un puerto con su adaptador simulado, así que el sistema se puede usar entero sin claves.

## Requisitos

Windows 10 u 11, con conexión a internet la primera vez, y:

- [Git](https://git-scm.com/downloads).
- [Docker Desktop](https://www.docker.com/products/docker-desktop/).
- [JDK 21](https://adoptium.net/), con `java` en el `PATH` o `JAVA_HOME` definido.
- [Node.js](https://nodejs.org/) 20.19 o posterior, 22.12 o posterior, o 24.
- Libres los puertos 4200, 8080, 5433, 9000 y 9001.

Recomendado: ffmpeg (`winget install Gyan.FFmpeg`). Sin él, las fotos nuevas se ven sin miniaturas y no se pueden subir videos.

## Instalación y ejecución

No hace falta ninguna clave ni permisos de administrador. En una consola de PowerShell:

1. Cloná el repositorio y entrá en la carpeta:

   ```powershell
   git clone https://github.com/alejofurque/obraviva.git obraviva
   cd obraviva
   ```

2. Permití los scripts sólo en esta consola:

   ```powershell
   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
   ```

3. Levantá todo con los simuladores:

   ```powershell
   .\levantar.ps1 -Simulado
   ```

   El script crea `obraviva-platform\.env` desde `.env.example`, abre Docker Desktop si está cerrado, levanta PostgreSQL y MinIO, instala las dependencias del frontend, y abre dos ventanas: `ObraViva backend` y `ObraViva web`. La primera vez tarda varios minutos, porque descarga las imágenes de Docker y las dependencias, y compila.

4. Cuando termine con `Listo: http://localhost:4200`, abrí esa dirección. Los datos de demostración se cargan solos en el primer arranque: si recién entrás y no ves los barrios, esperá unos segundos y recargá.

Para apagar: Ctrl+C en las dos ventanas. PostgreSQL y MinIO siguen corriendo en Docker y conservan los datos. Más detalle y problemas comunes en [docs/levantar.md](docs/levantar.md).

## Cuentas de prueba

| Rol | Correo | Contraseña | Qué ve |
|---|---|---|---|
| Responsable (dueño) | `admin@obraviva.local` | `admin123` | Todo: Barrio Los Aromos y Barrio El Nogal |
| Encargado de los dos barrios | `encargado@obraviva.local` | `demo1234` | Todas las casas |
| Encargado de casas puntuales | `carlos.medina@obraviva.local` | `demo1234` | Casa 1 y Casa 2 de Los Aromos |
| Comprador | `familia.aromos@obraviva.local` | `demo1234` | Casa 1 de Los Aromos |
| Comprador | `comprador.nogal@obraviva.local` | `demo1234` | Casa 1 de El Nogal |

Son datos inventados. Las imágenes de los barrios son ilustraciones de ejemplo y todos los recorridos 3D usan la misma captura de muestra, así que el viaje en el tiempo cambia de fecha sin que cambie el modelo.

## Recorrido sugerido

**Comprador** (`familia.aromos@obraviva.local`)
1. En *Mi casa*, mirá el avance, la entrega y lo que pasó este mes.
2. Entrá a *Recorrer en 3D*, movete por la escena y cambiá de mes en la línea de tiempo.
3. Pasá por *Historia* y *Documentos*.
4. Desde *Mi casa*, abrí los planos y tocá un ambiente para ver su nombre.

**Encargado** (`carlos.medina@obraviva.local`, conviene con el navegador a ancho de teléfono)
1. En *Hoy*, elegí la casa y mirá las etapas en curso.
2. *Publicar avance*: elegí una etapa, escribí qué se hizo, agregá una foto, ajustá el porcentaje y publicá.
3. *Contarlo con audio*: grabá unos segundos o elegí cualquier audio. Con `-Simulado`, la transcripción es siempre el mismo texto de ejemplo, y lo dice. Revisá lo propuesto y publicá.
4. *Recorrido de hoy*: en un lugar sin material, *Subir archivo de Scaniverse* acepta el `.spz` de `obraviva-platform\src\main\resources\demo\`.

**Responsable** (`admin@obraviva.local`)
1. En *Barrio Los Aromos*, mirá el *Resumen* y *Novedades*: ahí aparece lo que publicó el encargado.
2. Entrá a *Casas*, abrí la Casa 2, que está demorada, y mirá *Etapas* e *Historia*.
3. *Económico*: presupuesto contra avance y costo final estimado.
4. En la Casa 1, *Venta* y *Crear enlace*; abrí ese enlace en una ventana de incógnito: es lo que ve la familia sin cuenta.
5. *Ver como el comprador*, desde el encabezado de la casa.

## Activar los servicios reales (opcional)

Las claves son tuyas y van en `obraviva-platform\.env`, que no se versiona. Después de editarlo, volvé a correr `levantar.ps1`.

**Audios con Gemini y whisper**
1. Instalá ffmpeg con el filtro whisper: `winget install Gyan.FFmpeg`.
2. Descargá el modelo [`ggml-small.bin`](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin) (488 MB).
3. Sacá una clave en [Google AI Studio](https://aistudio.google.com/apikey).
4. En el `.env`, descomentá y completá `GEMINI_API_KEY` y `OBRAVIVA_WHISPER_MODEL`.
5. Corré `.\levantar.ps1`, sin `-Simulado`.

**Reconstrucción 3D con KIRI Engine**
1. Instalá [gsbox](https://github.com/gotoeasy/gsbox/releases) 4.7.2 o posterior y dejalo en el `PATH`, junto con ffmpeg.
2. Sacá una clave en [KIRI Engine](https://www.kiriengine.app/).
3. En el `.env`, descomentá `OBRAVIVA_RECONSTRUCTION_PROVIDER=kiri` y completá `KIRI_API_KEY`. Cada video que se sube consume un crédito.

Sin KIRI, el simulador procesa un video de punta a punta sólo si tiene ffmpeg, gsbox y un PLY de muestra en `OBRAVIVA_SAMPLE_PLY`. Subir un `.spz` no necesita nada de eso.

## Tests

Hace falta Docker encendido: los del backend levantan su propio PostgreSQL y su propio MinIO, y no tocan los contenedores de desarrollo, que pueden estar apagados.

```powershell
cd web
.\test.ps1
```

```powershell
cd obraviva-platform
.\mvnw.cmd test
```

Los tests que necesitan ffmpeg, gsbox o el modelo de whisper se omiten solos si no están disponibles. Usan siempre los simuladores: no consumen cuota ni créditos.

## Estructura del repositorio

```
obraviva/
├── levantar.ps1              Levanta todo el sistema
├── docs/
│   ├── levantar.md           Detalle del arranque y problemas comunes
│   ├── obraviva-documento-maestro.md
│   ├── decisiones.md         Decisiones técnicas y su motivo
│   └── obraviva-poc-3d-v1.1.md
├── obraviva-platform/        Backend: monolito modular en Spring Boot
│   ├── docker-compose.yml    PostgreSQL y MinIO
│   ├── .env.example          Configuración local, sin claves
│   ├── run-dev.ps1           Arranca sólo el backend
│   └── src/main/
│       ├── java/.../platform/  Un paquete por dominio: developments, progress,
│       │                       media, captures3d, ai, reports, sharing, ...
│       └── resources/          application.yml, migraciones Flyway y material de demostración
└── web/                      Frontend en Angular
    ├── test.ps1              Corre la suite
    └── src/app/
        ├── core/             Autenticación, API y navegación
        ├── shell/            Marco de la aplicación
        ├── shared/           Componentes y utilidades comunes
        └── features/         works (dueño), field (encargado), property (comprador),
                              viewer (visor 3D), login, home, account
```
