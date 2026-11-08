# ObraViva

*Plataforma de experiencia digital para propiedades en construcción*

**Documento maestro del proyecto**

**Trabajo Final de Graduación — Ingeniería en Software**  
**Línea temática:** Transformación Digital · **Modalidad:** Prototipado Tecnológico  
**Autor:** Alejo Furque · **Ubicación:** Córdoba, Argentina  
**Versión:** 3.0 — Documento consolidado · **Fecha:** [Fecha]

## Índice

1. Resumen ejecutivo
2. Contexto y problema
3. La solución: visión general
4. Los tres pilares del producto
5. Usuarios, roles y modelo de negocio
6. Límites deliberados del producto
7. Validación técnica realizada (prueba de concepto)
8. Arquitectura del sistema
9. El pipeline 3D en detalle
10. El módulo de inteligencia artificial
11. Seguridad y privacidad
12. Modelo de datos
13. Alcance del prototipo (MVP)
14. Plan de trabajo
15. Riesgos y mitigaciones
16. Encuadre académico
17. La demostración de la defensa
18. Evolución posterior a la tesis

## 1. Resumen ejecutivo
ObraViva es una plataforma web que convierte una propiedad en construcción en una
experiencia digital visible, navegable y compartible.

Su capacidad central es única en el mercado regional: a partir de un video filmado con un
teléfono común, la plataforma genera una reconstrucción 3D fotorrealista de la obra que el
comprador puede recorrer libremente desde el navegador — y, dado que cada captura
queda anclada a su fecha, puede viajar en el tiempo entre los distintos estados de la
construcción, desde el pozo hasta el presente.

Alrededor de esa experiencia, la plataforma organiza el ciclo completo de comunicación y
control de una obra: la historia visual semana a semana, las actualizaciones generadas por
inteligencia artificial a partir de los audios del encargado, el panel de avance físico y económico
para el desarrollista, y el modo compartir que convierte cada unidad en su propia herramienta
de venta.

El cliente que pagaría por el servicio es el desarrollista o inversor: la plataforma le vende
unidades con evidencia real, le reduce consultas repetitivas y le da control sobre su obra. El
beneficiario es el comprador: por primera vez puede ver, recorrer y mostrar aquello por lo que
está pagando.

La viabilidad técnica del componente de mayor riesgo —la reconstrucción 3D desde un teléfono
sin hardware especializado y su visualización web independiente— ya fue validada mediante
una prueba de concepto exitosa (sección 7), realizada antes de la redacción de este
documento, conforme al principio de atacar primero el mayor riesgo.

El proyecto en una frase: aplicar reconstrucción 3D por Gaussian Splatting, extracción
estructurada con modelos de lenguaje bajo confirmación humana, y un motor de seguimiento
físico-económico, integrados en una plataforma multi-actor, para transformar la experiencia de
compra y gestión de desarrollos inmobiliarios pequeños y medianos en Latinoamérica.

## 2. Contexto y problema

### 2.1 La compra invisible
Quien compra una vivienda o unidad en construcción realiza una de las inversiones más
importantes de su vida y recibe, a cambio, uno o dos años de espera a ciegas. La
comunicación de la obra se reduce a fotos sueltas por WhatsApp, sin orden, continuidad ni
contexto. El comprador no puede recorrer lo que compró, no puede mostrárselo a su familia, y
si vive en otra ciudad o país —caso frecuente en inversores y en familias que compran a
distancia— directamente no ve nada.

### 2.2 La comunicación artesanal
Del lado del que construye, el proceso es igual de precario. Los desarrollistas pequeños y
medianos responden manualmente las mismas consultas de cada comprador, reenvían las
mismas fotos una por una, y carecen de una forma ordenada de presentar el avance. La
información existe —el teléfono del capataz está lleno de fotos— pero no está organizada,
verificada ni presentada para las personas a las que les importa. El activo comercial más
potente del desarrollista, una obra real avanzando, permanece invisible.

### 2.3 El control que llega tarde
El inversor de un desarrollo chico o mediano no dispone de tablero de control: el avance físico
corre por fotos y el gasto por planillas, y nadie los cruza hasta que el desvío ya ocurrió y duele.
La detección temprana de problemas, que en la construcción vale dinero, hoy depende de la
intuición.

### 2.4 El hueco de mercado
Las soluciones de captura y seguimiento visual de obra existen, pero en el segmento
enterprise: plataformas como Matterport, OpenSpace o Buildots apuntan a grandes
constructoras internacionales, con costos y complejidad inaccesibles para el desarrollo de diez,
veinte o cincuenta unidades típico de la región. La tecnología de reconstrucción 3D desde un
teléfono ya es accesible y madura; nadie la ha empaquetado para este segmento ni la ha
orientado a la experiencia del comprador. Ese es el espacio de ObraViva.

