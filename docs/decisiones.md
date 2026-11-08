# ObraViva — Registro de decisiones

Por qué el sistema está hecho así. Es histórico: las entradas se agregan, no se
corrigen. Una decisión que se revisa se anota como decisión nueva, con su fecha y su
motivo, debajo de la anterior.

Esto no se deduce leyendo el código. El código muestra que gsbox se invoca con
`-rx 180`; no muestra que sea por la diferencia entre las convenciones RUB y RDF, ni
que `-q 9` se descartó tras una comparación A/B. Sin este registro, un parámetro
verificado experimentalmente parece arbitrario y se "mejora".

También es materia prima de la documentación final: el aporte de ingeniería del
proyecto no es que funcione, es por qué está resuelto de esta manera.

---

### Arquitectura y organización del código

- **Paquetes por módulo de dominio**, no por capa técnica. Nada de carpetas globales `controllers/`, `services/`.
- **Entre módulos, sólo referencias por UUID.** Sin relaciones JPA cruzadas; la integridad la garantizan las FK.
- **APIs públicas explícitas entre módulos:** `OrganizationMembershipService`, `DevelopmentAccessService`, `StageCatalogService`, `ProgressService`. Ningún módulo toca entidades ajenas.
- **UUIDs generados por la aplicación**, no por la base (necesario para trabajos asincrónicos).
- Campos de dependencias siempre `final` + inyección por constructor.
- Las respuestas HTTP son records de proyección, nunca entidades JPA.
- **Toda integración externa detrás de un puerto** con adaptadores intercambiables, y un adaptador simulado para el desarrollo diario. Excepción deliberada: la transcripción no tiene puerto abstracto, porque la abstracción de proveedor que exige el documento maestro corresponde al modelo de lenguaje. Una interfaz con una sola implementación sería ceremonia sin beneficio.
- **La selección de adaptador usa condicionales explícitos sobre una propiedad**, no `@ConditionalOnProperty`: el paquete de esa anotación cambió entre versiones de Boot. Además permite loguear en el arranque qué adaptador quedó activo, dato que importa cuando lo que está en juego es gastar créditos.
- **Dos familias de error en todo adaptador externo:** falla técnica reintentable frente a rechazo definitivo. `VideoProcessingException`/`VideoValidationException`, `ReconstructionException`/`ReconstructionRejectedException`, `SplatConversionException`/`InvalidReconstructionArchiveException`, `TranscriptionException`/`InvalidAudioException`, `ExtractionException`.

### Seguridad

- `organization_id` desnormalizado en toda tabla de negocio, incluso donde sea derivable.
- **Los repositorios no exponen búsquedas sin acotar por organización** (`findByIdAndOrganizationIdIn`, no `findById`). La seguridad vive en la firma del método. `findById` sin acotar queda para procesos de fondo, donde no hay usuario y la pertenencia se verifica contra el trabajo.
- **404, no 403**, ante recursos de otras organizaciones: no se confirma su existencia.
- Mismo error genérico para usuario inexistente y contraseña incorrecta.
- El algoritmo MAC se declara explícitamente en encoder y decoder. (Bug real: encoder HS512 con decoder por defecto HS256.)
- **Todo dato que llega del navegador se revalida en el servidor**, incluso cuando lo generó el propio sistema. Caso concreto: la propuesta de IA que el revisor corrige viaja en la petición de aprobación, y se revalida contra las etapas reales del desarrollo antes de aplicar nada.
- **Las claves de API nunca en `application.yml`**, que se versiona. Van en `.env`, cubierto por `.gitignore`. Una clave commiteada queda en el historial y debe considerarse comprometida aunque se borre.

### Datos y persistencia

