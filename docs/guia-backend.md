# Guía de estudio del backend de ObraViva

Esta guía es para aprender dos cosas a la vez: los conceptos de backend que usa el
proyecto y cómo están aplicados en `obraviva-platform`.

Cada capítulo tiene cinco partes:

1. **El concepto**: qué es y por qué existe, sin hablar de ObraViva.
2. **Cómo está en ObraViva**: con las clases reales.
3. **Dónde mirar**: qué abrir y en qué orden, con el test que muestra el comportamiento.
4. **Ejercicio**: algo para hacer con el código.
5. **Preguntas de defensa**: sin respuesta, para practicar.

Las rutas son relativas a `obraviva-platform/src/main/java/com/obraviva/platform/`, salvo
que digan otra cosa. Los tests están en `obraviva-platform/src/test/java/com/obraviva/platform/`.

Al final hay un glosario con todos los términos técnicos.

---

## Capítulo 1. Fundamentos

### El concepto

**Qué hace un backend.** Es el programa que corre en un servidor y atiende pedidos. El
navegador (el *frontend*) muestra pantallas, pero no guarda nada importante ni decide
quién puede ver qué. Eso lo hace el backend: recibe un pedido, verifica que quien pide
tenga permiso, lee o escribe en la base de datos y devuelve una respuesta.

Una analogía: el frontend es el mozo y el backend es la cocina. El mozo toma el pedido y
lo lleva; la cocina decide si se puede hacer, lo prepara y controla que no salga nada que
no corresponde.

**HTTP.** Es el protocolo con el que se hablan el navegador y el servidor. Cada pedido
tiene:

- un **método**, que dice la intención: `GET` (leer), `POST` (crear o hacer una acción),
  `PUT` (reemplazar), `PATCH` (cambiar una parte), `DELETE` (borrar);
- una **ruta**, que dice sobre qué: `/api/units/123/stages`;
- **encabezados**, datos extra como el token de sesión;
- a veces un **cuerpo**, normalmente en JSON.

La respuesta trae un **código de estado** y, casi siempre, un cuerpo. Los códigos que
vas a ver en este proyecto:

| Código | Significa |
|---|---|
| 200 | Salió bien |
| 201 | Se creó algo |
| 400 | El pedido está mal armado o no cumple una regla |
| 401 | No sé quién sos (falta el token o venció) |
| 403 | Sé quién sos, pero no podés hacer esto |
| 404 | No existe, o no existe para vos |
| 409 | Conflicto con el estado actual (por ejemplo, ya hay uno igual) |
| 415 | Tipo de archivo no aceptado |

**REST.** Es un estilo para diseñar una API sobre HTTP: las rutas nombran **recursos**
(sustantivos, como `/units/{id}`) y el método dice qué se hace con ellos. Así, `GET
/api/units/{id}/stages` se lee "las etapas de esa casa", sin verbo en la ruta. Cuando una
acción no encaja en crear, leer, cambiar o borrar, se usa un `POST` a una sub-ruta con
nombre de acción, como `POST /api/audio-extractions/{id}/approve`.

**Las capas.** Un backend bien armado separa responsabilidades en capas. Cada una habla
sólo con la de abajo:

```
Controlador  →  recibe el pedido HTTP y lo traduce a una llamada Java
Servicio     →  las reglas del negocio y los permisos
Repositorio  →  lee y escribe en la base
Entidad      →  la clase que representa una fila de una tabla
```

Separar sirve para entender y cambiar una cosa sin romper otra. Si mañana la API cambia de
forma, cambia el controlador; la regla "una etapa no vuelve a pendiente" sigue en el
servicio.

**Clase y tabla, objeto y fila.** Una base relacional guarda datos en **tablas**: cada
**fila** es un registro y cada **columna** un dato. Java trabaja con **clases** y
**objetos**. Una **entidad** es una clase que corresponde a una tabla, y cada objeto de esa
clase corresponde a una fila. La clase `Stage` es la tabla `stages`; un objeto `Stage` en
memoria es una fila de esa tabla.

**DTO.** *Data Transfer Object*: un objeto que sólo sirve para llevar datos de un lado a
otro, sin lógica. En una API hay dos tipos:

- **de entrada**: lo que manda el cliente (por ejemplo, el cuerpo de "crear una etapa");
- **de salida**: lo que devuelve el servidor.

¿Por qué no devolver directamente la entidad? Porque la entidad tiene todo: columnas
internas, relaciones, datos que algunos usuarios no deben ver. Con un DTO de salida, cada
campo que sale es una decisión explícita. En Java moderno se escriben como **records**,
clases inmutables de una línea.

**Spring Boot.** Es un framework (un esqueleto de aplicación) que resuelve lo que todo
backend necesita: levantar un servidor web, conectarse a la base, leer configuración,
manejar seguridad. Con **autoconfiguración**: si agregás la dependencia de JPA y le das la
URL de la base, Spring arma la conexión solo.

**Inyección de dependencias.** Una clase no crea los objetos que necesita: los recibe. En
vez de escribir `new StageRepository()` adentro del servicio, el servicio declara en su
constructor que necesita un repositorio, y Spring se lo pasa al crearlo.

```java
@Service
public class UnitStageService {
    private final StageRepository stageRepository;

    public UnitStageService(StageRepository stageRepository) {
        this.stageRepository = stageRepository;
    }
}
```

A los objetos que Spring crea y reparte se los llama **beans**. Se marcan con anotaciones
como `@Service`, `@Component`, `@RestController`, o se crean en un método `@Bean` de una
clase `@Configuration`. La ventaja: en un test podés pasarle al servicio un repositorio
falso, y la clase no se entera.

### Cómo está en ObraViva

- Cada módulo tiene sus controladores (`*Controller`), servicios (`*Service`),
  repositorios (`*Repository`), entidades y DTOs.
- Los controladores están anotados con `@RestController` y `@RequestMapping("/api")`. No
  deciden nada: leen el usuario del token y llaman al servicio.
- Los DTOs son records. Los de entrada se validan con anotaciones (`@NotNull`, `@Size`) y
  `@Valid`. Los de salida se llaman `*Response`, y nunca se devuelve una entidad.
- Toda la inyección es **por constructor**. Con un solo constructor, Spring lo usa sin que
  haga falta anotarlo. El único `@Autowired` de producción está en
  `reconstruction/ReconstructionCredits`, que tiene dos constructores: la anotación le dice
  a Spring cuál usar, y el otro, que recibe un `Clock`, queda para que los tests controlen
  la hora.
- Un ejemplo de `@Bean`: `reconstruction/ReconstructionConfig.reconstructionService` crea
  el adaptador de reconstrucción real o el simulado según una propiedad (capítulo 3).

### Dónde mirar

1. `developments/UnitStageController`: un controlador chico, con un `GET` y dos `POST`.
2. `developments/UnitStageService`: el servicio que llama.
3. `developments/StageRepository`: la interfaz del repositorio. No tiene implementación:
   Spring Data la genera a partir del nombre de cada método.
4. `developments/Stage`: la entidad.
5. `developments/StageResponse` y `developments/CreateStageRequest`: los DTOs de salida y
   de entrada.
6. Test: `developments/StageIntegrationTest`.

### Ejercicio

Abrí `UnitStageController.create` y seguí la llamada hasta el `saveAndFlush` del
repositorio. Anotá:

- qué valida la anotación `@Valid`;
- qué valida el servicio que no podría validar una anotación;
- qué código HTTP sale si dos etapas tienen el mismo orden, y en qué línea se decide.

### Preguntas de defensa

- ¿Por qué el controlador no tiene lógica de negocio?
- ¿Qué ganás devolviendo un record `StageResponse` en lugar de la entidad `Stage`?
- ¿Qué es un bean y quién lo crea?
- ¿Por qué inyección por constructor y no `@Autowired` en un campo?

---

## Capítulo 2. Base de datos

### El concepto

**JPA y Hibernate.** Escribir SQL a mano para cada lectura y escritura es repetitivo y
propenso a errores. Un **ORM** (*Object-Relational Mapper*) traduce entre objetos y filas.
**JPA** es la especificación estándar de Java para eso (las anotaciones `@Entity`, `@Id`,
`@Column`); **Hibernate** es la implementación que la ejecuta. **Spring Data JPA** agrega
los repositorios: escribís la firma de un método como `findByUnitIdOrderByOrderIndexAsc` y
Spring arma la consulta.

**Relaciones.** Una fila puede apuntar a otra con una **clave foránea** (*foreign key*,
FK): una columna que guarda el id de la otra fila, y que la base verifica. JPA puede
representarlo de dos maneras:

- como **objeto**: `@ManyToOne private Development development;`. Navegás con
  `stage.getDevelopment().getName()`, y Hibernate trae el desarrollo cuando hace falta;
- como **id suelto**: `private UUID unitId;`. Tenés sólo el identificador, y para saber
  más hay que preguntarle a quien corresponda.

**UUID.** Un identificador de 128 bits generado al azar, como
`0b6e3c1a-...`. A diferencia de un número autoincremental, se puede generar en la
aplicación antes de guardar y no revela cuántos registros hay.

**Transacciones.** Una **transacción** agrupa varias operaciones para que pasen todas o
ninguna. El ejemplo clásico es una transferencia: restar de una cuenta y sumar a otra. Si
se corta la luz en el medio, la base deshace lo hecho (*rollback*). Si todo sale bien, lo
confirma (*commit*).

En Spring se marca con `@Transactional` sobre un método: al entrar se abre la transacción,
y al salir se confirma o, si el método lanzó una excepción no chequeada, se revierte.
Detalles que importan:

- `@Transactional(readOnly = true)` avisa que sólo se lee, y permite optimizaciones.
- Funciona con un **proxy**: Spring envuelve el bean en otro objeto que abre y cierra la
  transacción. Por eso, si un método de una clase llama a otro método `@Transactional` de
  **la misma clase**, la anotación no se aplica: la llamada no pasa por el proxy.
- `noRollbackFor` dice qué excepciones no revierten. `propagation = REQUIRES_NEW` abre una
  transacción aparte, que se confirma aunque la de afuera falle.