La construcción es, además, uno de los sectores menos digitalizados de la economía regional,
lo que sitúa al proyecto de lleno en la línea temática de Transformación Digital: integrar nuevas
tecnologías para cambiar la forma de funcionar de una empresa, optimizar sus procesos,
mejorar su competitividad y ofrecer un nuevo valor a sus clientes.

## 3. La solución: visión general
ObraViva acompaña la obra en tres momentos, con una promesa por cada uno:

Ver. La obra deja de ser una caja negra: una reconstrucción 3D fotorrealista, generada desde
un video de teléfono, permite recorrerla desde el navegador como si se caminara por adentro.

Seguir. La construcción se convierte en una historia: línea de tiempo con fotos, etapas y
estados, alimentada en minutos por el encargado — incluso mediante un simple audio
interpretado por inteligencia artificial y confirmado por un humano. El desarrollista, en paralelo,
controla avance y gasto en su panel privado.

Mostrar. Cada unidad tiene una presentación cuidada y compartible: el comprador la muestra a
su familia con un enlace seguro; el desarrollista vende las unidades disponibles con su avance
real y verificable como argumento.

La idea central del producto: la transparencia de la obra deja de ser un problema y se
convierte en el producto. El mismo dato que le da tranquilidad al comprador es el que le da
argumentos de venta al desarrollista. Todos los actores de la plataforma quieren lo mismo —
que la obra se vea — y por eso el modelo funciona sin conflictos de interés.

## 4. Los tres pilares del producto

### 4.1 Pilar 1 — El gemelo 3D con viaje en el tiempo (la función estrella)
El flujo completo:

1. Captura. El encargado camina la obra filmando con su teléfono durante uno a tres
       minutos, siguiendo una guía de captura simple (recorrido pausado, cubriendo el espacio
       desde distintas posiciones y ángulos). No se requiere LiDAR, drones ni equipamiento
       especial — esto fue validado empíricamente con un teléfono sin sensor LiDAR.
2. Carga y preprocesamiento. El video se sube a la plataforma desde la web. El backend
       lo valida y adecua automáticamente a los límites del servicio de reconstrucción
       (resolución máxima 1920×1080, duración máxima 3 minutos) mediante transcodificación
       con ffmpeg.
3. Reconstrucción. La plataforma envía el video a un servicio externo de 3D Gaussian
       Splatting mediante API REST (candidato principal: KIRI Engine, con API documentada
       de carga de video, consulta de estado y descarga de resultados). El procesamiento es
       asincrónico y demora entre 7 y 20 minutos; la plataforma lo gestiona mediante una cola
       de trabajos con estados y consulta periódica.
4. Postprocesamiento. El resultado (archivo PLY) se convierte y almacena en formato
       SPZ — formato abierto (MIT) que reduce el tamaño aproximadamente 90% sin pérdida
       visual perceptible (validado: 97 MB → 9,6 MB) — junto con su transformación espacial
       (posición, rotación, escala) para corregir orientación y alinear capturas sucesivas.
5. Experiencia. La escena se visualiza en el visor 3D propio de la plataforma, construido
       sobre Three.js y el renderer open source Spark (MIT, de World Labs; soporta PLY, SPZ y
       otros formatos; compatible con WebGL2 en escritorio y móvil). El comprador la recorre
       libremente con controles de navegación.
6. Viaje en el tiempo. Cada captura queda anclada a fecha, etapa y unidad. Un control
       temporal permite alternar entre los estados históricos de la obra. La cámara mantiene su
       posición al cambiar de estado — el usuario permanece quieto y el mundo cambia a su
        alrededor — y las transformaciones almacenadas por captura permiten la alineación
        aproximada entre reconstrucciones, realizada manualmente desde un panel de
        administración (decisión deliberada: no se implementa registro 3D automático, que
        constituye un problema de investigación abierto innecesario para el producto).

### 4.2 Pilar 2 — La historia de la obra
- Línea de tiempo de construcción: entradas con fecha, etapa, estado (pendiente / en
        ejecución / completado / demorado), fotos, video opcional y descripción breve.
        Scrolleando, el comprador recorre la obra desde el inicio.
- Comparador proyecto/realidad: el render o plano junto a la foto real actual, en la
        misma perspectiva. La función más simple del sistema y la de mayor impacto
        emocional.
- Actualización por audio (núcleo de IA): el encargado envía el audio que ya envía hoy
        por WhatsApp; el sistema lo interpreta y propone una actualización estructurada que el
        responsable revisa, corrige y confirma antes de publicar (sección 10).