- `ddl-auto: validate` y `open-in-view: false` desde el primer arranque.
- **Migraciones Flyway inmutables:** se agregan, nunca se editan. Una restricción `check` no se amplía: se elimina y se recrea.
- Contenido publicado inmutable con versiones (`root_id`, `superseded_at`); sólo se corrige la versión vigente (intento sobre una superada → 409).
- **Transformación espacial en columnas explícitas** (`@Embeddable`), no JSON: se consulta y aplica en cada renderizado.
- **La propuesta de IA sí se guarda como texto JSON**, decisión opuesta y deliberada: es un borrador de longitud variable que se lee entero o no se lee, nunca se filtra ni se ordena por sus campos. Se usa `text` y no `jsonb` porque el mapeo de `jsonb` en Hibernate depende de anotaciones no verificadas en Boot 4.
- **Máquina de estados como comportamiento del enum**, con transiciones válidas declaradas: `JobStatus`, `PipelineStep`, `ReviewStatus`.
- **Cuando dos entidades deben cambiar de estado juntas, la operación es atómica a nivel de entidad**, no una secuencia de llamadas transaccionales encadenadas. Bug real: una excepción en la segunda operación revertía la transacción completa y perdía la primera.

### Cola de trabajos

- **`pipeline_step` complementa a `JobStatus`.** El primero responde "¿en qué punto está?", el segundo "¿está trabajando, terminó o falló?". Un trabajo en `PROCESSING` puede estar preprocesando un video o esperando a un tercero.
- **Cada worker declara qué pasos sabe ejecutar** y el reclamo filtra por ellos. Un trabajo en un paso sin implementar queda quieto en la cola en lugar de ser reclamado cada ciclo para no hacer nada.
- **Completar un paso reinicia `attempts`.** Sin eso, un trabajo que atraviesa varios pasos exitosos agotaría los reintentos sin haber fallado nunca. Los intentos cuentan tomas del paso actual.
- **`step_started_at` es distinto de `updated_at`.** La espera de un tercero se resuelve con muchas consultas y cada una mueve `updated_at`; medir la duración de un paso exige una marca que no se desplace.
- **El corte de la espera externa es por tiempo, no por intentos** (120 minutos por omisión): cada espera reinicia el contador a propósito.
- **Un trabajo de IA que espera revisión pasa a `NEEDS_REVIEW`**, no a `PENDING`. El reclamo filtra por `PENDING`, así que sale de la cola sin ningún filtro adicional. Espera a una persona, no a un worker.
- **Un trabajo por ciclo de polling.** Procesar todos los pendientes en un tick quemaría los reintentos de un fallo técnico sin pausa.
- **Sin bloqueo pesimista:** el planificador de Spring corre con un hilo. Con varios consumidores haría falta `SELECT ... FOR UPDATE SKIP LOCKED`.
- **Tres tiempos en todo worker:** transacción corta para tomar el trabajo, trabajo pesado sin transacción, transacción corta para el desenlace. Los datos cruzan la frontera como record, no como entidades.
- El método `@Scheduled` atrapa toda excepción: el planificador cancela una tarea que propague una excepción, y el worker quedaría muerto hasta el próximo reinicio.

### Pipeline 3D — parámetros verificados

**Preprocesamiento de video (ffmpeg):**
- Se transcodifica siempre, aunque el origen cumpla los límites: la salida es siempre MP4 / H.264 / yuv420p, sin audio y sin metadatos de contenedor (elimina la geolocalización del teléfono).
- **El filtro de escala se aplica siempre**, sin rama condicional. La rama creaba dos fuentes de verdad —`fitsWithin` en Java y el filtro en ffmpeg— que discreparon y produjeron un bug real.
- **Caja de escalado orientada:** 1920×1080 para horizontal, 1080×1920 para vertical, decidido con `gte(iw,ih)`. Sin esto, un 4K vertical se reducía a 608×1080 en lugar de 1080×1920, que es el caso de uso principal del producto.
- **Nunca se amplía:** `min(iw,...)` acota la caja al origen.
- Duración excedida se recorta; duración insuficiente se rechaza (mínimo 20 s).
- **La rotación del iPhone no se parsea a mano:** ffmpeg aplica la matriz al insertar filtros y hornea el resultado en los píxeles.
- **El preprocesamiento no siempre reduce el tamaño.** Medido: un origen de 3,1 MB en perfil Baseline salió en 4,0 MB. Recodificar agrega bits cuando el origen ya está muy comprimido. El propósito es normalizar formato, acotar límites y borrar metadatos; la compresión es un efecto secundario dependiente del origen.