**Migraciones.** El esquema de la base (tablas, columnas, índices) cambia con el proyecto.
Una **migración** es un archivo SQL numerado que describe un cambio. **Flyway** lleva la
cuenta de cuáles se aplicaron en cada base y aplica las que faltan, en orden, al arrancar.
Así, cualquier base (la tuya, la de un test, la de producción) llega al mismo esquema.

La regla de oro: **una migración aplicada no se edita**. Si se cambiara, las bases que ya
la corrieron quedarían distintas de las nuevas. Un cambio es una migración nueva.

### Cómo está en ObraViva

**Relaciones: objeto adentro del módulo, UUID entre módulos.**

- Adentro de `developments`, `Stage` y `Unit` tienen `@ManyToOne(fetch = FetchType.LAZY)`
  hacia `Development`. *Lazy* quiere decir que el desarrollo se carga recién cuando alguien
  lo pide.
- Entre módulos, la referencia es un UUID suelto. `progress/ProgressEntry` tiene
  `private UUID stageId`, no un `Stage`: `progress` no puede tocar entidades de
  `developments`. Si necesita algo de la etapa, le pregunta a `StageCatalogService`.
- En la base, igual hay clave foránea: `V6__create_progress_entries.sql` declara
  `stage_id uuid references stages (id)`. Java no navega la relación, pero la base no deja
  guardar una etapa inexistente.

**Los UUID los genera la aplicación.** Mirá el constructor de `ProgressEntry`:
`this.id = UUID.randomUUID()`. Hace falta saber el id antes de guardar: es la clave del
archivo en el almacenamiento y el `resource_id` de un trabajo de la cola.

**`organization_id` en toda tabla de negocio.** Cada entidad tiene su `organizationId`
(capítulo 5).

**Transacciones en acción:**

- `publication/ProgressPublicationService.publishAll` es `@Transactional`: avance, etapa y
  fotos en una sola transacción (capítulo 6).
- `UnitStageService.list` es `@Transactional(readOnly = true)`.
- `captures3d/Capture3dService.confirmUpload` es `@Transactional(noRollbackFor =
  ResponseStatusException.class)`: cuando un SPZ es inválido, borra la captura y lanza un
  400, y el borrado tiene que quedar.
- `sharing/PublicViewService.registerAccess` es `REQUIRES_NEW`: contar una visita no puede
  impedir ver la casa.
- `media/variants/MediaVariantQueue` existe **por el proxy**: sus métodos transaccionales
  están en otro bean para que la anotación se aplique de verdad.

**Configuración** (`src/main/resources/application.yml`):

- `spring.jpa.hibernate.ddl-auto: validate`: Hibernate **no crea** tablas. Al arrancar,
  compara las entidades con el esquema que dejó Flyway y, si no coinciden, la aplicación
  no arranca.
- `spring.jpa.open-in-view: false`: la sesión de la base se cierra al terminar el
  servicio. Una relación *lazy* que se lea después, en el controlador, falla. Obliga a que
  toda lectura pase dentro del servicio.

**Flyway:** 37 migraciones, de `V1__create_organizations.sql` a
`V37__add_initial_load_progress_source.sql`, en `src/main/resources/db/migration`.

### Dónde mirar

1. `src/main/resources/db/migration/V5__create_stages.sql` y
   `V6__create_progress_entries.sql`: las tablas.
2. `developments/Stage`: cómo se mapea esa tabla (`@Entity`, `@Table`, `@Column`,
   `@ManyToOne`, `@Enumerated`).
3. `progress/ProgressEntry`: una entidad que referencia por UUID.
4. `developments/StageRepository`: consultas derivadas del nombre.
5. `media/variants/MediaVariantQueue`: el comentario de la clase explica el proxy.
6. Test: `publication/ProgressPublicationIntegrationTest`, el caso "foto de otra casa: 400
   y no cambia ni el avance, ni la etapa, ni la foto". Es un *rollback* visible.

### Ejercicio

1. En `V6__create_progress_entries.sql`, encontrá qué columnas son claves foráneas. Después
   buscá en `ProgressEntry` cómo está cada una en Java: ¿cuáles son objetos y cuáles UUID?
2. Leé el test "foto de otra casa" de `ProgressPublicationIntegrationTest`. Antes de
   mirar las aserciones, predecí qué queda en la base después del 400. Después confirmalo
   leyendo `ProgressPublicationService.publishOne`: ¿en qué paso falla y qué ya se había
   hecho?

### Preguntas de defensa

- ¿Por qué `ProgressEntry` guarda `stageId` como UUID y no como `@ManyToOne Stage`?
- ¿Qué pasa si un método `@Transactional` llama a otro `@Transactional` de la misma clase?
- ¿Por qué `ddl-auto: validate` y no `update`?
- ¿Qué harías si tuvieras que corregir un error en `V20`, que ya está aplicada?

---

## Capítulo 3. La arquitectura de ObraViva

### El concepto

**Monolito, microservicios y monolito modular.**

- Un **monolito** es una sola aplicación con todo adentro: se despliega de una vez y sus
  partes se llaman como métodos comunes.
- Los **microservicios** parten el sistema en aplicaciones separadas que se hablan por
  red. Ganan independencia, pero suman la complejidad de una red: fallas parciales,
  versiones, despliegues coordinados.
- Un **monolito modular** es un monolito con fronteras internas estrictas: cada módulo
  tiene una API pública y oculta el resto. Tiene el orden de los microservicios sin el
  costo de la red.

**Puertos y adaptadores** (también llamada *arquitectura hexagonal*). La lógica del negocio
no debería depender de los detalles de un proveedor externo. Para eso:

- el **puerto** es una interfaz que dice qué se necesita ("reconstruí este video");
- el **adaptador** es la clase que lo implementa con un proveedor concreto (KIRI), u otra
  que lo simula.

Una analogía: el puerto es el enchufe de la pared y el adaptador es el cargador de cada
aparato. La casa no cambia si cambiás de aparato.

### Cómo está en ObraViva

**Un monolito modular.** Una aplicación Spring Boot con una base PostgreSQL y un
almacenamiento S3 (MinIO en desarrollo). Cada paquete de primer nivel es un módulo, y
tres reglas sostienen las fronteras:

1. **Un módulo usa a otro sólo por sus servicios públicos** (`DevelopmentAccessService`,
   `StageCatalogService`, `MediaCatalogService`…) y referencias por UUID. Nunca toca sus
   entidades ni sus repositorios. Hay una excepción deliberada, `activity` (capítulo 12).
2. **Toda integración externa está detrás de un puerto**, con un adaptador simulado cuando
   cuesta plata o necesita red.
3. **`organization_id` en toda tabla de negocio**, y ningún repositorio busca sin acotar
   (capítulo 5).

**El mapa de módulos:**

| Paquete | Qué es en la pantalla |
|---|---|
| `developments` | La obra: desarrollos, casas, etapas, gastos, el panel económico, el equipo, los compradores y los lugares de cada casa |
| `progress` | Los avances publicados, con sus correcciones como versiones |
| `publication` | "Publicar avance": texto, fotos y etapa juntos |
| `media` | Fotos y videos; `media/variants`, sus versiones livianas |
| `captures3d` | Los recorridos 3D y el "viaje en el tiempo" |
| `ai` | El audio del encargado: transcripción, interpretación y revisión |
| `reports` | Reportes de período, redactados por el modelo y revisados por una persona |
| `activity` | **Novedades**, el feed del dueño |
| `overview` | **Resumen** del desarrollo y de la casa, y **"Hoy"** del encargado |
| `sharing` | Lo del comprador: **"Mi propiedad"** y el **enlace público** |
| `plans` | Planos con imagen y ambientes |
| `comparisons` | **Proyecto y realidad**: render contra foto |
| `documents` | Documentos autorizados de la casa |
| `auth` | Cuentas, login y tokens |
| `organizations` | Empresas y su gente |
| `jobs` | La cola de trabajos |
| `storage`, `video`, `conversion`, `reconstruction` | Integraciones: MinIO, ffmpeg, gsbox, KIRI |
| `common/process` | Ejecutar herramientas de línea de comandos |
| `installation` | El responsable inicial y los barrios de demostración |

Algunos nombres de clase que no son obvios: `Unit` es **la casa**; `CaptureSector` es **el
lugar** de la casa ("Frente", "Living y cocina"); `AiExtraction` es **un audio con su
interpretación**; lo que tiene `unitId` nulo es de **las obras comunes**.

**Un puerto con sus adaptadores: la reconstrucción 3D.**

- El puerto es `reconstruction/ReconstructionService`, una interfaz con cuatro métodos:
  `submitVideo`, `checkStatus`, `downloadResult` y `availableCredits`.
- Hay dos adaptadores: `reconstruction/kiri/KiriReconstructionService` (el servicio real,
  que gasta créditos) y `reconstruction/mock/MockReconstructionService` (simulado, gratis).
- `reconstruction/ReconstructionConfig` elige uno según `obraviva.reconstruction.provider`
  (`mock` por omisión). Lo hace con un `if` explícito y **loguea cuál quedó activo**, porque
  importa saber si se está gastando plata.
- El dominio (`captures3d/ReconstructionWorker`) sólo conoce la interfaz. Cambiar de
  proveedor es escribir otro adaptador.

El mismo patrón se repite en `storage/StorageService` (MinIO), `video/VideoProcessor`
(ffmpeg), `conversion/SplatConverter` (gsbox), `ai/transcription/AudioTranscriber`
(whisper) y `ai/extraction/ProgressExtractor` (Gemini).

### Dónde mirar

1. `reconstruction/ReconstructionService`: el puerto. Leé los comentarios de cada método.
2. `reconstruction/ReconstructionConfig`: la elección del adaptador.
3. `reconstruction/mock/MockReconstructionService`: el simulado, más fácil de leer.
4. `reconstruction/kiri/KiriReconstructionService`: el real.
5. `captures3d/ReconstructionWorker`: quien lo usa sin saber cuál es.
6. Test: `captures3d/ReconstructionWorkerTest`, que reemplaza el puerto por un mock de
   Mockito.