- Transparencia también en las malas noticias: el estado "demorado" es un ciudadano
        de primera clase, con su explicación. Un portal que solo publica buenas noticias pierde
        credibilidad; uno que informa las demoras la construye. Es una decisión de producto
        deliberada y defendible.

### 4.3 Pilar 3 — El panel del desarrollista y el modo compartir
- Panel de control (privado del desarrollista): avance físico declarado contra plan y
        gasto acumulado contra presupuesto, presentados juntos, con semáforos por etapa. En
        su versión de prototipo, el panel es deliberadamente simple (dos curvas y semáforos); la
        indexación por series oficiales de costos de la construcción y las proyecciones
        estadísticas quedan declaradas como evolución.
- Reporte mensual autogenerado: el sistema redacta el informe del período para
        inversores a partir de los datos reales (avances, capturas, estados, gasto). El modelo de
        lenguaje narra; las cifras provienen exclusivamente del motor de datos.
- "Mi propiedad": portada personalizada de cada unidad — render, plano,
        características, estado actual, historia, recorrido 3D y documentos autorizados.
- Enlace compartible con privacidad por diseño: el comprador genera un enlace
        público limitado y revocable. La vista pública muestra galería, plano, avance y recorrido
        3D; jamás muestra precios, cuotas, contratos, datos personales ni información de otros
        compradores. El enlace posee token y permisos propios y nunca reutiliza la sesión
        privada.
- Vista de venta: las unidades disponibles se presentan con su avance real navegable
        como argumento comercial. Un comprador satisfecho compartiendo su propiedad en 3D
        es, además, el mejor canal de marketing que un desarrollo puede tener.

## 5. Usuarios, roles y modelo de negocio

| Rol | Qué hace en la plataforma | Qué ve | ¿Paga? |
| --- | --- | --- | --- |
| Desarrollista / inversor | Administra desarrollos, unidades y usuarios; supervisa avance y gasto; publica unidades en venta; recibe reportes | Todo lo de sus desarrollos, incluido el panel económico privado | Sí (modelo previsto: suscripción por desarrollo o por unidad activa) |
| Encargado de obra | Publica avances por formulario o audio; captura los videos para el 3D; adjunta evidencias | Los desarrollos asignados, sin información económica ni de compradores | No |
| Comprador / familia | Sigue su unidad; recorre el 3D; comparte con enlace seguro; consulta documentos autorizados | Exclusivamente su unidad y el contenido aprobado para su rol | No |

El modelo es deliberadamente de un solo lado pagador: quien construye y vende captura el
valor comercial (unidades vendidas, consultas reducidas, control de desvíos, imagen
profesionalizada) y por lo tanto es quien paga. No existe conflicto de intereses: todo lo que la
plataforma muestra al comprador es contenido que al desarrollista le conviene mostrar, y el
control económico es interno y privado.

Los permisos se otorgan por desarrollo y por unidad, no solo por rol global: un mismo usuario
puede ser comprador en un desarrollo y desarrollista en otro, y un encargado solo accede a las
obras que tiene asignadas.

Nota de alcance: para la tesis, el modelo de negocio se documenta como diseño; el prototipo
no incluye pagos ni facturación y se ejecuta íntegramente en entorno de desarrollo.

## 6. Límites deliberados del producto
Definir lo que el sistema no hace protege su credibilidad y acota el alcance:

- No audita presupuestos de terceros ni arbitra entre comprador y constructor. Es
        una plataforma de experiencia y control, no un perito. (El concepto de auditoría de
        presupuestos, explorado en fases previas del proyecto, queda documentado como
        producto futuro independiente, con otro cliente y otro canal.)
- No estima el avance automáticamente desde las imágenes. El avance lo declara el
        responsable con evidencia; la IA asiste en interpretación y redacción, nunca decide la
        cifra.
- No garantiza fechas ni costos. Muestra el estado real y las proyecciones internas del
        inversor.
- No certifica calidad constructiva ni reemplaza a la dirección técnica.
- No expone información privada en enlaces compartidos ni entre compradores, bajo
        ninguna circunstancia.

## 7. Validación técnica realizada (prueba de concepto)
Antes de la redacción final de esta propuesta se ejecutó una prueba de concepto del
componente de mayor riesgo técnico, con resultado aprobado.

### 7.1 Metodología y resultados
- Dispositivo: iPhone 15, sin sensor LiDAR — validando que la propuesta no depende
        de hardware especializado.