**Conversión PLY → SPZ (gsbox):**
- Comando: `gsbox p2z -i <entrada> -o <salida> -ov 2 -q 5 -rx 180`.
- **Los parámetros son constantes del adaptador, no configuración.** Salieron de verificación experimental A/B contra Spark 2.1.0; exponerlos invitaría a cambiarlos sin repetir esa verificación.
- **`-ov 2`:** es la versión que exporta Scaniverse y la que el visor usa desde la Fase 0. Spark también carga v3, pero v2 pesa menos (6,7 contra 7,1 MB).
- **`-rx 180` es obligatorio.** SPZ almacena en RUB (convención OpenGL/three.js) y PLY usa RDF; la diferencia es una rotación de 180° sobre X. Scaniverse aplica la conversión al exportar, gsbox no. Verificado visualmente: con la corrección, la salida queda **idéntica** a la referencia.
- **`-q 5`:** comparado A/B contra `-q 9` con corte seco y cámara fija, la diferencia es imperceptible y pesa 21% menos. El ahorro importa en la descarga desde el teléfono del comprador.
- **Sin `-sh`:** se preservan los armónicos esféricos de grado 3, que son los reflejos dependientes del ángulo.
- **La corrección de orientación vive en la conversión, no en la transformación almacenada.** Esa transformación existe para alinear capturas entre sí; usarla para compensar una diferencia sistemática del formato mezclaría responsabilidades.
- **Requiere gsbox ≥ 4.7.2**, que corrigió la codificación de rotación de SPZ v3. Los reportes antiguos de degradación en la conversión correspondían a ese bug.
- **Un fallo de gsbox sobre un archivo verificado como legible es rechazo definitivo, no falla técnica.** La clasificación no se basa en el texto del mensaje: gsbox usa al menos dos formulaciones distintas para entradas inválidas (`unsupported ply file` y `EOF`).

**Servicio externo de reconstrucción (KIRI Engine):**
- Base `https://api.kiriengine.app/api`. Endpoints: `POST /v1/open/3dgs/video` (multipart), `GET /v1/open/model/getStatus`, `GET /v1/open/model/getModelZip`, `GET /v1/open/balance`.
- **El éxito se determina por el código HTTP y el campo `ok`, nunca por `code`.** La documentación muestra `code: 0` en sus ejemplos; la API real devuelve `200`. Verificado empíricamente.
- **Las llamadas de lectura no consumen créditos.** Verificado: tras invocar el saldo, el panel siguió en 0 llamadas y 10 créditos.
- **El resultado se descarga como ZIP, no como PLY** (corrección al documento maestro), con enlace válido 60 minutos y **retención de 3 días**. La descarga a almacenamiento propio es urgente, no opcional.
- **Trampa de estados:** en el sobre `code: 0` es éxito, pero en el modelo `status: 0` es procesando, `1` fallido y `2` exitoso. Se modela con enum de nombres explícitos; prohibidas las comparaciones numéricas sueltas.
- **`isMask=0` es requisito del caso de uso**, no preferencia: el enmascarado automático aísla un objeto de su fondo, y en una obra el fondo es el sujeto. `isMesh=0` porque la malla sirve para mediciones, fuera del alcance.
- **HTTP 403 (créditos insuficientes) es fallo terminal**, no reintentable.
- **Cliente HTTP: `java.net.http.HttpClient` del JDK**, no la abstracción de Spring. API estable desde Java 11 e independiente de la autoconfiguración de Boot; `BodyPublishers.ofFile` transmite por streaming.
- La clave de API no se adjunta a la descarga del ZIP: el enlace ya viene firmado y mandarla a un dominio ajeno sería filtrarla.
- **Polling, no webhooks:** los webhooks exigen una URL de callback accesible y el backend corre en localhost durante toda la tesis.
- **Extracción del ZIP con validación contra Zip Slip.** El archivo proviene de un servicio externo. Se elige el PLY más grande (los chicos suelen ser nubes auxiliares) y se escribe con **nombre canónico `model.ply`**, porque gsbox no encuentra archivos con acentos.