### Ejercicio

1. Elegí un módulo (`comparisons` es chico) y listá todos los `import
   com.obraviva.platform.…` de sus clases. ¿Importa alguna entidad o repositorio de otro
   módulo, o sólo servicios?
2. En `ReconstructionConfig`, ¿qué pasa si `provider` vale `kiri` y no hay clave de API?
   ¿Y si vale algo que no es `mock` ni `kiri`?

### Preguntas de defensa

- ¿Por qué un monolito modular y no microservicios?
- ¿Qué problema resuelve un puerto con adaptador simulado en este proyecto en particular?
- ¿Cómo sabés que un módulo no toca entidades de otro? ¿Está verificado automáticamente?
- Si mañana KIRI cierra, ¿qué archivos cambiás?

---

## Capítulo 4. Un pedido de punta a punta

### El concepto

Antes de que tu código vea un pedido, el servidor hace varias cosas:

1. Un **servidor web** embebido (Tomcat, en Spring Boot) recibe los bytes por la red.
2. Una cadena de **filtros** procesa el pedido. Uno de ellos es el de seguridad, que
   puede cortarlo ahí mismo.
3. El **dispatcher** de Spring MVC busca qué método de qué controlador corresponde a ese
   método y esa ruta (`@GetMapping`, `@PathVariable`).
4. Convierte el cuerpo JSON en un objeto Java (**deserializar**), corre las validaciones y
   llama al método.
5. Al volver, convierte el objeto devuelto en JSON (**serializar**) y escribe la respuesta.

La librería que convierte entre JSON y objetos es **Jackson** (en este proyecto, Jackson
3, en el paquete `tools.jackson`).

### Cómo está en ObraViva

El ejemplo es `GET /api/units/{unitId}/stages`, la lista de etapas de una casa:

```
GET /api/units/0b6e…/stages
Authorization: Bearer eyJhbGciOiJIUzUxMiJ9…
```

**1. Seguridad** (`auth/SecurityConfig`). El filtro de Spring Security ve que la ruta no es
pública, lee el encabezado `Authorization`, verifica la firma y el vencimiento del token
(capítulo 5). Sin token válido, corta con **401**. Con token válido, deja el `Jwt`
disponible para el controlador.

**2. Controlador** (`developments/UnitStageController.list`):

```java
@GetMapping
public List<StageResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID unitId) {
    return stageService.list(userId(jwt), unitId);
}
```

`@AuthenticationPrincipal` le pasa el token ya verificado. `userId(jwt)` es
`auth/CurrentUser.userId`, que convierte el `sub` del token en el UUID de la cuenta: es el
único lugar del sistema que lee quién pide.

**3. Servicio** (`developments/UnitStageService.list`, en una transacción de sólo lectura):

```java
Unit unit = requireAccessibleUnit(userId, unitId);
boolean owner = membershipService.isOwnerOf(userId, unit.getOrganizationId());
return stageRepository
        .findByUnitIdAndOrganizationIdInOrderByOrderIndexAsc(unitId, List.of(unit.getOrganizationId()))
        .stream()
        .map(stage -> StageResponse.forViewer(stage, owner))
        .toList();
```

`requireAccessibleUnit` es el permiso, en tres filtros:

1. la casa tiene que ser de una de sus organizaciones
   (`unitRepository.findByIdAndOrganizationIdIn`);
2. tiene que llegar a esa obra (`developmentAccess.canAccess`);
3. si es encargado de casas puntuales, tiene que ser una de las suyas
   (`developmentAccess.canWorkOnUnit`).

Si cualquiera falla, lanza `ResponseStatusException(HttpStatus.NOT_FOUND, "Casa no
encontrada")`.

**4. Repositorio** (`developments/StageRepository`). La consulta sale del nombre del
método. También va acotada por organización.

**5. Respuesta.** Cada `Stage` se convierte en un `StageResponse`. Para el encargado,
`forViewer` deja `budgetAmount` en `null`: se omite **el valor, no el campo**, así la forma
del JSON no depende de quién mira.

```json
[{"id":"…","unitId":"0b6e…","name":"Estructura","orderIndex":3,"status":"IN_PROGRESS",
  "progressPercent":40,"weight":15,"budgetAmount":null,"plannedStart":"2026-08-01", …}]
```

**Los errores.** Se lanzan como `ResponseStatusException(estado, mensaje)`; no hay un
manejador global (`@ControllerAdvice`). El mensaje llega al cliente porque
`spring.web.error.include-message: always` en `application.yml`; la traza nunca.

### Dónde mirar

1. `auth/SecurityConfig`: qué rutas son públicas.
2. `developments/UnitStageController`.
3. `auth/CurrentUser`.
4. `developments/UnitStageService`, los métodos `list` y `requireAccessibleUnit`.
5. `developments/StageRepository`.
6. `developments/StageResponse.forViewer`.
7. Test: `developments/MemberRoleRestrictionsIntegrationTest`, el caso "el encargado ve
   las etapas sin presupuesto, y el dueño con presupuesto".

### Ejercicio

Hacé el mismo recorrido para `GET /api/developments/{developmentId}/progress` (la línea de
tiempo de avances). Empezá en `progress/ProgressController` y anotá, paso por paso: qué
filtro de permisos se aplica, qué consulta se hace y qué DTO sale. Después compará: ¿qué
devuelve para un encargado de casas puntuales, y dónde se decide?

### Preguntas de defensa

- ¿Qué pasa si llega un pedido sin token a una ruta protegida? ¿Llega al controlador?
- ¿Dónde se decide que el encargado no ve el presupuesto?
- ¿Por qué el campo `budgetAmount` viene en `null` en lugar de no venir?
- ¿Por qué no hay `@ControllerAdvice`?

---

## Capítulo 5. Seguridad

### El concepto

**Autenticación y autorización** son dos preguntas distintas:

- **Autenticación**: ¿quién sos? Se resuelve con algo que sólo vos tenés, como una
  contraseña. Si falla, el código es **401**.
- **Autorización**: ¿podés hacer esto? Se resuelve con reglas: tu rol, tus asignaciones.
  Si falla, el código es **403** (o **404**, ver más abajo).

**Sesión con token.** Después del login, el servidor podría guardar una sesión en memoria,
pero eso no escala y complica. La alternativa es un **token**: el servidor le da al cliente
un dato firmado, y el cliente lo manda en cada pedido. Se dice que la API es **stateless**
(sin estado): el servidor no recuerda nada entre pedidos.

**JWT** (*JSON Web Token*). Es un token con tres partes en base64: encabezado, datos
(**claims**, como el id del usuario y el vencimiento) y una **firma**. El servidor firma
con una clave secreta; si alguien cambia un dato, la firma ya no coincide. Cualquiera
puede **leer** un JWT, pero nadie puede **falsificarlo** sin la clave.

**El problema del JWT:** no se puede revocar. Si te lo roban, vale hasta que vence. La
solución estándar son dos tokens:

- uno de **acceso**, JWT, corto (minutos);
- uno de **refresco**, largo (días), que se guarda en la base y sí se puede revocar. Sirve
  sólo para pedir un acceso nuevo.

**Rotación:** cada vez que se usa el de refresco, se revoca y se entrega otro. Un token de
refresco robado sirve una sola vez.

**Multi-tenencia.** Cuando varias empresas usan el mismo sistema y la misma base, cada una
es un **tenant** (inquilino). El riesgo más grave es que una vea datos de otra. La defensa
clásica es una columna con el id del inquilino en cada tabla, y filtrar siempre por ella.

**404 o 403.** Si alguien pide un recurso que no es suyo y respondés 403, le confirmás que
existe. Para recursos de otro inquilino, lo correcto es **404**: para él, no existe.

### Cómo está en ObraViva

**Login** (`auth/AuthService.login`). Busca la cuenta por email y compara la contraseña
con BCrypt, un algoritmo de *hash* lento a propósito para que adivinar contraseñas sea
caro. Si falla, responde 401 "Credenciales inválidas", sin decir si fue el email o la
contraseña. Si sale bien, emite:

- **acceso** (`auth/JwtTokenService.generateAccessToken`): JWT firmado con HMAC-SHA512,
  **15 minutos**. Su `sub` es el id de la cuenta. **No lleva roles**: los permisos se leen
  de la base en cada pedido, así un cambio vale enseguida.
- **refresco** (`auth/RefreshTokenService.issue`): 48 bytes al azar, **7 días**. En la base
  se guarda **sólo su SHA-256**: si alguien lee la base, no obtiene tokens usables.

**Refresco** (`POST /api/auth/refresh` → `RefreshTokenService.validateAndRevoke`): valida,
revoca el usado y emite un par nuevo. En el frontend, `web/src/app/core/auth/auth.interceptor.ts`
detecta un 401, refresca y reintenta una vez.

**Cambio de contraseña** (`AuthService.changePassword`): revoca todos los tokens de
refresco de la cuenta (`revokeAllFor`) y devuelve un par nuevo. Quien cambió la contraseña
sigue adentro; cualquier otra sesión se corta.

**Verificar el token** (`auth/SecurityConfig`): `NimbusJwtDecoder` con HS512. El algoritmo
se declara en el encoder y en el decoder, porque un desacuerdo entre los dos ya causó un
bug. Las rutas públicas son: login, refresco, logout, `/actuator/health`, los `GET` de
`/api/public/**` (enlace público) y `/error`.

**`organization_id`.** Toda entidad de negocio lo tiene, y los repositorios buscan por
`…AndOrganizationIdIn(organizationIds)`. La lista de organizaciones del usuario sale de
`organizations/OrganizationMembershipService.organizationIdsOf`. La seguridad vive en la
firma del repositorio: no hay un `findById` seguido de un `if`, que es la forma habitual
de olvidarse una verificación.

**Roles y alcance.** Hay cuatro formas de estar del otro lado:

| Quién | Cómo llega | Qué ve |
|---|---|---|
| **Dueño** (`OWNER`) | Por la organización | Todo: obras, plata, compradores, alineación 3D |
| **Encargado de todo el desarrollo** (`MEMBER` + `development_members`) | Por la organización, en las obras asignadas | Carga en todas las casas y las obras comunes. No ve plata, precio ni compradores |
| **Encargado de casas puntuales** (`MEMBER` + `unit_supervisors`) | Por la organización, en sus casas | Carga sólo en sus casas. Lee sus casas y las obras comunes |
| **Comprador** (`unit_members`) | Por la casa comprada | Su casa y lo común de su obra, en "Mi propiedad" |

Las reglas están en dos clases:

- `developments/DevelopmentAccessService`: la llegada a una obra (`requireAccess`,
  `canAccess`), el rol (`requireOwnerAccess`, `requireOwnerOf`) y las casas
  (`canWorkOnUnit`, `requireWorkOnUnit`, `readableUnits`, `workableUnits`).
- `developments/UnitAccessService.requireAccess`: los **dos caminos** a una casa, por la
  organización o por haberla comprado.

**404 o 403 en ObraViva.** Lo que no te corresponde ver no existe (**404**); lo que ves pero
no te toca hacer está prohibido (**403**):

- otra organización, una obra no asignada, una casa ajena para un encargado de casas
  puntuales: **404**;
- un encargado que intenta algo del dueño (ver el panel, crear etapas): **403**
  (`OrganizationMembershipService.ownerOnly()`). Ya sabe que la obra existe; un 404 le
  mentiría sin proteger nada;
- un encargado de casas puntuales que intenta cargar en otra casa del desarrollo: **403**
  (`requireWorkOnUnit`).

### Dónde mirar

1. `auth/SecurityConfig`.
2. `auth/AuthService`, `auth/JwtTokenService`, `auth/RefreshTokenService`.
3. `organizations/OrganizationMembershipService`.
4. `developments/DevelopmentAccessService`.
5. `developments/UnitAccessService`.
6. Tests: `auth/RefreshTokenIntegrationTest` (rotación),
   `developments/OrganizationIsolationIntegrationTest` (404 entre organizaciones),
   `developments/HouseScopeReadIntegrationTest` (encargado de casas puntuales).

### Ejercicio

1. Leé `RefreshTokenIntegrationTest` y, para cada test, predecí el código HTTP antes de
   leer el `andExpect`.
2. Buscá en `DevelopmentAccessService` el comentario de `requireOwnerAccess` que explica
   por qué ahí sale 403 y no 404. Escribilo con tus palabras.
3. En `UnitAccessService.requireAccess`, ¿qué pasa con alguien que es encargado en una
   organización y comprador de una casa de otra?

### Preguntas de defensa

- ¿Cuál es la diferencia entre 401 y 403? ¿Y entre 403 y 404?
- ¿Por qué el JWT no lleva el rol del usuario?
- Si te roban el token de refresco, ¿qué puede hacer el atacante y qué lo frena?
- ¿Cómo garantizás que una organización no vea datos de otra?

---

## Capítulo 6. Publicar un avance

### El concepto

**Atomicidad**: una operación de varios pasos es atómica si se ve entera o no se ve. Es la
"A" de **ACID**, las propiedades que garantiza una transacción (atomicidad, consistencia,
aislamiento, durabilidad).

**Subida en dos pasos con URL firmada.** Subir archivos grandes a través del backend lo
satura. La alternativa: el backend genera una **URL firmada**, una dirección temporal que
autoriza a subir un archivo puntual al almacenamiento, y el navegador sube directo. Después
avisa al backend, que verifica que el archivo esté antes de darlo por subido.

### Cómo está en ObraViva

El encargado escribe qué se hizo, adjunta fotos y, si corresponde, mueve la etapa. Primero
las fotos, después la publicación.

**1. Subir cada foto** (`media/MediaService`):

1. `POST /api/developments/{id}/media/upload-url` (`requestUploadUrl`): crea un
   `MediaAsset` en estado `PENDING` y devuelve una URL firmada de subida (vale 15 minutos).
2. El navegador sube la foto directo a MinIO.
3. `POST /api/media/{id}/confirm` (`confirmUpload`): verifica con `StorageService.exists`
   que el archivo esté. No se confía en la palabra del cliente.

**2. Publicar** (`POST /api/developments/{id}/progress`): lo atiende
`publication/ProgressPublicationController`, que llama a
`ProgressPublicationService.publish`. El cuerpo trae el texto, la fecha, la casa y la
etapa, más `photoIds` (hasta 10), `photoSectors` (el lugar de cada foto) y `stageUpdate`
(porcentaje y estado nuevos de la etapa).

**3. Todo en una transacción**, en este orden (`publishOne`):

1. **El avance** (`ProgressService.create`): valida acceso, casa, etapa y que la fecha no
   sea futura.
2. **La etapa** (`StageCatalogService.applyProgressUpdate`), si vino `stageUpdate`. El
   avance guarda el porcentaje y el estado de antes y de después
   (`ProgressService.recordStageChange`), porque la etapa sólo sabe cómo está hoy.
3. **Las fotos** (`MediaCatalogService.attachPendingPhotos`): tienen que ser de la misma
   casa y la misma persona, estar en `PENDING` y existir en el almacenamiento.

Si una foto falla, la excepción revierte el avance y la etapa. **El comprador ve todo
junto o no ve nada.**

**Reglas que vale la pena conocer:**

- `stageUpdate` exige `stageId` y no puede volver una etapa a pendiente (400).
- Completada lleva la etapa a 100; llegar a 100 no la completa.
- `stageUpdate` acepta sólo porcentaje y estado. Spring ignora los campos desconocidos de
  un JSON, así que se rechazan con `@JsonAnySetter` para que un presupuesto colado no pase
  en silencio.

**Por qué vive en `publication` y no en `progress`.** Junta tres módulos. `media` ya
depende de `progress` para ubicar las fotos de un avance; si `progress` dependiera de
`media`, se cerraría un ciclo. Un cuarto módulo que usa a los tres lo evita.

**Después**, `media/variants/MediaVariantWorker` genera la miniatura y la versión de
pantalla de cada foto. Mientras no estén, la respuesta usa el original.

**Corregir** (`POST /api/progress/{entryId}/corrections`) no pisa el avance: crea una
versión nueva del mismo hilo (`rootId`).

### Dónde mirar

1. `media/MediaService`: `requestUploadUrl` y `confirmUpload`.
2. `publication/ProgressPublicationService`: entero, es corto.
3. `progress/ProgressService.create`.
4. `developments/StageCatalogService.applyProgressUpdate`.
5. `media/MediaCatalogService.attachPendingPhotos`.
6. Test: `publication/ProgressPublicationIntegrationTest`.

### Ejercicio

1. En `ProgressPublicationIntegrationTest`, leé "el encargado publica con fotos y la etapa
   al 35%, y el comprador ve todo junto en el enlace público". Anotá cada pedido HTTP que
   hace, en orden.
2. Cambiá mentalmente el orden de `publishOne`: primero las fotos, después el avance. ¿Se
   puede? Pista: ¿a qué avance se cuelgan las fotos?

### Preguntas de defensa

- ¿Qué garantiza que el comprador no vea un avance sin sus fotos?
- ¿Por qué el archivo no pasa por el backend?
- ¿Cómo sabe el servidor que la foto realmente se subió?
- ¿Por qué existe el paquete `publication`?

---

## Capítulo 7. Trabajos en segundo plano

### El concepto

Algunas tareas tardan minutos: transcribir un audio, reconstruir un modelo 3D. Un pedido
HTTP no puede esperar tanto. La solución es una **cola de trabajos**: el pedido registra
"hay que hacer esto" y responde enseguida; un **worker** (un proceso que trabaja de fondo)
toma el trabajo después y lo ejecuta.

Formas de enterarse de que hay trabajo:

- **Broker de mensajes** (RabbitMQ, Kafka): un sistema aparte que entrega los mensajes.
- **Polling**: el worker pregunta cada tanto "¿hay algo?". Más simple, un poco menos
  inmediato.

Cada trabajo tiene un **estado** (esperando, en proceso, terminado, fallido). Lo que
falla por algo pasajero (la red) se **reintenta**; lo que falla por algo definitivo (un
archivo inválido) no.

Un problema clásico: si el proceso se corta mientras trabaja, el trabajo queda "en
proceso" para siempre. Hay que **recuperarlo** al arrancar.

### Cómo está en ObraViva

**Cola propia**, sin broker: la tabla `processing_jobs` (entidad `jobs/ProcessingJob`) y
workers con `@Scheduled`, la anotación de Spring que ejecuta un método cada cierto
tiempo.

**Dos ejes de estado:**

- `jobs/JobStatus` dice si trabaja: `UPLOADED`, `PENDING`, `PROCESSING`, `NEEDS_REVIEW`,
  `COMPLETED`, `FAILED`.
- `jobs/PipelineStep` dice en qué punto está:
  - recorrido 3D: `PREPROCESS → SUBMIT → AWAIT_EXTERNAL → DOWNLOAD → CONVERT → DONE`;
  - audio: `TRANSCRIBE → INTERPRET → AWAIT_REVIEW`;
  - reporte: `NARRATE → AWAIT_REVIEW`.

  `PipelineStep.canAdvanceTo` impide saltarse pasos.

**La vida de un trabajo** (`jobs/ProcessingJobService`):

1. Nace en `UPLOADED` cuando se pide la URL de subida (`create`).
2. Pasa a `PENDING` cuando el archivo llegó (`markReady`). Recién ahí lo ven los workers.
3. Un worker lo toma con `claimNext(tipo, pasos)`: el `PENDING` más antiguo de ese tipo,
   en un paso que el worker sabe hacer. Pasa a `PROCESSING` y suma un intento.
4. Si sale bien, `completeStep` lo deja `PENDING` en el paso siguiente y reinicia los
   intentos. Si es el último paso, termina; si espera a una persona, `awaitHumanReview`
   lo deja en `NEEDS_REVIEW`.
5. Si falla por algo técnico, `retryOrFail` lo devuelve a la cola o, sin intentos, lo deja
   `FAILED`. Si es un rechazo definitivo, `fail`.