- Herramientas evaluadas: KIRI Engine (app: el modo 3DGS requiere suscripción; su
        API de desarrolladores es un producto separado, con 10 créditos gratuitos al
        registrarse y modelo de pago por uso, y permanece como candidato principal para la
        integración automatizada), Polycam (excelente calidad visual; exportación de splat no
        conveniente en la modalidad utilizada) y Scaniverse (modo Splat nativo con
        exportación libre; herramienta utilizada para la validación).
- Distinción técnica identificada: los modos Mesh (geometría tradicional, apto para
        CAD y mediciones) y Splat (reconstrucción visual fotorrealista) producen resultados
        distintos; para la experiencia de ObraViva el modo Splat es el adecuado.
- Exportación: PLY (~97 MB) y SPZ (~9,6 MB), con calidad visual equivalente —
        confirmando SPZ como formato de almacenamiento y distribución.
- Visualización: ambos archivos se cargaron en un visor web independiente de la
        plataforma de captura, validando el flujo completo teléfono → captura → reconstrucción
        → exportación → visor web → navegación 3D.

- Rendimiento: fluido en iPhone 15. En escritorio se observó inicialmente bajo
         rendimiento, que fue diagnosticado y resuelto: la aceleración por hardware del
         navegador estaba desactivada; con aceleración activa, tanto PLY como SPZ navegan
         con fluidez. El episodio queda documentado como aprendizaje de diagnóstico
         (compresión de archivo y carga de GPU son problemas distintos) y el rendimiento en
         escritorio queda descartado como riesgo del proyecto. Permanece pendiente la
         verificación en equipos de gama baja con gráfica integrada, anotada como prueba futura
         y como argumento para ofrecer calidad configurable en el visor.
- Orientación: la escena puede llegar invertida según el sistema de coordenadas de
         origen; se resuelve con la transformación espacial almacenada por captura,
         confirmando que ese componente es un requisito de la arquitectura desde el día uno.

### 7.2 Riesgos descartados por la prueba
Ya no se considera necesario: desarrollar algoritmos propios de Gaussian Splatting; disponer
de LiDAR; construir un motor gráfico desde cero; realizar alineación 3D automática entre
capturas; distribuir archivos PLY sin comprimir; ni disponer de forma inmediata de una obra real
para validar la tecnología (aunque la obtención de capturas de una obra real es prioridad para
la calidad de la demostración — ver sección 15).

### 7.3 Conclusión y siguiente validación
El concepto tecnológico central es viable con herramientas accesibles y sin costo relevante. La
siguiente validación —el visor propio mínimo (Vite + TypeScript + Three.js + Spark) cargando
el archivo SPZ generado, con control de cámara, orientación, navegación y alternancia entre
dos capturas— constituye la primera tarea del plan de trabajo.

## 8. Arquitectura del sistema

### 8.1 Vista general
Aplicación web con arquitectura de monolito modular y procesamiento asincrónico para las tareas pesadas.

```text
┌─────────────────────────────────────────────┐
│ Angular (web responsive)                    │
│ Desarrollista · Encargado · Comprador       │
│ + Visor 3D propio (Three.js + Spark)        │
│ + Vista pública por enlace tokenizado       │
└──────────────────────┬──────────────────────┘
                       │ REST
┌──────────────────────▼──────────────────────┐
│ Spring Boot — Monolito modular              │
│                                             │
│ auth · organizations · developments         │
│ units · stages · progress · media           │
│ captures3d · ai · reports · dashboard       │
│ sharing · jobs                              │
└────┬──────────┬──────────┬──────────┬────────┘
     │          │          │          │
     ▼          ▼          ▼          ▼
 PostgreSQL  Almacen.   API 3DGS   Proveedor LLM
 + Flyway    S3/MinIO   (KIRI,     (Spring AI,
             (media,    abstraída)  salidas
             splats)               estructuradas)
```

Herramientas de pipeline: ffmpeg (transcodificación), conversor PLY→SPZ. Todo en entorno de desarrollo local (Docker Compose); sin despliegue a producción para la tesis.

### 8.2 Stack tecnológico y justificación