### Módulo de IA

**Principio rector, sin excepciones:** la IA extrae, interpreta y redacta; el motor de datos calcula; el humano confirma.

**Transcripción (local, sin costo):**
- Filtro `whisper` de ffmpeg (implementa whisper.cpp), modelo `ggml-small.bin`.
- **`small` y no `base`:** con `base` la transcripción resultó inservible para el español, perdiendo términos de construcción, fechas y el sentido de frases enteras.
- **`queue=30`** en lugar del valor por omisión de 3 segundos: el filtro trocea el audio en ventanas y transcribe cada una aislada; con 3 segundos whisper pierde el contexto de la frase, que es lo que usa para desambiguar.
- **`language=es` fijo, nunca `auto`:** con audio degradado el detector puede confundir el español con lenguas próximas.
- Rendimiento medido: **7,7x tiempo real**. Un audio de tres minutos se transcribe en unos 25 segundos. Timeout 300 s.
- **El texto se lee como UTF-8 explícito**, nunca con la codificación por omisión de la plataforma.
- **Limitación registrada: whisper alucina ante audio sin habla.** No se implementa detección por heurísticas —descartaría transcripciones legítimas breves—; la salvaguarda es de proceso: nada se publica sin confirmación humana.

**Interpretación (Gemini):**
- Proveedor: **Google Gemini vía AI Studio, nivel gratuito**, modelo `gemini-3.6-flash`, temperatura 0.2.
- **`max-output-tokens: 8192`.** Con 2048 la respuesta se truncaba a mitad del JSON: los modelos con razonamiento consumen tokens pensando antes de escribir, y ese consumo sale del mismo presupuesto.
- **No definir `project-id` ni `location`:** cualquiera de las dos conmuta el cliente a modo Vertex AI y rechaza la clave del nivel gratuito con un 400 que aparenta ser un problema de cuota.
- **El prompt se versiona como artefacto**, en `src/main/resources/prompts/progress-extraction-v1.txt`. La versión forma parte del nombre del archivo y queda registrada en cada interpretación.
- **La superficie de Spring AI se aísla en una interfaz funcional de una línea** (`ChatCompletion`). Sus nombres cambian entre versiones mayores; concentrar el contacto en una lambda de la configuración permite corregir un cambio de API en un solo lugar, y hace el adaptador testeable sin Spring AI, sin red y sin cuota.
- **El análisis de la respuesta es tolerante:** quita bloques de código y texto circundante. Un identificador mal formado no aborta la interpretación: se devuelve nulo y el validador decide.
- **La respuesta cruda se guarda aunque el análisis falle.** El diseño anterior la perdía justo cuando más se necesitaba.

**Esquema y validación:**
- **La confianza es un enum (`HIGH`/`MEDIUM`/`LOW`), no un número.** Un decimal sugeriría una precisión inexistente y chocaría con el principio de que ninguna cifra proviene de un LLM. Una confianza ausente se degrada a `LOW`.
- **El modelo no inventa etapas:** recibe la lista completa en el prompt y sólo puede referenciar esos identificadores.
- **`ExtractionValidator` descarta y registra, nunca corrige.** Adivinar a qué etapa se refería un identificador inexistente sería inventar sobre lo inventado.
- **Asimetría deliberada:** una propuesta de estado con etapa inválida se descarta entera; una novedad con etapa inválida se conserva sin etapa, porque el modelo de datos admite avances sin etapa.
- Se conservan `promptVersion`, `modelId` y `rawOutput` por trazabilidad.