**Tres tiempos en todo worker:** una transacción corta para tomar el trabajo, el trabajo
pesado **sin transacción abierta** y otra transacción corta para guardar el resultado.
Un audio de tres minutos tarda unos 25 segundos en transcribirse, y KIRI tarda minutos;
retener una conexión de base todo ese tiempo agotaría el pool de conexiones (el conjunto
limitado de conexiones abiertas que comparte la aplicación).

**El método `@Scheduled` atrapa toda excepción.** Si la dejara pasar, Spring cancelaría
la tarea y el worker quedaría muerto hasta reiniciar.

**Sin bloqueo.** Hay una instancia y el planificador tiene un hilo, así que nadie compite
por un trabajo. Con varias instancias haría falta `SELECT … FOR UPDATE SKIP LOCKED`.

**Recuperación al arrancar** (`ai/AudioJobRecovery`): escucha el evento
`ApplicationReadyEvent` y llama a `AudioExtractionJobService.recoverInterruptedJobs`. Los
trabajos de audio en `PROCESSING` vuelven a la cola, contando el intento cortado; si era
el último, la extracción queda `FAILED`. Sólo audio: los recorridos 3D esperan a KIRI con
un corte por tiempo (`max-external-wait-minutes`), no por intentos.

**Los workers:** `captures3d/VideoPreprocessingWorker`, `captures3d/ReconstructionWorker`,
`captures3d/CaptureConversionWorker`, `ai/AudioExtractionWorker`, `reports/ReportWorker` y
`media/variants/MediaVariantWorker`. Este último no usa `processing_jobs`: su cola es la
propia foto (`variants_status`), porque es un paso sin espera externa ni revisión.

### Dónde mirar

1. `jobs/JobStatus` y `jobs/PipelineStep`.
2. `jobs/ProcessingJob`: las transiciones (`markProcessing`).
3. `jobs/ProcessingJobService`: `claimNext`, `completeStep`, `retryOrFail`.
4. `ai/AudioExtractionWorker`: un worker completo.
5. `ai/AudioExtractionJobService`: sus transacciones cortas.
6. `ai/AudioJobRecovery`.
7. Tests: `ai/AudioExtractionWorkerTest` (fallos, reintentos y recuperación) y
   `ai/AudioJobRecoveryTest`.

### Ejercicio

En `AudioExtractionWorkerTest`, la clase corre con `max-attempts=2`. Para el test
"agotados los intentos de interpretación…", predecí antes de leer las aserciones:

- cuántas veces se llama al transcriptor y cuántas al intérprete;
- en qué estado y en qué paso termina el trabajo;
- qué queda en `rawOutput` y por qué.

### Preguntas de defensa

- ¿Por qué una cola propia con polling y no un broker de mensajes?
- ¿Qué pasa si el backend se reinicia mientras transcribe un audio?
- ¿Por qué el trabajo pesado va fuera de la transacción?
- ¿Qué cambiarías para correr dos instancias del backend?

---

## Capítulo 8. El audio con IA

### El concepto

**Transcripción** (voz a texto): un modelo convierte audio en texto. **Whisper** es un
modelo abierto que corre en la propia máquina.

**Modelo de lenguaje** (LLM, *Large Language Model*): recibe un texto, el **prompt**, y
genera otro. Sirve para interpretar lenguaje natural, pero puede equivocarse con
seguridad o inventar (**alucinar**).

**Salida estructurada.** Para que un programa use lo que dice el modelo, se le pide la
respuesta en un formato fijo, normalmente JSON con campos conocidos. Hay dos maneras: que
la API del proveedor **imponga** un esquema, o pedirlo **en el prompt** y validar la
respuesta después.

**Humano en el circuito** (*human in the loop*): la IA propone y una persona confirma
antes de que algo tenga efecto. Se usa cuando un error del modelo tiene costo real.

### Cómo está en ObraViva

El encargado graba lo que pasó en la obra. El principio es: **la IA extrae, interpreta y
redacta; el motor de datos calcula; el humano confirma.** Ninguna cifra sale del modelo.

**1. Subida** (`ai/AudioExtractionService`):

1. `POST /api/developments/{id}/audio-extractions` (`createFromAudio`): crea la
   `AiExtraction`, un trabajo `AUDIO_EXTRACTION` y la URL firmada.
2. El navegador sube el audio a MinIO.
3. `POST /api/audio-extractions/{id}/confirm-audio` (`confirmAudioUpload`): verifica el
   archivo y pasa el trabajo a `PENDING`.

**2. Transcribir** (`ai/AudioExtractionWorker`, paso `TRANSCRIBE`). Baja el audio y lo
transcribe con `ai/transcription/WhisperAudioTranscriber` (puerto `AudioTranscriber`; en
los tests, `SimulatedAudioTranscriber`). Un audio ilegible o sin voz lanza
`InvalidAudioException` y falla de una vez, con un mensaje para el revisor.

**3. Interpretar** (paso `INTERPRET`). `ai/extraction/gemini/GeminiProgressExtractor`
(puerto `ProgressExtractor`; simulado, `MockProgressExtractor`):

- arma el prompt con la plantilla `src/main/resources/prompts/progress-extraction-v1.txt`,
  reemplazando `{{ETAPAS}}` por las etapas de la casa (con su id) y `{{TRANSCRIPCION}}` por
  el texto;
- lo manda por `ai/ChatCompletion`, una interfaz de un método (`complete`) que aísla a
  Spring AI en un solo lugar;
- interpreta la respuesta como JSON.

**La salida estructurada se pide en el prompt; la API no la impone.** El prompt dice
"respondé únicamente con un objeto JSON" y da la estructura exacta. Las reglas del prompt
también dicen: usar sólo los identificadores de etapa de la lista, sólo cuatro estados,
**no calcular porcentajes, plazos ni montos**, y citar en `rationale` el fragmento del
audio que respalda cada propuesta. El análisis es tolerante: si el modelo envuelve el JSON
en un bloque de código, se lo quita (`stripCodeFence`).

La respuesta tiene tres listas, cada una un record:

- `StageProposal`: un cambio de estado de etapa, con su confianza (`ConfidenceLevel`);
- `ProgressNote`: una novedad;
- `SupplyAlert`: un aviso de materiales.

**4. Validar** (`ai/extraction/ExtractionValidator`). Antes de que la propuesta llegue a
una persona, descarta lo que no encaja con el dominio: etapas inventadas, estados que no
existen, textos vacíos. **Descarta, no corrige**: adivinar a qué etapa se refería un id
inventado sería inventar sobre lo inventado. Se aplica igual al simulado y al real.

**5. Queda para revisión.** La extracción pasa a `READY_FOR_REVIEW` y el trabajo a
`NEEDS_REVIEW`: sale de la cola, porque lo que sigue lo decide una persona. La respuesta
cruda del modelo se guarda siempre (`rawOutput`), para poder reconstruir qué dijo.

**6. Revisar y publicar** (`AudioExtractionService.approve`). La persona ve la
transcripción junto a la propuesta, corrige, **pone los porcentajes** y elige fotos. Al
aprobar, cada novedad se publica como un avance de origen `AUDIO`, por **la misma
publicación atómica** del capítulo 6 (`publishAll`): si una falla, no se publica ninguna.
La propuesta guardada se reemplaza por lo que efectivamente se publicó.

**7. Avisos de materiales.** No se publican al comprador. Después de aprobar el audio,
aparecen **sólo al dueño** en Novedades (`activity`), como ítem `SUPPLY`.

### Dónde mirar

1. `src/main/resources/prompts/progress-extraction-v1.txt`: leelo entero.
2. `ai/AudioExtractionService`: `createFromAudio`, `confirmAudioUpload`, `approve`.
3. `ai/AudioExtractionWorker`.
4. `ai/extraction/gemini/GeminiProgressExtractor`.
5. `ai/extraction/ExtractionValidator`.
6. `ai/extraction/mock/MockProgressExtractor`.
7. Tests: `ai/extraction/ExtractionValidatorTest`,
   `ai/extraction/gemini/GeminiProgressExtractorTest` y
   `ai/AudioExtractionReviewIntegrationTest`.

### Ejercicio

1. En `ExtractionValidatorTest`, el test "acepta los cuatro estados del dominio y ninguno
   más" manda cinco propuestas. Predecí cuántas sobreviven y cuál se cae.
2. Buscá dónde se decide que el modelo **no** pone porcentajes. Hay dos lugares: uno en el
   prompt y otro en el código que publica. ¿Cuál es el que realmente lo garantiza?

### Preguntas de defensa

- ¿Qué pasa si el modelo inventa un id de etapa?
- ¿Cómo garantizás que ninguna cifra salga del modelo?
- ¿Por qué se guarda la respuesta cruda del modelo?
- ¿Usás la salida estructurada nativa del proveedor? ¿Por qué no, y qué la reemplaza?

---

## Capítulo 9. El 3D

### El concepto

**Gaussian Splatting** (3DGS, presentado en 2023) es una forma de representar una escena
3D a partir de fotos o de un video. En lugar de triángulos, la escena son **millones de
manchas** (*gaussianas*): elipsoides difusos con posición, tamaño, orientación, color y
opacidad. Un proceso de entrenamiento ajusta esas manchas hasta que, vistas desde la
posición de cada foto, reproducen la foto.

El resultado se ve casi como una foto y se dibuja en tiempo real en un navegador. El
costo: hace falta capturar bien (recorrer el lugar con la cámara) y entrenar requiere una
GPU potente. Por eso ObraViva no entrena: usa un servicio externo o el teléfono.

Formatos: **PLY** es un formato genérico de nubes de puntos, grande. **SPZ** es un formato
comprimido para *splats* (gzip con un encabezado `NGSP`), mucho más liviano.

**URLs firmadas.** Un almacenamiento S3 es privado. Para que el navegador suba o baje un
archivo sin pasar por el backend, el backend genera una URL que incluye una **firma** y un
**vencimiento**: autoriza esa operación, sobre ese archivo, por un rato. La firma se
calcula localmente con la clave del almacenamiento; no hace falta preguntarle nada.