| Capa | Tecnología | Justificación |
| --- | --- | --- |
| Frontend | Angular + TypeScript + Angular Material + SCSS | Framework profesional, tipado, con experiencia previa del autor; la tesis no es el lugar para aprender un framework nuevo |
| Visor 3D | Three.js + Spark (MIT, World Labs) | Renderer 3DGS de referencia, activo, integra splats con la escena Three.js estándar; soporta PLY/SPZ; WebGL2 con amplia compatibilidad; su versión 2 incorpora nivel de detalle con streaming |
| Backend | Java LTS + Spring Boot (Web, Data JPA, Security, Validation, Actuator) | Robustez, experiencia previa, ecosistema maduro |
| IA | Spring AI con salidas estructuradas forzadas por esquema; proveedor abstraído | Estructura garantizada, independencia de proveedor |
| Base de datos | PostgreSQL + Flyway | Dominio claramente relacional; migraciones versionadas desde el primer commit |
| Archivos | Almacenamiento compatible S3 (MinIO en local) + URLs firmadas | Videos, fotos y splats fuera de la base; el frontend sube directo sin atravesar el backend |
| Reconstrucción 3D | API externa de 3DGS (candidato: KIRI Engine), abstraída como adaptador | La reconstrucción no es el aporte de la tesis; la abstracción permite cambiar de proveedor sin tocar el núcleo |
| Preprocesamiento | ffmpeg | Adecuación automática de videos a los límites del servicio (1080p, 3 min) |
| Asincronía | Tabla de trabajos con estados + tareas asíncronas de Spring + consulta periódica (polling) | Suficiente para el volumen; sin broker de mensajes; el polling evita el problema de webhooks hacia localhost en desarrollo (el soporte de webhook queda implementado como optimización documentada para producción) |
| Autenticación | Spring Security + JWT (acceso corto + refresco revocable) | Estándar, seguro, controlado |
| Desarrollo local | Docker Compose (app, base, MinIO) | Entorno reproducible completo |
| Pruebas | JUnit, Mockito, Testcontainers, Playwright | Unitarias, integración con base real, extremo a extremo |

### 8.3 Principios de arquitectura
- Fronteras modulares explícitas: los módulos de dominio no dependen de los de
       infraestructura; la infraestructura depende del dominio. Verificación automatizada de
       reglas de dependencia como parte de la suite de pruebas.
- Toda integración externa detrás de una abstracción: el proveedor 3DGS y el
       proveedor de LLM son adaptadores intercambiables; ningún tipo específico de
       proveedor se filtra fuera de su módulo.
- Procesos pesados siempre asincrónicos: carga de video, reconstrucción, conversión
       de formatos, interpretación de audio y generación de reportes corren como trabajos con
       estados (UPLOADED → PENDING → PROCESSING →
       NEEDS_REVIEW/COMPLETED → FAILED), reintentos e idempotencia.
- Estados de revisión humana como ciudadanos de primera clase: nada generado
       por IA se publica sin confirmación.
- Contenido inmutable con versiones: los avances publicados y las capturas no se
       sobreescriben; las correcciones generan versiones.

## 9. El pipeline 3D en detalle
1. Guía de captura. La interfaz instruye al encargado: recorrido lento y estable, cobertura
       del espacio desde varias posiciones y alturas, buena iluminación, inicio desde un punto
       de referencia marcado físicamente en la obra (estaca o marca de pintura), siempre en el
       mismo sentido. Esta disciplina de captura simplifica la alineación posterior de treinta
       minutos de rompecabezas a treinta segundos de ajuste.
2. Carga. Subida directa a almacenamiento mediante URL firmada; registro del documento
       y creación del trabajo de procesamiento.
3. Preprocesamiento. ffmpeg valida y transcodifica: resolución máxima 1920×1080,
       recorte a 3 minutos, formato compatible. Los rechazos se explican al usuario con
       claridad (video demasiado oscuro, demasiado corto, etc.).
4. Reconstrucción externa. Envío a la API 3DGS mediante el adaptador; consulta
       periódica del estado; descarga del resultado al completarse. Control de costos: los
       créditos del servicio solo se consumen con capturas definitivas; el desarrollo diario se
       realiza contra archivos ya procesados y mocks del adaptador.
5. Postprocesamiento. Conversión a SPZ para distribución; almacenamiento del PLY
       original como respaldo; registro de metadatos (fecha, etapa, unidad, tamaño, cantidad
       aproximada de splats).
6. Alineación. Desde el panel de administración se ajusta la transformación (posición,
       rotación, escala) de la nueva captura contra el marco de coordenadas común del
       desarrollo — no contra la captura anterior, para evitar acumulación de errores. La
       transformación queda almacenada.
7. Publicación. La captura aparece en el visor y en la línea de tiempo.

El visor propio implementa: carga de escenas SPZ/PLY, aplicación de la transformación
almacenada, controles de navegación (órbita y desplazamiento; en móvil, gestos táctiles),
selector temporal entre capturas manteniendo la posición de cámara, transición entre estados
(con la posibilidad de fundido cruzado aprovechando que el renderer admite múltiples escenas
splat simultáneas con opacidad ajustable — el momento visual más potente de la
demostración), indicador de carga progresiva y calidad configurable.