**Flujo de revisión:**
- **La transcripción se guarda apenas está disponible**, antes de la interpretación: si el modelo falla después, el trabajo del transcriptor no se pierde y el revisor conserva algo que mirar.
- **Al aprobar, el cliente envía la propuesta corregida y el servidor la revalida.** Lo que se publica es lo que el revisor dejó, no lo que el modelo generó; y como llega del navegador, no es confiable.
- **La propuesta almacenada se reemplaza por la publicada.** Lo que queda registrado es lo aprobado; la versión original del modelo permanece en `raw_output`.
- **Una interpretación resuelta no vuelve atrás.** Corregir un avance publicado es tarea de la línea de tiempo, que versiona su contenido.
- **Decisión de alcance:** las alertas de insumos se muestran al revisor pero **no generan entradas en la línea de tiempo**. El modelo de datos del documento maestro no tiene tabla de alertas, y convertirlas en avances sería inventar semántica.
- Los avances publicados desde audio llevan `ProgressSource.AUDIO`, distinguible del manual en la línea de tiempo.

### Almacenamiento y adaptadores

- SDK de S3 apuntado a MinIO (no el SDK propietario), con `pathStyleAccessEnabled`. El protocolo es el contrato.
- **Subida directa cliente → almacenamiento por URL firmada**; el backend nunca transporta bytes en ese camino. `download`/`upload` existen para el procesamiento del lado del servidor, donde el archivo debe estar en disco.
- `download` es idempotente ante reintentos: escribe a un temporal y mueve con `REPLACE_EXISTING`.
- `exists()` contempla `NoSuchKeyException` y `S3Exception` con status 404: una operación HEAD no trae cuerpo y el SDK no siempre puede leer el código de error.
- **Convención de claves, generada en la entidad y nunca reconstruida por un worker:**
  - `developments/{devId}/media|captures|videos|audios/{id}.{ext}`
  - `.../videos/{id}-normalized.mp4` · `.../captures/{id}-result.zip` · `.../captures/{id}.spz`
- Los tests que dependen de almacenamiento mockean `StorageService`, salvo `StorageTransferIntegrationTest`, que **requiere MinIO corriendo**: mockear el SDK verificaría el mock, no el protocolo.

### Frontend

- Angular standalone + signals + zoneless. Zoneless reduce fricción con el loop de render de Three.js.
- Acceso en memoria, refresh en localStorage. Restauración de sesión con `provideAppInitializer`.
- Refresco automático en el interceptor ante 401, con reintento único y refresco compartido.
- Proxy de desarrollo (`proxy.conf.json`) en vez de CORS.
- El visor separa creación de meshes de aplicación de transformaciones, lo que habilita la alineación en vivo sin volver a descargar los archivos.
- Liberación explícita de recursos WebGL en `ngOnDestroy`, tratada como requisito. Rutas lazy para el visor.
- **La espera de trabajos asincrónicos se resuelve con consulta periódica** desde el
  componente, con tope de intentos y cancelación al destruirse. Los webhooks están
  descartados para toda la tesis.
- **El interceptor adjunta el Bearer sólo a `/api/` y `/actuator/`, y nunca a
  `/api/public/`.** Las subidas por URL firmada van a otro origen: mandarles el token lo
  filtraría y rompería la firma S3. En la vista pública, un token vencido dispararía un
  refresco que al fallar cerraría la sesión del usuario por abrir una página pública.
- **Las fechas usan `input type="date"` nativo** en vez de `MatDatepicker`.
- **Las fechas por omisión se calculan con componentes locales de `Date`**, nunca con
  `toISOString()`, que convierte a UTC y en Argentina devuelve el día anterior.
- **Los mensajes de error se toman de `error.message` del backend**, habilitado por
  `spring.web.error.include-message`.
- **Locale `es-AR` registrado en `app.config.ts`.** Sin eso las fechas salen en inglés y
  los decimales con punto.
- **La vista pública vive en `/p/:token`:** ruta corta porque el enlace se copia y pega
  en mensajes.
- **El token del enlace se muestra una vez, con la advertencia visible.** Es consecuencia
  de que el backend guarde sólo el hash.
- **El panel no usa librería de gráficos.** Barras con `mat-progress-bar` y anchos
  porcentuales por CSS.
- **Cada etapa del panel muestra dos barras alineadas**, avance y consumo. La
  comparación visual es el producto.