### Cómo está en ObraViva

Cada lugar de cada casa puede tener un recorrido por fecha. El visor del frontend
(`web/src/app/features/viewer/splat-viewer.component.ts`, con la librería Spark) los
ordena en el tiempo: el **viaje en el tiempo**.

**URLs firmadas:** `storage/S3StorageService.presignUpload` y `presignDownload`, con el
SDK de AWS (`S3Presigner`). Las de subida valen 15 minutos y las de descarga una hora, o 5
minutos en el enlace público (capítulo 10).

**Las reglas comunes** están en `captures3d/CaptureRules`:

- la etapa y la casa tienen que ser de la obra (400);
- la fecha no puede ser futura y el lugar tiene que ser de la casa (400);
- **un recorrido por lugar y día** (409). Lo bloquean uno disponible o uno que se está
  reconstruyendo; una subida abandonada no;
- el recorrido nuevo **hereda la alineación** del último de su lugar, así el viaje en el
  tiempo queda casi alineado sin tocar nada.

**A. SPZ directo** (`captures3d/Capture3dService`). Lo exporta Scaniverse desde el
teléfono, sin gastar créditos.

1. `POST /api/developments/{id}/captures` (`createCapture`): rechaza lo que no es `.spz` o
   pasa de 50 MB declarados y devuelve la URL firmada.
2. El navegador sube a MinIO.
3. `POST /api/captures/{id}/confirm` (`confirmUpload`): **mide el archivo real**
   (`captures3d/SpzValidation`). Lee el tamaño y los primeros 64 KB, los descomprime y
   exige `NGSP`, versión 2 o 3 y más de 0 puntos. Si no cumple, borra el archivo y la
   captura y responde 400.

**B. Video reconstruido por KIRI** (`captures3d/VideoCaptureService` y tres workers):

1. `POST /api/developments/{id}/captures/from-video` (`createFromVideo`): crea la captura,
   el trabajo `CAPTURE_3D_RECONSTRUCTION` y la URL del video.
2. `POST /api/captures/{id}/confirm-video` (`confirmVideoUpload`): verifica el video y lo
   encola.
3. `PREPROCESS` (`VideoPreprocessingWorker`): ffmpeg (puerto `VideoProcessor`) lo
   normaliza a 1080p y hasta 3 minutos, el límite del servicio.
4. `SUBMIT` (`ReconstructionWorker`): lo envía a KIRI. **Acá se gasta un crédito**, sólo
   con el adaptador real.
5. `AWAIT_EXTERNAL`: consulta cada 30 segundos; KIRI tarda entre 7 y 20 minutos. Se corta
   por tiempo.
6. `DOWNLOAD`: baja el resultado comprimido.
7. `CONVERT` (`CaptureConversionWorker`): gsbox (puerto `SplatConverter`) convierte PLY a
   SPZ con `-rx 180`, porque los dos formatos usan ejes distintos. La captura pasa a
   `AVAILABLE`.

### Dónde mirar

1. `storage/StorageService` y `storage/S3StorageService`.
2. `captures3d/CaptureRules`.
3. `captures3d/Capture3dService` y `captures3d/SpzValidation`.
4. `captures3d/VideoCaptureService`.
5. Los workers en orden: `VideoPreprocessingWorker`, `ReconstructionWorker`,
   `CaptureConversionWorker`.
6. Tests: `captures3d/SpzValidationTest`, `captures3d/CaptureUploadRulesIntegrationTest`,
   `captures3d/CaptureProcessingIntegrationTest`, `storage/StorageTransferIntegrationTest`.

### Ejercicio

1. En `CaptureUploadRulesIntegrationTest`, leé "un video reconstruyéndose bloquea el
   sector ese día; uno sin confirmar o un SPZ sin confirmar, no". Antes de cada
   `andExpect`, predecí el código.
2. ¿Por qué `confirmUpload` es `noRollbackFor = ResponseStatusException.class`? Escribí
   qué pasaría sin eso, paso por paso.

### Preguntas de defensa

- ¿Qué es Gaussian Splatting y por qué no lo entrenan ustedes?
- ¿Cómo validás un SPZ si el archivo nunca pasa por el backend?
- ¿Qué impide gastar un crédito de KIRI en un recorrido que no se puede publicar?
- ¿Qué es una URL firmada y por qué tiene vencimiento?

---

## Capítulo 10. Enlace público y panel económico

### El concepto

**Proyección por contexto.** Los mismos datos se muestran distinto según quién mira. En
vez de un único DTO con todo, se arma una **proyección** (una vista parcial) para cada
contexto, y cada campo que se omite es una decisión.

**Token de acceso compartible.** Para compartir algo sin cuenta, se genera un token largo
al azar y se pone en la URL. Quien tiene la URL tiene acceso. Como una contraseña, se
guarda **hasheado**: si alguien lee la base, no obtiene enlaces válidos.

### Cómo está en ObraViva

**Proyecciones por contexto:**

- `developments/StageResponse.forViewer`: el presupuesto, sólo para el dueño.
- `developments/UnitResponse.from(..., showCommercial)`: estado comercial y precio, sólo
  para el dueño.
- `sharing/UnitViewAssembler.assemble` arma `PublicUnitView`, la vista del comprador. La
  usan "Mi propiedad" (`sharing/MyPropertyService`, URLs de una hora) y el enlace público
  (`sharing/PublicViewService`, URLs de 5 minutos). Nunca trae precio, presupuesto,
  documentos, ids internos ni claves de almacenamiento.