## 10. El módulo de inteligencia artificial
Principio rector, aplicado sin excepciones: la IA extrae, interpreta y redacta; el motor de
datos calcula; el humano confirma. Ninguna cifra del sistema proviene de un modelo de
lenguaje, y nada generado se publica sin revisión.

### 10.1 Audio → actualización estructurada
El encargado envía un audio. El pipeline: transcripción → interpretación mediante LLM con
salida forzada por esquema (etapas afectadas, estados propuestos, novedades, alertas de
insumos, nivel de confianza por elemento) → pantalla de revisión donde el responsable ve la
propuesta junto a la transcripción, corrige lo necesario y confirma → publicación. Los prompts
se versionan como artefactos, y cada interpretación almacena la versión de prompt y modelo
utilizada, junto con la salida cruda, para trazabilidad.

Evaluación: se construye un conjunto fijo de audios de prueba (grabados con ruido de obra
realista) con su interpretación esperada anotada, y se miden precisión y exhaustividad de la
extracción. Sin este conjunto, la IA sería una demostración anecdótica; con él, es un
componente medido.

### 10.2 Reporte mensual autogenerado
El motor compone los datos del período (avances, estados, capturas, evolución del gasto); el
LLM los redacta en una narrativa clara para inversores. Cada afirmación del texto es trazable al
dato que la origina. El desarrollista revisa y aprueba antes del envío.

### 10.3 Fuera del alcance de la IA
La clasificación automática de fotos por etapa queda como evolución (relación esfuerzo/valor
desfavorable para el MVP). La estimación de avance por visión computacional queda
explícitamente descartada del producto (sección 6).

## 11. Seguridad y privacidad
- Autorización por recurso: la matriz de permisos evalúa (usuario, desarrollo, unidad,
        acción) de forma centralizada. Los roles globales son insuficientes: un contratista ve
        solo su alcance; un comprador, solo su unidad.
- Aislamiento estructural entre organizaciones: organization_id en todas las
        tablas de negocio desde el primer día, con filtrado a nivel de la capa de persistencia (no
        dependiente de la disciplina de cada consulta) y pruebas de aislamiento que intentan
        explícitamente acceder a datos ajenos y verifican que fallan.
- Enlaces públicos seguros: token propio no adivinable, revocable, con vencimiento
        opcional; la vista pública consume proyecciones de datos preparadas para ese
        contexto — nunca entidades completas filtradas en el frontend. Un error aquí expondría
        precios o datos personales; por eso se prueba específicamente.

- JWT de vida corta con refresco revocable. Registro de auditoría de acciones
       sensibles (quién publicó, quién confirmó una interpretación de IA, quién generó o revocó
       un enlace).
- Datos personales: la plataforma maneja contratos, documentos e información de
       compradores; la documentación incluye el tratamiento conforme a la normativa
       argentina de protección de datos personales, política de retención, y la consideración de
       qué información se envía a proveedores externos de IA (con revisión de sus términos de
       tratamiento).

## 12. Modelo de datos (entidades principales)
- users / organizations / organization_members — identidad y pertenencia
       multi-organización
- developments — desarrollos (obras), con su marco de coordenadas de referencia
- units — unidades de un desarrollo; unit_members — vínculo comprador-unidad
- stages — etapas de obra por desarrollo, con orden y estado
- progress_entries — actualizaciones publicadas (fecha, etapa, estado, descripción,
       origen: manual o audio, versión)
- media_assets — fotos y videos, con metadatos y vínculo a entrada/etapa/unidad
- captures_3d — capturas: fecha, etapa, unidad opcional, archivo SPZ, archivo PLY de
       respaldo, transformación espacial (posición, rotación, escala), estado de procesamiento
- processing_jobs — trabajos asincrónicos con estado, reintentos, claves de
       idempotencia
- ai_extractions — audio origen, transcripción, versión de prompt y modelo, salida cruda,
       propuesta estructurada, confianza, estado de revisión, revisor
- budget_plan / expenses — plan económico simple y gastos declarados (panel del
       desarrollista)
- reports — reportes generados, con período, datos fuente y estado de aprobación
- share_links — enlaces públicos: token, alcance, vencimiento, estado de revocación
- audit_log — registro inmutable de acciones sensibles

## 13. Alcance del prototipo (MVP)

### 13.1 Incluye
1. Autenticación y tres roles con permisos por desarrollo y unidad.
2. Gestión de desarrollos, unidades, etapas y miembros.

3. Carga de video desde la web, preprocesamiento automático, envío al servicio 3DGS
       mediante adaptador, y visor 3D propio con navegación completa.