- **El semáforo va acompañado de un texto que dice qué significa.** Un color sin lectura
  no informa, y no es accesible para quien no distingue rojo de verde.

### Proceso de trabajo

- **El código se entrega como archivo completo con su ruta**, nunca como instrucciones de edición parcial. (Regla reinstaurada tras un bug causado por una entrega incompleta.)
- **Ante duda sobre una API o versión, verificar antes de escribir.** Confirmado repetidamente: `code: 0` documentado contra `200` real en KIRI, `gemini-2.5-flash` retirado para cuentas nuevas, enlaces de descarga de gsbox no descargables por programa. **La fuente confiable es la herramienta, no su documentación.**
- **Un resultado de Surefire no se acepta por `BUILD SUCCESS`:** se lee la línea `Tests run:` y se compara contra un número esperado. `Tests run: 0` con build exitoso es el síntoma de una *assumption* de clase abortada.
- **Cuando el conteo no cierra, se averigua por qué antes de seguir**, incluso si la sorpresa es a favor.
- Tests de integración en fronteras críticas (persistencia, seguridad, aislamiento), sin objetivo de cobertura total ni tests triviales.
- **Los tests que exigen un comportamiento que la herramienta no tiene se convierten en documentación del comportamiento real**, en vez de forzar heurísticas frágiles.
- **Cuando un test prepara su escenario ejecutando un worker previo, las interacciones del mock compartido se limpian con `clearInvocations`** antes de las verificaciones.
- **Los tests que comparten una cola vacían las tablas en `@BeforeEach`:** la cola es FIFO y un método que deja un trabajo pendiente contamina al siguiente.
- Reiniciar el backend antes de verificar a mano cualquier código nuevo.
- Verificaciones manuales en PowerShell con `Invoke-RestMethod`, no `curl.exe` con JSON escapado.
- **Arrancar el backend con `Tee-Object`** para conservar el log completo: reconstruir un stack trace desde la consola ya costó varias rondas.
- **En tests con MockMvc, el mensaje de una `ResponseStatusException` se verifica con
  `getResponse().getErrorMessage()`**, no parseando el cuerpo: MockMvc no ejecuta el
  reenvío interno a `/error`.
- **Los tests que afirman algo sobre códigos HTTP vistos por un cliente sin sesión usan
  un servidor real** (`WebEnvironment.RANDOM_PORT`), no MockMvc.
- **Boot 4 reubicó las clases del starter de test.** `TestRestTemplate` y
  `LocalServerPort` no están donde estaban en Boot 3. Se usa `java.net.http.HttpClient`
  del JDK y se lee el puerto de `local.server.port` con `@Value`.
- **Los DTOs de entrada no usan tipos primitivos:** Jackson 3 rechaza la construcción de
  un record cuando falta un componente primitivo en el JSON y devuelve 400.
- **Todo DTO que llega del exterior lleva un test de deserialización desde el JSON real
  del cliente.** Construir el record en Java no ejercita el camino del cliente.
- **Todo test que ejercite la cola vacía `processing_jobs` y su tabla de recursos en
  `@BeforeEach`.** La cola es FIFO por tipo de trabajo.
- **Todo test de worker verifica el número de invocaciones del adaptador mockeado.** Sin
  eso, un test contaminado puede pasar por el motivo equivocado.
- **Los tests de integración que afirman algo sobre el estado de la cola desactivan los
  workers** (`obraviva.jobs.enabled=false`), o se vuelven intermitentes.
- **Cuando un test necesita una excepción por "cómo funciona MockMvc", hay que
  preguntarse qué hace el servidor de verdad antes de aceptarla.** Esa excepción,
  aceptada sin investigar, tapó el bug del 401.

### Trampas del entorno Windows / PowerShell