- `MyPropertyResponse.preview` existe sólo si quien mira es del equipo ("Ver como el
  comprador"); para el comprador es nulo.

**El enlace público:**

1. **Emitir** (`POST /api/units/{unitId}/share-links` → `sharing/ShareLinkService.create`):
   lo pueden hacer el comprador o el dueño. `sharing/ShareToken.generate` da 32 bytes de
   `SecureRandom` en base64. Se guarda **sólo el SHA-256** y el token se devuelve una vez.
   Sin sal ni bcrypt, a propósito: con 256 bits al azar no hay diccionario que lo alcance,
   y una función lenta sólo demoraría cada visita.
2. **Ver** (`GET /api/public/share/{token}` → `sharing/PublicShareController` →
   `PublicViewService.view`): el único punto sin sesión. No mira la sesión ni aunque
   exista. Si el token no existe, venció o fue revocado, responde **el mismo 404**, para no
   confirmar que existió.
3. **Contar visitas** (`registerAccess`, `REQUIRES_NEW`): si falla, igual se ve la casa.
4. **Revocar** (`POST /api/share-links/{linkId}/revoke`): deja de servir en el acto. Las
   URLs firmadas que ya se entregaron sobreviven hasta vencer, y por eso duran 5 minutos.

**El panel económico** (`GET /api/developments/{id}/dashboard?unitId=` →
`developments/DashboardService`). Es sólo del dueño: el encargado recibe 403.

- **Avance físico:** el promedio de las etapas ponderado por su peso. La plantilla de casa
  (`StandardHouseStages`) trae 12 etapas con pesos que suman 100. El avance del desarrollo
  es el promedio de sus casas.
- **Gasto:** la suma de los gastos cargados (`Expense`), por etapa, por casa o generales.
- **Semáforo por etapa** (`trafficLight`): desviación = % del presupuesto consumido − %
  de avance. Hasta 10 puntos, verde; hasta 25, amarillo; más, rojo. Sin presupuesto,
  `NO_BUDGET`, no verde: pintar de verde lo que nadie presupuestó sería afirmar algo que
  nadie dijo.
- **Curva prevista** (`plannedCurve`): el presupuesto de cada etapa repartido por días
  entre su inicio y su fin previstos, acumulado por mes.

**El costo final estimado lo calcula el frontend, no el backend.** El backend manda lo
gastado, el avance y cuántas etapas no tienen presupuesto. La cuenta está en
`web/src/app/features/works/economics-derivations.ts`:

- `finalCost(gastado, avance) = gastado / (avance / 100)`. Es una **regla de tres**: "si se
  sigue gastando al mismo ritmo que se avanza". Sin avance no hay estimado; al 100% da
  exactamente lo gastado.
- `houseEstimate`: el estimado de una casa, comparado con su presupuesto **sólo si todas
  sus etapas tienen presupuesto**. Si no, dice "Presupuesto incompleto".
- `developmentEstimate`: la **suma** de los estimados de cada casa y de las obras comunes,
  no lo gastado dividido por el avance del desarrollo, que promedia casas y deja afuera lo
  común. Una parte sin avance cuenta con su presupuesto (o con lo gastado, si es más).

### Dónde mirar

1. `sharing/ShareToken` y `sharing/ShareLinkService`.
2. `sharing/PublicShareController` y `sharing/PublicViewService`.
3. `sharing/UnitViewAssembler`.
4. `developments/DashboardService`: `trafficLight` y `plannedCurve`.
5. `web/src/app/features/works/economics-derivations.ts`.
6. Tests: `sharing/PublicShareIsolationIntegrationTest`,
   `developments/DashboardIntegrationTest` y, en el frontend,
   `web/src/app/features/works/economics-derivations.spec.ts`.

### Ejercicio

1. En `DashboardIntegrationTest`, el test "gastar mucho más de lo avanzado enciende el
   rojo" carga una etapa al 20% con 10.000.000 de presupuesto y gasta 8.000.000. Calculá la
   desviación y el semáforo antes de leer el `andExpect`.
2. Con esos mismos números, calculá el costo final estimado de esa etapa con `finalCost`.
   ¿Cuánto se pasaría del presupuesto?
3. Leé "la vista pública no expone ningún identificador interno" en
   `PublicShareIsolationIntegrationTest`: ¿cómo lo verifica?

### Preguntas de defensa

- ¿Qué ve el comprador que no ve un visitante del enlace, y al revés?
- ¿Por qué el token del enlace se guarda hasheado, y por qué con SHA-256 y no con bcrypt?
- ¿Cómo se calcula el costo final estimado y qué supuesto hace?
- ¿Por qué una etapa sin presupuesto no es verde?

---

## Capítulo 11. Tests

### El concepto

**Test unitario:** prueba una clase sola, sin base ni servidor. Es rápido y señala con
precisión qué se rompió. Lo que la clase necesita se reemplaza por **dobles**.

**Test de integración:** prueba varias piezas juntas, con la base real y el contexto de
Spring. Es más lento, pero encuentra errores que un unitario no ve: una consulta mal
escrita, una transacción que no se aplica, un permiso mal cableado.

**Mockito** crea dobles de prueba (**mocks**): objetos que imitan una interfaz y devuelven
lo que les indicás (`when(...).thenReturn(...)`). Después podés **verificar** cómo se los
llamó (`verify(mock, times(1))`).

**Testcontainers** levanta contenedores Docker desde el test: una base PostgreSQL real, un
MinIO real. Se crean al empezar y se destruyen al terminar. Así un test de integración no
depende de lo que tengas instalado ni toca tus datos.

**Cómo leer un test para entender el código.** Un buen test tiene tres partes:
**preparar** (el estado inicial), **actuar** (la llamada) y **verificar** (las
aserciones). El nombre (`@DisplayName`) dice en palabras qué comportamiento se espera.
Leer los nombres de los tests de una clase es la forma más rápida de saber qué hace.

### Cómo está en ObraViva

El backend tiene más de cien clases de test, y la suite completa corre 631 tests.

**Unitarios**, sin Spring:

- `ai/extraction/ExtractionValidatorTest`: arma propuestas a mano y verifica qué descarta
  el validador.
- `captures3d/SpzValidationTest`: arma SPZ válidos e inválidos en memoria
  (`support/SpzFixtures`).
- `ai/AudioJobRecoveryTest`: con `mock(AudioExtractionJobService.class)` verifica que la
  recuperación se llame o no según la propiedad.
- Los `*DeserializationTest`: verifican que el JSON real del cliente se convierta en el
  DTO. Existen porque Jackson 3 rechaza un record si falta un valor de tipo primitivo.

**Integración**, con `@SpringBootTest`:

- `@Testcontainers` y un `PostgreSQLContainer("postgres:16-alpine")` por clase, conectado
  con `@ServiceConnection`.
- MinIO: los tests que lo necesitan registran el de la suite con `support/TestMinio`; los
  demás reemplazan el almacenamiento con `@MockitoBean private StorageService`.
- `@AutoConfigureMockMvc` y `MockMvc`: mandan pedidos HTTP falsos al controlador, sin
  levantar un servidor. Con eso se prueban los códigos y el JSON.
- `PublicShareHttpIntegrationTest` usa un servidor real, porque MockMvc no ejecuta el
  reenvío interno a `/error` y ese reenvío causó un bug real.

**Reglas de los tests de cola:**

- vacían `processing_jobs` en `@BeforeEach`: la cola toma el trabajo más antiguo, y un
  trabajo de otro test lo contaminaría;
- apagan los workers (`obraviva.jobs.enabled=false`) y llaman `processNext()` a mano;
- verifican cuántas veces se llamó al adaptador mockeado, para que un test no pase por el
  motivo equivocado.

### Dónde mirar

1. `ai/extraction/ExtractionValidatorTest`: un unitario puro.
2. `ai/AudioJobRecoveryTest`: un unitario con Mockito.
3. `developments/StageIntegrationTest`: un integración simple.
4. `ai/AudioExtractionWorkerTest`: un integración de cola, con mocks y `verify`.
5. `support/TestMinio`: un contenedor compartido.

### Ejercicio

1. Abrí `HouseScopeReadIntegrationTest` y leé **sólo** los `@DisplayName`. Escribí, sin
   mirar el código de producción, las reglas de qué ve un encargado de casas puntuales.
   Después compará con `DevelopmentAccessService.readableUnits`.
2. En `AudioExtractionWorkerTest`, borrá mentalmente el `jobRepository.deleteAll()` del
   `@BeforeEach`. ¿Qué test podría pasar por el motivo equivocado, y por qué?

### Preguntas de defensa

- ¿Qué diferencia hay entre un test unitario y uno de integración, y cuándo usás cada uno?
- ¿Por qué Testcontainers y no una base en memoria como H2?
- ¿Qué verifica un `verify(mock, times(1))` que una aserción sobre el resultado no ve?
- ¿Cómo sabés que tus tests de aislamiento entre organizaciones prueban lo correcto?

---

## Capítulo 12. Decisiones que se pueden discutir

Son puntos donde el código no cumple al pie de la letra lo que dice la sección 8.3 del
documento maestro, o donde un evaluador podría preguntar. Cada uno fue una decisión, no un
descuido.

### B. Los puertos viven en los paquetes de infraestructura

**Qué pasa.** `StorageService` está en `storage`, `VideoProcessor` en `video`,
`SplatConverter` en `conversion` y `ReconstructionService` en `reconstruction`, cada uno
junto a sus adaptadores. El dominio (`captures3d`, `media`) importa esos paquetes. En una
arquitectura hexagonal de manual, el puerto pertenece al dominio.

**Por qué está bien así.** La dependencia ya va hacia una **abstracción**: ningún módulo de
dominio importa `KiriReconstructionService`, `FfmpegVideoProcessor` ni
`GsboxSplatConverter`. Cambiar de proveedor es escribir otro adaptador, sin tocar el
dominio, que es lo que la regla busca proteger. Mover las interfaces al dominio obligaría a
decidir de qué módulo es `StorageService`, que usan nueve módulos, y eso pediría un paquete
"dominio compartido" que agregaría una capa.

**Cómo defenderlo.** La regla protege contra depender de un proveedor concreto, y eso se
cumple. El documento maestro se corrigió para decir lo que hay.

**Excepción dentro de la excepción.** Las versiones livianas de fotos
(`media/variants/MediaVariantGenerator`) llaman a ffmpeg directamente, por
`common/process/ProcessRunner`, sin puerto. ffmpeg es una herramienta local: no cuesta, no
falla por red y no hace falta simularla.

### C. `activity` lee entidades de otros módulos

**Qué pasa.** Novedades (`activity/ActivityQueries`) consulta directamente `ProgressEntry`,
`MediaAsset`, `Capture3d`, `AiExtraction` y `ProcessingJob`, y usa `UnitRepository` y
`UserRepository`. Rompe la regla de que ningún módulo toca entidades de otro.

**Por qué.** El feed **pagina por cursor**: cada fuente trae a lo sumo el tope más uno de
lo anterior al cursor, y se mezclan en memoria. Por los servicios públicos habría que pedir
"todo lo de la obra" y paginar después, o agregarle a cinco módulos una API de paginación
que sólo usaría Novedades. Lo primero trae la obra entera en cada página; lo segundo
reparte el feed en cinco módulos.

**Lo que lo hace aceptable.** Es sólo lectura, salvo su propia tabla (`activity_seen`).
Todas sus consultas están en una clase, acotadas por desarrollo y organización, y los
permisos pasan por `DevelopmentAccessService`. Si cambia una entidad, se rompe una sola
clase y `ActivityIntegrationTest` lo detecta.

**Cómo defenderlo.** Es una excepción explícita, en un solo lugar y documentada. La
alternativa "pura" cuesta memoria o acoplamiento, y no gana seguridad.

### El ciclo entre módulos

**Qué pasa.** Hay tres ciclos entre paquetes:

- `developments → captures3d → developments`;
- `developments → media → developments`;
- `media → progress → developments → media`.

**Por qué aparecen.** `developments` es a la vez la **raíz de permisos**
(`DevelopmentAccessService`, `UnitAccessService`), que usan todos, y el dueño de las casas
y sus lugares. Cuando una casa necesita saber si un lugar tiene material antes de borrarlo,
pregunta a `media` y a `captures3d`, que a su vez preguntan permisos a `developments`.

**Por qué no rompe nada en la práctica.**

- Los ciclos son entre **servicios públicos**, nunca entre entidades.
- Son ciclos entre paquetes, no entre beans. Spring Boot rechaza al arrancar un ciclo de
  inyección por constructor (no está habilitado `allow-circular-references`), y la
  aplicación arranca: ningún servicio termina dependiendo de sí mismo.
- `publication` existe justamente para no agregar otro ciclo (capítulo 6).

**Qué costaría sacarlos.** Mover los permisos a su propio módulo. Ese módulo tendría que
leer casas, asignaciones y organizaciones sin tocar las entidades de `developments`, lo
que obliga a duplicar repositorios o a volver públicas las entidades.

### Por qué no se partió `developments`

**Qué pasa.** Es el paquete más grande: 60 archivos y unas 6.000 líneas.

**Por qué no se partió en subpaquetes.** En Java, la visibilidad de paquete es la única
forma de encapsular sin el sistema de módulos de Java. Hoy muchas clases y métodos de
`developments` son *package-private* (sin modificador): nada de afuera puede usarlos, y el
compilador lo garantiza. Partirlo en `developments.units`, `developments.stages`, etc.
obligaría a volver **públicas** casi todas, porque una casa conoce su desarrollo, una etapa
su casa y el panel a las tres. Habría **más superficie pública y menos encapsulamiento**, a
cambio de carpetas más chicas.

**Cómo defenderlo.** El tamaño refleja el dominio central. La frontera que importa es la
de afuera: los demás módulos lo usan sólo por sus servicios públicos.

### El costo final estimado se calcula en el frontend

**Qué pasa.** El panel muestra un "Costo final estimado" por casa y por desarrollo, pero esa
cifra no sale del backend. `DashboardService` devuelve lo gastado, el avance, el presupuesto
y cuántas etapas no tienen presupuesto (`stagesWithoutBudget`). La cuenta la hace el
navegador, en `web/src/app/features/works/economics-derivations.ts` (`finalCost`,
`houseEstimate`, `developmentEstimate`), y la prueba
`web/src/app/features/works/economics-derivations.spec.ts`.

**Por qué se puede sostener así.**

- Es una **derivación de presentación**: una regla de tres sobre números que la respuesta
  ya trae. No necesita datos nuevos ni otra consulta a la base.
- **No abre un agujero de permisos.** Las cifras de entrada ya pasaron por el backend, que
  sólo se las da al dueño (el encargado recibe 403 en el panel). El navegador no puede
  calcular nada que no haya recibido.
- **No es una cifra de la IA.** El principio "la IA no calcula" se cumple igual: la cuenta es
  código determinista, con su test.

**Lo que tiene de discutible.**

- El documento maestro dice que **el motor de datos calcula**. Una cifra que se ve en el
  panel y no está en la API es una cifra que el motor no conoce.
- **Sólo la ve un cliente.** El reporte mensual (`reports`) sólo puede escribir los números
  que el motor le pasa en su JSON, y su prompt (`report-narration-v1.txt`) le prohíbe
  estimar costos. Como el estimado no existe en el backend, el reporte no puede
  mencionarlo. Una exportación o una app móvil tendrían que repetir la cuenta, con el
  riesgo de hacerla distinta.
- **La regla queda partida.** Qué cuenta como "presupuesto incompleto" lo decide el
  backend (`stagesWithoutBudget`); cómo se estima y cómo se suma el desarrollo, el frontend.
  Para entender la cifra entera hay que leer dos lenguajes.

**Qué cambiaría en producción.** Mover la cuenta a `DashboardService` y agregar al DTO del
panel el estimado, la diferencia contra el presupuesto y la marca de presupuesto
incompleto, por casa, por obras comunes y para el desarrollo. El frontend sólo los
formatearía, y los casos de `economics-derivations.spec.ts` pasarían a un test del
backend. Así hay una sola fuente para la web, el reporte y cualquier cliente nuevo. El
costo es un cambio de API y de contrato con el frontend, que durante la tesis no se
justificaba porque hay un solo cliente.

**Cómo defenderlo.** Para un solo cliente, una derivación simple sobre datos ya
autorizados puede vivir en la presentación. Pero si la cifra se vuelve un dato del negocio
(que se reporta, se exporta o se compara), su lugar es el backend, y ese es el primer
cambio que haría al pasar a producción.

### Dos detalles menores

- `common/process/ProcessRunner` lanza `VideoProcessingException`, una excepción del
  paquete `video`: viene de cuando vivía adentro del adaptador de ffmpeg. Cambiarla
  cambiaría qué atrapan sus llamadores, así que se dejó como estaba.
- **La verificación de las reglas de dependencia no está automatizada.** El maestro la
  prometía (por ejemplo, con ArchUnit) y se corrigió: se sostiene por convención y
  revisión.

### Preguntas de defensa

- Si las reglas de dependencia no se verifican solas, ¿cómo sabés que se cumplen?
- ¿Por qué `activity` puede leer entidades ajenas y otros módulos no?
- ¿Qué te costaría eliminar el ciclo entre `developments` y `captures3d`?
- ¿Por qué no partiste `developments`, si es cuatro veces más grande que el resto?
- El costo final estimado no está en la API: ¿quién lo calcula y qué pasaría con un segundo
  cliente?

---

## Glosario

| Término | Qué es |
|---|---|
| **ACID** | Propiedades de una transacción: atomicidad, consistencia, aislamiento y durabilidad |
| **Adaptador** | La clase que implementa un puerto con un proveedor concreto, o lo simula |
| **Alucinación** | Cuando un modelo de lenguaje inventa algo con seguridad |
| **API** | El conjunto de rutas y formatos con que un programa se comunica con otro |
| **Atomicidad** | Que una operación de varios pasos se vea entera o no se vea |
| **Autenticación** | Comprobar quién sos |
| **Autoconfiguración** | Spring Boot arma componentes solo según las dependencias y propiedades |
| **Autorización** | Decidir si podés hacer algo |
| **Bean** | Un objeto que Spring crea y reparte |
| **BCrypt** | Algoritmo de hash lento, para guardar contraseñas |
| **Broker de mensajes** | Sistema aparte que entrega mensajes entre procesos (RabbitMQ, Kafka) |
| **Claim** | Un dato dentro de un JWT, como el id del usuario o el vencimiento |
| **Clave foránea (FK)** | Columna que guarda el id de una fila de otra tabla; la base verifica que exista |
| **Código de estado** | Número de la respuesta HTTP: 200, 404, etc. |
| **Cola de trabajos** | Lista de tareas pendientes que un worker toma después |
| **Commit** | Confirmar una transacción |
| **Controlador** | La capa que recibe el pedido HTTP |
| **Cursor (paginación)** | Pedir "lo anterior a tal momento" en lugar de "la página 3" |
| **Deserializar** | Convertir JSON en un objeto |
| **Dispatcher** | La pieza de Spring MVC que elige qué método atiende un pedido |
| **DTO** | Objeto para transportar datos, sin lógica |
| **Entidad** | Clase que corresponde a una tabla |
| **Filtro** | Código que procesa el pedido antes del controlador, como la seguridad |
| **Flyway** | Herramienta que aplica migraciones en orden |
| **Gaussian Splatting (3DGS)** | Representar una escena 3D con millones de manchas difusas entrenadas desde fotos |
| **Hash** | Resumen de un dato que no se puede revertir (SHA-256, BCrypt) |
| **Hibernate** | La implementación de JPA |
| **HMAC** | Firma con clave secreta compartida; HS512 usa SHA-512 |
| **HTTP** | El protocolo de pedidos y respuestas de la web |
| **Humano en el circuito** | La IA propone y una persona confirma |
| **Inyección de dependencias** | Una clase recibe lo que necesita en lugar de crearlo |
| **Jackson** | La librería que convierte entre JSON y objetos Java |
| **JPA** | El estándar de Java para mapear objetos a tablas |
| **JSON** | Formato de texto para datos: `{"campo": "valor"}` |
| **JWT** | Token firmado con datos del usuario |
| **Lazy** | Que una relación se carga recién cuando se usa |
| **LLM** | Modelo de lenguaje grande, como Gemini |
| **Migración** | Archivo SQL numerado que cambia el esquema |
| **Mock** | Doble de prueba que imita una interfaz |
| **MockMvc** | Herramienta de Spring para mandar pedidos falsos a los controladores en un test |
| **Monolito modular** | Una sola aplicación con fronteras internas estrictas |
| **Multi-tenencia** | Varias empresas en el mismo sistema y la misma base |
| **ORM** | Herramienta que traduce entre objetos y filas |
| **Package-private** | Visible sólo dentro del mismo paquete Java |
| **PLY** | Formato genérico de nube de puntos, grande |
| **Polling** | Preguntar cada tanto si hay algo nuevo |
| **Pool de conexiones** | Conjunto limitado de conexiones a la base que comparte la aplicación |
| **Prompt** | El texto que se le manda a un modelo de lenguaje |
| **Proxy** | Objeto que envuelve a otro para agregar comportamiento, como abrir una transacción |
| **Proyección** | Una vista parcial de los datos, armada para un contexto |
| **Puerto** | Interfaz que dice qué necesita el dominio de algo externo |
| **Record** | Clase inmutable de Java para transportar datos |
| **Repositorio** | La capa que lee y escribe en la base |
| **REST** | Estilo de API con rutas que nombran recursos |
| **Rollback** | Deshacer una transacción |
| **Rotación de token** | Revocar el token de refresco usado y entregar otro |
| **S3** | El estándar de almacenamiento de objetos de Amazon; MinIO lo implementa |
| **Salida estructurada** | Pedirle al modelo la respuesta en un formato fijo, como JSON |
| **Serializar** | Convertir un objeto en JSON |
| **Servicio** | La capa con las reglas del negocio |
| **SHA-256** | Función de hash rápida |
| **SPZ** | Formato comprimido de Gaussian Splatting |
| **Spring Boot** | Framework que arma una aplicación Java con poca configuración |
| **Spring Data JPA** | Genera repositorios a partir de interfaces |
| **Stateless** | El servidor no recuerda nada entre pedidos |
| **Tenant** | Cada empresa en un sistema multi-tenencia |
| **Testcontainers** | Levanta contenedores Docker desde los tests |
| **Test de integración** | Prueba varias piezas juntas, con base real |
| **Test unitario** | Prueba una clase sola |
| **Token de acceso** | JWT corto que acompaña cada pedido |
| **Token de refresco** | Token largo, revocable, que sirve para pedir otro de acceso |
| **Transacción** | Grupo de operaciones que pasan todas o ninguna |
| **URL firmada** | Dirección temporal que autoriza una operación sobre un archivo |
| **UUID** | Identificador de 128 bits generado al azar |
| **Worker** | Proceso que trabaja de fondo, tomando trabajos de una cola |

**Herramientas y servicios que aparecen en el código:**

| Nombre | Qué es |
|---|---|
| **`@Scheduled`** | Anotación de Spring que ejecuta un método cada cierto tiempo |
| **`ApplicationReadyEvent`** | Evento que Spring publica cuando la aplicación terminó de arrancar |
| **Docker** | Herramienta para correr programas en contenedores aislados |
| **ffmpeg** | Programa de línea de comandos para procesar video e imagen |
| **Gemini** | El modelo de lenguaje de Google que interpreta los audios |
| **gsbox** | Programa que convierte PLY a SPZ |
| **KIRI Engine** | Servicio externo que reconstruye un modelo 3D desde un video; cobra por crédito |
| **MinIO** | Almacenamiento compatible con S3 que corre en la propia máquina |
| **PostgreSQL** | La base de datos relacional del proyecto |
| **Scaniverse** | App de teléfono que captura y exporta SPZ |
| **`SecureRandom`** | Generador de números al azar de Java apto para seguridad |
| **Spark** | Librería del frontend que dibuja los SPZ en el navegador |
| **Spring AI** | Librería de Spring para hablar con modelos de lenguaje |
| **Spring MVC** | La parte de Spring que atiende pedidos HTTP con controladores |
| **Spring Security** | La parte de Spring que maneja autenticación y autorización |
| **Tomcat** | El servidor web que Spring Boot trae embebido |
| **Whisper** | Modelo de transcripción de voz a texto que corre local |