4. Viaje en el tiempo entre al menos tres capturas, con transformaciones almacenadas
       y cámara estable.
5. Línea de tiempo de obra con fotos, estados (incluido "demorado") y comparador
       proyecto/realidad.
6. Actualización por audio: transcripción, propuesta estructurada, pantalla de revisión y
       confirmación, con conjunto de evaluación y métricas.
7. Panel del desarrollista en versión mínima: curva de avance físico declarado vs. gasto
       acumulado, semáforos por etapa.
8. Reporte mensual autogenerado con aprobación.
9. "Mi propiedad" y enlace público compartible con su modelo de seguridad completo y
       pruebas de aislamiento.
10. Notificaciones por correo de eventos relevantes.

### 13.2 Queda explícitamente fuera
Aplicación móvil nativa (web responsive es suficiente y fue validada en teléfono); cámaras en
vivo e integraciones con NVR; procesamiento de reconstrucción 3D propio (se integra servicio
externo; entrenar modelos es investigación futura); registro de cuotas y pagos; facturación;
firma digital; CRM y vidriera pública con SEO; clasificación automática de fotos; estimación de
avance por visión computacional; indexación del panel económico por series oficiales y
proyecciones estadísticas (declaradas como evolución); alineación 3D automática entre
capturas; despliegue a producción.

### 13.3 Criterio de terminación
El prototipo está completo cuando, en entorno de desarrollo, un tribunal puede: (1) presenciar
la carga de un video y su conversión en recorrido 3D; (2) recorrer en 3D una obra desde el
navegador y viajar entre tres momentos de su construcción; (3) ver un audio convertirse en
actualización estructurada y publicarse tras confirmación humana; (4) consultar el panel del
desarrollista con los datos de esa obra; y (5) abrir el enlace compartido en un teléfono
verificando que no expone ningún dato privado.

## 14. Plan de trabajo

| Fase | Contenido | Duración estimada |
| --- | --- | --- |
| 0 — Visor propio y capturas | Segunda validación: visor mínimo (Vite + TypeScript + Three.js + Spark) con el SPZ ya generado; control de cámara, orientación, alternancia entre dos capturas. En paralelo: inicio inmediato de capturas semanales de una obra real accesible (el tiempo de obra no se recupera). Diseño visual del producto. | 3-4 semanas |
| 1 — Núcleo de plataforma | Autenticación, roles, permisos por unidad; desarrollos, unidades, etapas; avances y línea de tiempo; media con URLs firmadas. | 4-5 semanas |
| 2 — Pipeline 3D integrado | Carga de video, ffmpeg, adaptador del servicio 3DGS, cola de trabajos con polling, conversión SPZ, panel de alineación, visor embebido con viaje temporal. | 5-6 semanas |
| 3 — Módulo de IA | Pipeline de audio con pantalla de confirmación; conjunto de evaluación con métricas; reporte mensual generado. | 4-5 semanas |
| 4 — Panel y compartir | Panel del desarrollista mínimo; "Mi propiedad"; enlaces públicos con su seguridad; pruebas de aislamiento. | 4-5 semanas |
| 5 — Cierre | Carga completa de la obra de demostración; pruebas extremo a extremo; pulido visual; documentación final de la tesis. | 3-4 semanas |

Total estimado: 23 a 29 semanas, con entregables demostrables al cierre de cada fase,
alineados con los entregables parciales del seminario. La Fase 0 completa la validación del
único riesgo técnico relevante antes de comprometer el desarrollo principal.

## 15. Riesgos y mitigaciones

| Riesgo | Impacto | Mitigación |
| --- | --- | --- |
| El servicio 3DGS externo cambia condiciones, precios o disponibilidad | Alto | Adaptador abstraído con al menos dos alternativas evaluadas (API de KIRI; exportación desde apps como Scaniverse como vía manual de respaldo); los archivos ya procesados permiten desarrollar sin dependencia diaria; degradación documentada a fotos/video si una captura falla |
| Costos del servicio de reconstrucción | Bajo | 10 créditos gratuitos del candidato principal cubren las capturas de la tesis; regla operativa: ningún crédito se consume hasta tener la toma definitiva; desarrollo contra mocks y archivos locales |
| No disponer de obra real para la demostración | Medio | Captura de una obra visible desde la vía pública con punto de filmación fijo, iniciada desde la Fase 0; complementada con capturas interiores de un entorno controlado para el flujo completo; en la documentación se declara la naturaleza de cada captura |
| Calidad de interpretación de audios con ruido de obra | Medio | Confirmación humana obligatoria; conjunto de evaluación con métricas honestas; carga manual siempre disponible |
| Rendimiento del visor en equipos de gama baja | Bajo-medio | Calidad configurable; SPZ para carga rápida; nivel de detalle con streaming disponible en el renderer elegido; prueba en hardware modesto antes del cierre |
| Alcance excesivo | Alto | Recortes de la sección 13.2 como contrato; el panel del desarrollista es la pieza recortable a su mínima expresión sin romper la historia del producto |
| Exposición de datos privados por el enlace público | Alto | Proyecciones por contexto, tokens revocables, pruebas de aislamiento específicas en la suite |
| Dependencia del proveedor de LLM | Medio | Capa abstraída mediante el framework de IA; prompts versionados; el núcleo del sistema no depende del modelo |