- **Windows PowerShell 5.1 no maneja correctamente caracteres no ASCII en rutas** escritas como literales, ni para procesos externos ni para sus propios cmdlets. Todo archivo destinado a herramientas de línea de comandos usa nombres ASCII; las rutas con acentos se obtienen del sistema de archivos con `Get-ChildItem`, nunca se escriben a mano.
- **Las rutas absolutas en descripciones de filtro de ffmpeg requieren doble barra invertida antes de los dos puntos** (`C\\:/Users/...`), y **cuatro en el literal de Java**. Hay dos niveles de parseo encadenados —el del grafo de filtros y el de las opciones— y cada uno consume un nivel de escapes. Las barras invertidas se convierten previamente en barras normales.
- Las comillas simples dentro de un argumento `-vf` son obligatorias a nivel del parser de ffmpeg, no del shell.
- El separador de clases en `-Dtest` de Surefire es **coma**, no `+`.
- Agregar `-UseBasicParsing` a `Invoke-WebRequest` evita el prompt de seguridad.

### Módulo de reportes (tarea 3.6)

- **El reporte se compone con datos de obra únicamente.** La sección económica se
  incorporó después, en Fase 4; el snapshot es extensible sin rediseño.
- **Cada reporte congela un snapshot inmutable del período al generarse.** La narrativa
  se contrasta contra ese snapshot y no contra el estado actual de la base. Reintentar
  la redacción no altera las cifras.
- **La composición es sincrónica, dentro de la petición.** Son consultas a la base, no
  I/O externo, y resolverlas ahí permite rechazar un período vacío (409) antes de
  encolar nada y antes de gastar cuota.
- **Las fechas del snapshot se serializan como texto ISO-8601**, no como `LocalDate`: el
  soporte de `java.time` en Jackson 3 bajo Boot 4 no está verificado y el snapshot no
  necesita tipos de fecha.
- **El estado de etapa del snapshot se llama `statusAtCompositionTime`** porque el
  modelo no versiona el estado de las etapas: no se puede reconstruir el estado al
  cierre del período. El nombre existe para que el prompt no afirme lo que el sistema no
  sabe.
- **`ReportStatus` es un enum propio del módulo**, con los mismos nombres que
  `ReviewStatus` del módulo `ai`. Duplicación deliberada: son dos ciclos de vida que hoy
  coinciden y compartirlos ataría `reports` a `ai`.
- **El adaptador de narración tiene una sola familia de error** (`NarrationException`,
  técnica y reintentable). No hay "rechazo definitivo" como en video, reconstrucción,
  conversión y transcripción, porque la entrada la compuso el propio motor de datos.
- **Agotados los reintentos de `NARRATE`, el reporte pasa a `FAILED`**, no queda en
  `PENDING`. Corrige un hueco que el módulo de audio todavía tiene.
- **`NarrativeGuard`:** toda secuencia de dígitos del texto redactado debe existir en el
  snapshot serializado, o la redacción se rechaza y se reintenta. Convierte "ninguna
  cifra sale de un LLM" de promesa del prompt en propiedad verificada. La comparación es
  permisiva a propósito. **Limitación conocida:** sólo inspecciona dígitos, así que un
  número escrito en letras ("dos novedades") lo esquiva.
- **El guard vive fuera del adaptador y se aplica en el worker**, igual que
  `ExtractionValidator`. Consecuencia buscada: el adaptador simulado queda sujeto a la
  misma comprobación.
- **El texto que aprueba el revisor NO pasa por el guard.** La salvaguarda vigila al
  modelo, no a la persona: si el revisor escribe una cifra ausente del snapshot es
  porque sabe algo que el sistema no.
- **El trabajo se cierra como `COMPLETED` tanto ante aprobación como ante rechazo.**
  Marcar `FAILED` ante un rechazo confundiría una decisión editorial con un error del
  sistema.
- **`ChatCompletion` se movió a `com.obraviva.platform.ai`** y pasó a ser API pública del
  módulo de IA, expuesta como bean único. La necesitan dos pipelines y un módulo no
  puede depender del paquete interno de otro. Sin `ChatModel` en el contexto, el bean
  existe igual y falla al usarse con un mensaje que indica qué configurar.

### Enlaces públicos y acceso por unidad (Fase 4)

- **El enlace se emite sobre una unidad.** En el prototipo lo emite el desarrollista o
  el comprador; ambos caminos están habilitados.
