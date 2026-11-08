package com.obraviva.platform.activity;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.progress.ProgressEntry;
import com.obraviva.platform.progress.ProgressEntryRepository;
import com.obraviva.platform.storage.StorageService;
import com.obraviva.platform.support.SpzFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Novedades del desarrollo: lo que publicaron los encargados, para el dueño.
 *
 * <p>Se prueba lo que no puede fallar: el feed no mezcla otro desarrollo ni otra organización,
 * el encargado sólo ve el de su casa, el cambio de etapa queda guardado al publicar, las fotos
 * de una sesión llegan como un solo ítem, pagina sin traer todo y "sin ver" no cuenta lo propio.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class ActivityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ProgressEntryRepository progressRepository;
    @Autowired private JdbcTemplate jdbc;

    private String ownerToken;
    private String supervisorToken;
    private String outsiderToken;
    private UUID developmentId;
    private UUID otherDevelopmentId;
    private UUID casa1;
    private UUID casa2;
    private UUID otherDevelopmentHouse;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.size(anyString())).thenReturn(SpzFixtures.TYPICAL_SIZE);
        when(storageService.readPrefix(anyString(), anyInt())).thenReturn(SpzFixtures.valid());

        Organization organization = organizationRepository.save(new Organization("Casas del Feed"));
        ownerToken = login(createUser("duenio", organization, OrganizationRole.OWNER));
        String supervisorEmail = createUser("carlos", organization, OrganizationRole.MEMBER);
        UUID supervisorId = userRepository.findByEmail(supervisorEmail).orElseThrow().getId();
        supervisorToken = login(supervisorEmail);
        outsiderToken = login(createUser("ajeno", organizationRepository.save(new Organization("Otra")), OrganizationRole.OWNER));

        developmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio del Feed"}""".formatted(organization.getId()))));
        JsonNode houses = json(ok(send(ownerToken, post("/api/developments/" + developmentId + "/units/batch"), """
                {"prefix":"Casa","firstNumber":1,"count":2,"withStandardStages":true}""")));
        casa1 = UUID.fromString(houses.get(0).get("id").asString());
        casa2 = UUID.fromString(houses.get(1).get("id").asString());
        ok(send(ownerToken, post("/api/developments/" + developmentId + "/team"), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(supervisorId, casa1)));

        otherDevelopmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Otro barrio"}""".formatted(organization.getId()))));
        otherDevelopmentHouse = UUID.fromString(json(ok(send(ownerToken, post("/api/developments/" + otherDevelopmentId + "/units/batch"), """
                {"prefix":"Lote","firstNumber":1,"count":1,"withStandardStages":true}"""))).get(0).get("id").asString());
    }

    // --- El cambio de etapa queda guardado al publicar ---

    @Test
    @DisplayName("la publicación guarda el porcentaje y el estado de antes y de después; sin stageUpdate quedan nulos")
    void publicationRecordsTheStageChange() throws Exception {
        UUID stage = firstStageOf(casa1);
        UUID withUpdate = idOf(ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-10-10","stageId":"%s","description":"Arrancó","unitId":"%s",
                 "stageUpdate":{"status":"IN_PROGRESS","progressPercent":35}}""".formatted(stage, casa1))));
        UUID completed = idOf(ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-10-12","stageId":"%s","description":"Terminó","unitId":"%s",
                 "stageUpdate":{"status":"COMPLETED"}}""".formatted(stage, casa1))));
        UUID plain = idOf(ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-10-12","stageId":"%s","description":"Sólo novedad","unitId":"%s"}""".formatted(stage, casa1))));

        ProgressEntry first = progressRepository.findById(withUpdate).orElseThrow();
        assertThat(first.getStagePercentBefore()).isZero();
        assertThat(first.getStagePercentAfter()).isEqualTo(35);
        assertThat(first.getStageStatusBefore().name()).isEqualTo("PENDING");
        assertThat(first.getStageStatusAfter().name()).isEqualTo("IN_PROGRESS");
        ProgressEntry done = progressRepository.findById(completed).orElseThrow();
        assertThat(done.getStagePercentBefore()).isEqualTo(35);
        assertThat(done.getStagePercentAfter()).isEqualTo(100);
        assertThat(done.getStageStatusAfter().name()).isEqualTo("COMPLETED");
        ProgressEntry untouched = progressRepository.findById(plain).orElseThrow();
        assertThat(untouched.getStagePercentBefore()).isNull();
        assertThat(untouched.getStagePercentAfter()).isNull();
        assertThat(untouched.getStageStatusBefore()).isNull();
        assertThat(untouched.getStageStatusAfter()).isNull();

        JsonNode items = feed(ownerToken, "").get("items");
        JsonNode change = itemOf(items, "entry:" + withUpdate).get("entry").get("stageChange");
        assertThat(change.get("percentBefore").asInt()).isZero();
        assertThat(change.get("percentAfter").asInt()).isEqualTo(35);
        assertThat(itemOf(items, "entry:" + plain).get("entry").get("stageChange").isNull()).isTrue();
    }

    // --- Qué trae el feed ---

    @Test
    @DisplayName("el feed trae avances, una sesión de fotos como un solo ítem y los recorridos, sin mezclar otro desarrollo")
    void feedBringsEveryKindOfTheDevelopmentOnly() throws Exception {
        UUID entry = idOf(ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-10-14","description":"Revoque del frente","unitId":"%s"}""".formatted(casa1))));
        sessionPhoto(supervisorToken, casa1, "Frente", "2026-10-14");
        sessionPhoto(supervisorToken, casa1, "Frente", "2026-10-14");
        sessionPhoto(supervisorToken, casa1, "Living y cocina", "2026-10-14");
        capture(supervisorToken, casa1, "Frente", "2026-10-14");
        ok(send(ownerToken, post("/api/developments/" + otherDevelopmentId + "/progress"), """
                {"entryDate":"2026-10-14","description":"Del otro barrio","unitId":"%s"}""".formatted(otherDevelopmentHouse)));

        JsonNode items = feed(ownerToken, "").get("items");

        assertThat(kinds(items)).containsExactlyInAnyOrder("ENTRY", "PHOTOS", "CAPTURE");
        JsonNode avance = itemOf(items, "entry:" + entry);
        assertThat(avance.get("unitCode").asString()).isEqualTo("Casa 1");
        assertThat(avance.get("authorName").asString()).isEqualTo("Usuario carlos");
        assertThat(avance.get("entry").get("source").asString()).isEqualTo("MANUAL");
        JsonNode session = items.valueStream().filter(item -> item.get("kind").asString().equals("PHOTOS")).findFirst().orElseThrow();
        assertThat(session.get("session").get("photos").asInt()).isEqualTo(3);
        assertThat(texts(session.get("session").get("sectors"))).containsExactly("Frente", "Living y cocina");
        assertThat(session.get("session").get("thumbnails")).hasSize(3);
        JsonNode recorrido = items.valueStream().filter(item -> item.get("kind").asString().equals("CAPTURE")).findFirst().orElseThrow();
        assertThat(recorrido.get("capture").get("zone").asString()).isEqualTo("Frente");
        assertThat(recorrido.get("capture").get("state").asString()).isEqualTo("READY");
        assertThat(items.toString()).doesNotContain("Del otro barrio");
    }

    @Test
    @DisplayName("filtra por casa, por quien publicó y por tipo")
    void filters() throws Exception {
        ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-10-14","description":"De Carlos en la 1","unitId":"%s"}""".formatted(casa1)));
        ok(send(ownerToken, post(progressUrl()), """
                {"entryDate":"2026-10-14","description":"Del dueño en la 2","unitId":"%s"}""".formatted(casa2)));
        sessionPhoto(supervisorToken, casa1, "Frente", "2026-10-14");
        UUID carlos = userIdOf(supervisorToken);

        assertThat(descriptions(feed(ownerToken, "?unitId=" + casa2).get("items"))).containsExactly("Del dueño en la 2");
        assertThat(kinds(feed(ownerToken, "?authorId=" + carlos).get("items"))).containsExactlyInAnyOrder("ENTRY", "PHOTOS");
        assertThat(descriptions(feed(ownerToken, "?authorId=" + carlos + "&kind=ENTRY").get("items"))).containsExactly("De Carlos en la 1");
        assertThat(kinds(feed(ownerToken, "?kind=PHOTOS").get("items"))).containsExactly("PHOTOS");
        assertThat(feed(ownerToken, "?kind=AUDIO").get("items")).isEmpty();
    }

    @Test
    @DisplayName("pagina por cursor: cada página trae a lo sumo el tope y la última no ofrece otra")
    void paginatesByCursor() throws Exception {
        for (int i = 1; i <= 3; i++) {
            ok(send(supervisorToken, post(progressUrl()), """
                    {"entryDate":"2026-10-1%d","description":"Avance %d","unitId":"%s"}""".formatted(i, i, casa1)));
        }

        JsonNode first = feed(ownerToken, "?limit=2");
        assertThat(descriptions(first.get("items"))).containsExactly("Avance 3", "Avance 2");
        String cursor = first.get("nextCursor").asString();
        JsonNode second = feed(ownerToken, "?limit=2&before=" + cursor);
        assertThat(descriptions(second.get("items"))).containsExactly("Avance 1");
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    // --- Sin ver ---

    @Test
    @DisplayName("sin ver: lo de otros posterior a la marca; lo propio nunca; una corrección ajena vuelve a contar")
    void unseenCountsOthersAndCorrections() throws Exception {
        UUID ajeno = idOf(ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-10-14","description":"De Carlos","unitId":"%s"}""".formatted(casa1))));
        UUID propio = idOf(ok(send(ownerToken, post(progressUrl()), """
                {"entryDate":"2026-10-14","description":"Del dueño","unitId":"%s"}""".formatted(casa2))));

        JsonNode items = feed(ownerToken, "").get("items");
        assertThat(itemOf(items, "entry:" + ajeno).get("unseen").asBoolean()).isTrue();
        assertThat(itemOf(items, "entry:" + propio).get("unseen").asBoolean()).isFalse();
        assertThat(json(ok(send(ownerToken, get(activityUrl() + "/unseen-count")))).get("count").asInt()).isEqualTo(1);

        // Visto hasta el ítem más nuevo mostrado.
        String newest = items.get(0).get("at").asString();
        send(ownerToken, put(activityUrl() + "/seen"), """
                {"until":"%s"}""".formatted(newest)).andExpect(status().isNoContent());
        assertThat(json(ok(send(ownerToken, get(activityUrl() + "/unseen-count")))).get("count").asInt()).isZero();
        assertThat(feed(ownerToken, "").get("seenUntil").asString()).isEqualTo(newest);

        // La corrección del dueño no es novedad; la del encargado, sí, y el avance queda en su lugar.
        ok(send(ownerToken, post("/api/progress/" + propio + "/corrections"), """
                {"entryDate":"2026-10-14","description":"Del dueño, corregido"}"""));
        assertThat(json(ok(send(ownerToken, get(activityUrl() + "/unseen-count")))).get("count").asInt()).isZero();
        ok(send(supervisorToken, post("/api/progress/" + ajeno + "/corrections"), """
                {"entryDate":"2026-10-14","description":"De Carlos, corregido"}"""));
        assertThat(json(ok(send(ownerToken, get(activityUrl() + "/unseen-count")))).get("count").asInt()).isEqualTo(1);
        JsonNode corrected = itemOf(feed(ownerToken, "").get("items"), "entry:" + ajeno);
        assertThat(corrected.get("unseen").asBoolean()).isTrue();
        assertThat(corrected.get("entry").get("description").asString()).isEqualTo("De Carlos, corregido");
        assertThat(corrected.get("entry").get("correctedByName").asString()).isEqualTo("Usuario carlos");
        assertThat(corrected.get("at").asString()).isEqualTo(itemOf(items, "entry:" + ajeno).get("at").asString());

        // La marca nunca retrocede.
        send(ownerToken, put(activityUrl() + "/seen"), """
                {"until":"2020-01-01T00:00:00Z"}""").andExpect(status().isNoContent());
        assertThat(feed(ownerToken, "").get("seenUntil").asString()).isEqualTo(newest);
    }

    // --- Permisos ---

    @Test
    @DisplayName("la carga inicial no es novedad: no está en el feed ni en sin ver, y corregirla conserva el origen")
    void initialLoadIsNotNews() throws Exception {
        UUID inicial = idOf(ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2025-03-07","description":"Estructura hormigonada","unitId":"%s"}""".formatted(casa1))));
        UUID nuevo = idOf(ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-10-14","description":"Cañerías del primer piso","unitId":"%s"}""".formatted(casa1))));
        // Como la deja el script de carga de la obra: directo en la base, nunca por la API.
        jdbc.update("update progress_entries set source = 'INITIAL_LOAD' where id = ?", inicial);

        JsonNode items = feed(ownerToken, "").get("items");
        assertThat(idsOf(items)).contains("entry:" + nuevo).doesNotContain("entry:" + inicial);
        assertThat(json(ok(send(ownerToken, get(activityUrl() + "/unseen-count")))).get("count").asInt()).isEqualTo(1);

        ok(send(supervisorToken, post("/api/progress/" + inicial + "/corrections"), """
                {"entryDate":"2025-03-07","description":"Estructura hormigonada y consolidada"}"""));
        assertThat(progressRepository.findAll().stream()
                .filter(entry -> entry.getRootId().equals(inicial))
                .map(entry -> entry.getSource().name())).containsOnly("INITIAL_LOAD");
        assertThat(json(ok(send(ownerToken, get(activityUrl() + "/unseen-count")))).get("count").asInt()).isEqualTo(1);
        assertThat(idsOf(feed(ownerToken, "").get("items"))).doesNotContain("entry:" + inicial);
        // La historia del dueño sí la muestra, con su origen, para la etiqueta.
        JsonNode timeline = json(ok(send(ownerToken, get(progressUrl()))));
        JsonNode shown = timeline.valueStream()
                .filter(entry -> entry.get("description").asString().startsWith("Estructura")).findFirst().orElseThrow();
        assertThat(shown.get("source").asString()).isEqualTo("INITIAL_LOAD");
        assertThat(shown.get("description").asString()).isEqualTo("Estructura hormigonada y consolidada");
    }

    @Test
    @DisplayName("el encargado: con su casa 200, sin casa o con otra casa 403; nada del dueño; otra organización 404")
    void permissions() throws Exception {
        ok(send(supervisorToken, get(activityUrl() + "?unitId=" + casa1)));
        send(supervisorToken, get(activityUrl())).andExpect(status().isForbidden());
        send(supervisorToken, get(activityUrl() + "?unitId=" + casa2)).andExpect(status().isForbidden());
        send(supervisorToken, get(activityUrl() + "/unseen-count")).andExpect(status().isForbidden());
        send(supervisorToken, get(activityUrl() + "/pending-audios")).andExpect(status().isForbidden());
        send(supervisorToken, put(activityUrl() + "/seen"), """
                {"until":"2026-10-14T00:00:00Z"}""").andExpect(status().isForbidden());

        send(outsiderToken, get(activityUrl())).andExpect(status().isNotFound());
        send(outsiderToken, get(activityUrl() + "?unitId=" + casa1)).andExpect(status().isNotFound());
        send(outsiderToken, get(activityUrl() + "/unseen-count")).andExpect(status().isNotFound());
        send(outsiderToken, get(activityUrl() + "/pending-audios")).andExpect(status().isNotFound());

        // Una casa de otro desarrollo no existe para este feed.
        send(ownerToken, get(activityUrl() + "?unitId=" + otherDevelopmentHouse)).andExpect(status().isNotFound());
        assertThat(json(ok(send(ownerToken, get(activityUrl() + "/pending-audios"))))).isEmpty();
    }

    @Test
    @DisplayName("el feed de una casa, para el encargado: sólo esa casa y sin marcas de visto")
    void houseFeedForTheSupervisor() throws Exception {
        ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-10-14","description":"En la 1","unitId":"%s"}""".formatted(casa1)));
        ok(send(ownerToken, post(progressUrl()), """
                {"entryDate":"2026-10-14","description":"En la 2","unitId":"%s"}""".formatted(casa2)));

        JsonNode page = feed(supervisorToken, "?unitId=" + casa1);

        assertThat(descriptions(page.get("items"))).containsExactly("En la 1");
        assertThat(page.get("items").get(0).get("unseen").isNull()).isTrue();
        assertThat(page.get("seenUntil").isNull()).isTrue();
    }

    // --- Armado ---

    private String activityUrl() {
        return "/api/developments/" + developmentId + "/activity";
    }

    private String progressUrl() {
        return "/api/developments/" + developmentId + "/progress";
    }

    // --- Avisos de materiales ---

    private static final String WITH_ALERT = """
            {"stageProposals":[],"progressNotes":[],"supplyAlerts":[{"description":"Falta el tablero eléctrico","confidence":"HIGH"}]}""";

    @Test
    @DisplayName("un audio aprobado con avisos de materiales es un ítem del dueño, con quien lo envió y sus avisos tal cual")
    void supplyAlertsOfApprovedAudios() throws Exception {
        UUID carlos = userIdOf(supervisorToken);
        UUID aviso = audio(carlos, developmentId, casa1, "APPROVED", WITH_ALERT);
        // No son avisos: aprobado sin avisos, sin aprobar, descartado, una propuesta que no es JSON y otro desarrollo.
        audio(carlos, developmentId, casa1, "APPROVED", """
                {"stageProposals":[],"progressNotes":[],"supplyAlerts":[]}""");
        audio(carlos, developmentId, casa1, "READY_FOR_REVIEW", WITH_ALERT);
        audio(carlos, developmentId, casa1, "REJECTED", WITH_ALERT);
        audio(carlos, developmentId, casa1, "APPROVED", "no es JSON");
        audio(carlos, otherDevelopmentId, otherDevelopmentHouse, "APPROVED", WITH_ALERT);

        JsonNode items = feed(ownerToken, "").get("items");
        assertThat(idsOf(items)).containsExactly("supply:" + aviso);
        JsonNode item = items.get(0);
        assertThat(item.get("kind").asString()).isEqualTo("SUPPLY");
        assertThat(item.get("authorName").asString()).isEqualTo("Usuario carlos");
        assertThat(item.get("unitCode").asString()).isEqualTo("Casa 1");
        assertThat(item.get("unseen").asBoolean()).isTrue();
        assertThat(item.get("supply").get("extractionId").asString()).isEqualTo(aviso.toString());
        assertThat(texts(item.get("supply").get("alerts"))).containsExactly("Falta el tablero eléctrico");
        assertThat(item.get("entry").isNull()).isTrue();

        assertThat(json(ok(send(ownerToken, get(activityUrl() + "/unseen-count")))).get("count").asInt()).isEqualTo(1);
        assertThat(idsOf(feed(ownerToken, "?kind=SUPPLY").get("items"))).containsExactly("supply:" + aviso);
        assertThat(feed(ownerToken, "?kind=ENTRY").get("items")).isEmpty();
        assertThat(idsOf(feed(ownerToken, "?unitId=" + casa1).get("items"))).containsExactly("supply:" + aviso);
        assertThat(feed(ownerToken, "?unitId=" + casa2).get("items")).isEmpty();
    }

    @Test
    @DisplayName("los avisos de materiales no le llegan al encargado: ni en el feed de su casa ni pidiéndolos")
    void supplyAlertsAreForTheOwner() throws Exception {
        audio(userIdOf(supervisorToken), developmentId, casa1, "APPROVED", WITH_ALERT);

        assertThat(feed(supervisorToken, "?unitId=" + casa1).get("items")).isEmpty();
        assertThat(feed(supervisorToken, "?unitId=" + casa1 + "&kind=SUPPLY").get("items")).isEmpty();
    }

    /**
     * Un audio con su propuesta guardada, escrito en la base: aprobar uno de verdad exige la
     * transcripción y el modelo, y acá sólo importa lo que quedó guardado.
     */
    private UUID audio(UUID author, UUID development, UUID unitId, String status, String proposal) {
        UUID id = UUID.randomUUID();
        UUID organization = jdbc.queryForObject("select organization_id from developments where id = ?", UUID.class, development);
        boolean reviewed = status.equals("APPROVED") || status.equals("REJECTED");
        jdbc.update("""
                insert into ai_extractions (id, organization_id, development_id, unit_id, audio_storage_key, proposal,
                    review_status, reviewed_by, reviewed_at, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, case when ? then now() end, ?)""",
                id, organization, development, unitId, "audios/" + id + ".ogg", proposal, status,
                reviewed ? author : null, reviewed, author);
        return id;
    }

    private JsonNode feed(String token, String query) throws Exception {
        return json(ok(send(token, get(activityUrl() + query))));
    }

    private UUID firstStageOf(UUID unitId) throws Exception {
        return UUID.fromString(json(ok(send(ownerToken, get("/api/units/" + unitId + "/stages")))).get(0).get("id").asString());
    }

    private void sessionPhoto(String token, UUID unitId, String sector, String takenOn) throws Exception {
        UUID mediaId = idOf(ok(send(token, post("/api/developments/" + developmentId + "/media/upload-url"), """
                {"filename":"f.jpg","contentType":"image/jpeg","unitId":"%s","sector":"%s","showcase":false,"takenOn":"%s"}"""
                .formatted(unitId, sector, takenOn))), "mediaId");
        ok(send(token, post("/api/media/" + mediaId + "/confirm"), "{}"));
    }

    private void capture(String token, UUID unitId, String zone, String date) throws Exception {
        UUID captureId = idOf(ok(send(token, post("/api/developments/" + developmentId + "/captures"), """
                {"captureDate":"%s","unitId":"%s","zone":"%s","filename":"s.spz","fileSize":1000}"""
                .formatted(date, unitId, zone))), "captureId");
        ok(send(token, post("/api/captures/" + captureId + "/confirm"), "{}"));
    }

    private UUID userIdOf(String token) throws Exception {
        return UUID.fromString(json(ok(send(token, get("/api/auth/me")))).get("id").asString());
    }

    private static JsonNode itemOf(JsonNode items, String id) {
        return items.valueStream().filter(item -> item.get("id").asString().equals(id)).findFirst().orElseThrow();
    }

    private static List<String> idsOf(JsonNode items) {
        return items.valueStream().map(item -> item.get("id").asString()).toList();
    }

    private static List<String> kinds(JsonNode items) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.get("kind").asString()));
        return values;
    }

    private static List<String> descriptions(JsonNode items) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.get("entry").get("description").asString()));
        return values;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private String createUser(String prefix, Organization organization, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        memberRepository.save(new OrganizationMember(organization, user.getId(), role));
        return email;
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("accessToken").asString();
    }

    private ResultActions send(String token, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    private ResultActions send(String token, MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String ok(ResultActions result) throws Exception {
        return result.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    private static UUID idOf(String body) {
        return idOf(body, "id");
    }

    private static UUID idOf(String body, String field) {
        return UUID.fromString(JSON.readTree(body).get(field).asString());
    }
}