## 16. Encuadre académico
Línea temática — Transformación Digital. El proyecto integra nuevas tecnologías
(reconstrucción 3D, inteligencia artificial, procesamiento de lenguaje) para transformar la forma
de operar de las empresas de un sector tradicional y poco digitalizado, optimizando sus
procesos de comunicación y control, mejorando su competitividad y ofreciendo un nuevo valor
a sus clientes — en correspondencia directa con la definición del eje presentada en el módulo
introductorio del seminario.

Modalidad — Prototipado tecnológico. El entregable es un prototipo funcional demostrable,
con objetivo y alcance definidos en este documento, desarrollado y presentado en entorno de
desarrollo, conforme a los requisitos del seminario (que no exigen implementación completa ni
empresa destinataria; el proyecto puede constituir un emprendimiento personal).

Aporte de ingeniería. La integración original de tres dominios técnicos — reconstrucción 3D
neural aplicada mediante servicios especializados, extracción estructurada con modelos de
lenguaje bajo confirmación humana, y una plataforma multi-actor con seguridad por recurso —
en un producto coherente, con arquitectura documentada, procesamiento asincrónico,
integraciones abstraídas, y calidad medida mediante conjuntos de evaluación y pruebas de
aislamiento. El proyecto demuestra, además, la práctica profesional de validar los riesgos
técnicos mayores mediante pruebas de concepto antes de comprometer el desarrollo.

Diferenciación. No existe en el mercado regional una plataforma que combine la experiencia
del comprador, el gemelo 3D accesible desde un teléfono común y el control del desarrollo para
el segmento pequeño y mediano. Las soluciones comparables pertenecen al segmento
enterprise internacional.

## 17. La demostración de la defensa
Guion de cinco minutos, sin relleno:

Se abre con la pregunta que cualquiera entiende: "¿Comprarías algo de cien mil dólares que no
vas a poder ver durante dos años?" Se proyecta ObraViva y se entra a "Mi propiedad": el
render, el plano, y el botón Recorrer la obra hoy. Se navega la reconstrucción 3D real en vivo,
caminando por los ambientes en construcción frente al tribunal. Se toma el control temporal y la
obra se desconstruye — muros, estructura, pozo — y regresa al presente. Se reproduce un
audio real del encargado y se muestra su conversión en la actualización estructurada de la
semana, publicada con una confirmación. Se abre el panel del desarrollista: el avance contra el
gasto, los semáforos, el reporte mensual ya redactado. Cierre: se toma un teléfono, se abre el
enlace compartido, y la propiedad en construcción queda en la palma de la mano — lista para
mostrarse a una familia, o para venderle la unidad vecina al próximo comprador.

Ver, seguir, mostrar: la obra completa, visible de punta a punta. La obra, viva.

## 18. Evolución posterior a la tesis
El prototipo constituye directamente el producto comercial para el segmento identificado, con
estas extensiones naturales ya diseñadas:

- Comercialización: suscripción por desarrollo o unidad activa; despliegue a producción
        con CDN para las escenas 3D; el contacto desarrollista que originó una versión previa
        del proyecto es el primer cliente candidato cuando su desarrollo se reactive.
- Panel económico completo: indexación por series oficiales de costos de la
        construcción (fuentes ya investigadas y verificadas: ICC-Córdoba, INDEC),
        proyecciones y alertas avanzadas.
- Recorrido del futuro: modelo 3D navegable del proyecto terminado a partir de planos,
        complementando el 3D del presente.
- Automatizaciones: timelapses, clasificación de fotos por etapa, webhooks en
        producción.
- Producto hermano: el auditor de presupuestos explorado en fases previas del proyecto
        (ObraCheck), como producto independiente para familias y entidades financieras — dos
        productos, dos clientes, sin conflicto de intereses.

Fin del documento. La documentación complementaria del proyecto incluye: informe completo
de la prueba de concepto 3D, documento de arquitectura y stack de la fase previa, e
investigación de fuentes de datos públicas del sector construcción.