- **Token de 32 bytes de `SecureRandom`**, base64 URL sin relleno. Se guarda sólo el
  hash SHA-256 y se devuelve una única vez. Sin sal y sin función lenta: con 256 bits de
  entropía no hay diccionario que alcance, y bcrypt sólo agregaría latencia a cada
  visita.
- **Revocado y vencido son estados distintos y separados.** El vencimiento es
  automático; la revocación guarda autor y fecha, que es lo que se pregunta cuando algo
  se filtró.
- **Todo token que no sirve devuelve 404 con el mismo mensaje.** Distinguirlos
  confirmaría a quien prueba tokens que uno existió.
- **La vista pública se acota por la organización del propio enlace:** ninguna consulta
  sin acotar, aunque no haya usuario.
- **La proyección pública se construye campo por campo y no incluye ningún
  identificador interno.** Recortar desde una entidad falla en silencio cuando alguien
  agrega un campo.
- **`/api/public/**` se permite sólo con GET.** Nada sin sesión puede modificar estado.
- **`/error` se permite explícitamente en la cadena de seguridad.** Spring reenvía
  internamente a esa ruta para armar el cuerpo de toda respuesta de error; sin el
  permiso, un 404 anónimo llega al cliente como 401.
- **El contador de visitas corre en transacción propia** y su fallo no afecta la
  respuesta.
- **`unit_members` es el segundo camino de acceso del sistema.** Un comprador no
  pertenece a la organización; el vínculo es lo único que aporta la organización con la
  que después se acotan las consultas. `UnitAccessService` es el único lugar donde esa
  dualidad está escrita.
- **Sin columna de rol en `unit_members`:** estar ahí significa una sola cosa.
- **El vínculo se crea por correo electrónico** y el usuario tiene que existir: no hay
  invitaciones a gente sin cuenta.
- **Administrar miembros exige el camino de la organización.** Un comprador tiene acceso
  a su unidad, no a decidir quién más la tiene.
- **La revocación prueba dos búsquedas acotadas** (por organización y por unidad) en vez
  de un `findById` con verificación posterior: la comprobación vive en la firma.
- **"Mi propiedad" y la vista pública comparten proyección.** Hoy el comprador
  autenticado no ve nada que el enlace no muestre. Se separan cuando existan los
  documentos.
- **La vigencia de las URLs firmadas la decide el contexto:** 5 minutos para el enlace
  público, 1 hora para la sesión autenticada.

### Panel del desarrollista (Fase 4)

- **El avance físico es un porcentaje entero por etapa, declarado por el responsable.**
  No se estima desde imágenes (sección 6 del maestro) ni se deriva del estado.
- **Avance y estado son declaraciones independientes.** Llegar a 100 no completa la
  etapa, y `DELAYED` es ortogonal al porcentaje.
- **Cada etapa tiene un peso entero positivo. Los pesos NO están obligados a sumar 100:**
  el avance global divide por la suma real.
- **El dinero se almacena en `numeric(15,2)` y se mapea a `BigDecimal`.** Nunca punto
  flotante.
- **Los montos son nominales, sin indexar.** Con inflación alta esto subestima el
  desvío: el panel lo muestra nominal y la limitación se documenta, en vez de inventar
  un ajuste.
- **`budget_amount` nulo significa "sin presupuestar", distinto de cero.**
- **Los gastos admiten `stage_id` nulo.** El semáforo por etapa considera sólo los que
  tienen etapa; el total, todos.
- **El semáforo compara consumo de presupuesto contra avance declarado:** verde hasta 10
  puntos de desvío, amarillo hasta 25, rojo después. Sólo se mira el desvío hacia
  arriba.
- **Una etapa sin presupuesto devuelve `NO_BUDGET`, no verde.**
- **El panel muestra gasto como serie temporal y avance como valor actual.** Cada gasto
  lleva fecha, pero `progress_percent` se sobrescribe. Una curva de avance exigiría
  historizar las declaraciones.
- **Al cargar un gasto con etapa se verifica que la etapa pertenezca al desarrollo**, no
  sólo a la organización.

---

