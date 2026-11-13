package com.obraviva.platform.developments;

import com.obraviva.platform.ai.AudioExtractionWorker;
import com.obraviva.platform.ai.extraction.AiExtractionRepository;
import com.obraviva.platform.ai.transcription.AudioTranscriber;
import com.obraviva.platform.ai.transcription.Transcription;
import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.jobs.ProcessingJobRepository;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo que lee un encargado de casas puntuales. Asignado a la Casa 1, ve lo de su casa y lo
 * de las obras comunes (sin cargarlas); lo de la Casa 3 no le llega en ningún listado, y
 * pedirla por su id responde 404, como si no existiera.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false",
        "obraviva.ai.extraction.provider=mock"
})
class HouseScopeReadIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";
    private static final String DAY = "2026-10-01";

    @MockitoBean private StorageService storageService;
    @MockitoBean private AudioTranscriber transcriber;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AudioExtractionWorker audioWorker;
    @Autowired private AiExtractionRepository extractionRepository;
    @Autowired private ProcessingJobRepository jobRepository;

    private String ownerToken;
    private String supervisorToken;
    private UUID developmentId;
    private UUID casa1;
    private UUID casa3;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();
        extractionRepository.deleteAll();
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.size(anyString())).thenReturn(SpzFixtures.TYPICAL_SIZE);
        when(storageService.readPrefix(anyString(), anyInt())).thenReturn(SpzFixtures.valid());
        when(transcriber.transcribe(any())).thenReturn(new Transcription(
                "Terminamos el obrador.", Duration.ofSeconds(5), Duration.ofSeconds(1)));

        Organization organization = organizationRepository.save(new Organization("Casas del Sur"));
        ownerToken = login(createUser("duenio", organization, OrganizationRole.OWNER));
        String supervisorEmail = createUser("carlos", organization, OrganizationRole.MEMBER);
        UUID supervisorId = userRepository.findByEmail(supervisorEmail).orElseThrow().getId();
        supervisorToken = login(supervisorEmail);

        developmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio del Sur"}""".formatted(organization.getId()))), "id");
        JsonNode houses = json(ok(send(ownerToken, post(developmentUrl("/units/batch")), """
                {"prefix":"Casa","firstNumber":1,"count":3,"withStandardStages":true}""")));
        casa1 = UUID.fromString(houses.get(0).get("id").asString());
        casa3 = UUID.fromString(houses.get(2).get("id").asString());
        ok(send(ownerToken, post(developmentUrl("/team")), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(supervisorId, casa1)));
    }

    @Test
    @DisplayName("avances: los de su casa y los comunes, nunca los de la Casa 3")
    void progress() throws Exception {
        progressEntry(casa1, "Platea de la Casa 1");
        progressEntry(casa3, "Platea de la Casa 3");
        progressEntry(null, "Cordón cuneta");

        assertThat(descriptions(json(ok(send(supervisorToken, get(developmentUrl("/progress")))))))
                .containsExactlyInAnyOrder("Platea de la Casa 1", "Cordón cuneta");
        assertThat(json(ok(send(ownerToken, get(developmentUrl("/progress")))))).hasSize(3);
    }

    @Test
    @DisplayName("fotos: las de su casa y las comunes, nunca las de la Casa 3")
    void media() throws Exception {
        photo(casa1);
        photo(casa3);
        photo(null);

        assertThat(unitIds(json(ok(send(supervisorToken, get(developmentUrl("/media")))))))
                .containsExactlyInAnyOrder(casa1.toString(), "null");
        assertThat(json(ok(send(ownerToken, get(developmentUrl("/media")))))).hasSize(3);
    }

    @Test
    @DisplayName("recorridos 3D: los de su casa; los de la Casa 3 no, ni disponibles ni en proceso")
    void captures() throws Exception {
        confirmedSpz(casa1);
        confirmedSpz(casa3);
        reconstructingVideo(casa1);
        reconstructingVideo(casa3);

        assertThat(unitIds(json(ok(send(supervisorToken, get(developmentUrl("/captures")))))))
                .containsExactly(casa1.toString());
        assertThat(json(ok(send(ownerToken, get(developmentUrl("/captures")))))).hasSize(2);
        assertThat(unitIds(json(ok(send(supervisorToken, get(developmentUrl("/captures/processing")))))))
                .containsExactly(casa1.toString());
        assertThat(json(ok(send(ownerToken, get(developmentUrl("/captures/processing")))))).hasSize(2);
    }

    @Test
    @DisplayName("audios: en el listado y en la bandeja de revisión, sólo los de su casa")
    void audios() throws Exception {
        UUID own = readyForReview(supervisorToken, casa1);
        UUID foreign = readyForReview(ownerToken, casa3);

        assertThat(ids(json(ok(send(supervisorToken, get(developmentUrl("/audio-extractions")))))))
                .containsExactly(own.toString());
        assertThat(ids(json(ok(send(supervisorToken, get("/api/audio-extractions/pending-review"))))))
                .containsExactly(own.toString());
        assertThat(ids(json(ok(send(ownerToken, get("/api/audio-extractions/pending-review"))))))
                .containsExactlyInAnyOrder(own.toString(), foreign.toString());
    }

    @Test
    @DisplayName("por casa: la Casa 3 no existe para él, ni su vista, ni sus enlaces, planos, documentos o compradores")
    void unitEndpoints() throws Exception {
        ok(send(supervisorToken, get("/api/my-units/" + casa1)));
        for (String path : List.of("/api/my-units/%s", "/api/units/%s/share-links", "/api/units/%s/plans",
                "/api/my-units/%s/documents", "/api/units/%s/members", "/api/units/%s/overview",
                "/api/units/%s/stages", "/api/units/%s/sectors")) {
            send(supervisorToken, get(path.formatted(casa3))).andExpect(status().isNotFound());
            send(ownerToken, get(path.formatted(casa3))).andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    @DisplayName("el avance del desarrollo y las casas terminadas salen de sus casas, no de todo el barrio")
    void developmentProgress() throws Exception {
        for (JsonNode stage : json(ok(send(ownerToken, get("/api/units/" + casa3 + "/stages"))))) {
            ok(send(ownerToken, patch("/api/stages/" + stage.get("id").asString() + "/plan"), """
                    {"progressPercent":100}"""));
        }

        JsonNode owner = json(ok(send(ownerToken, get(developmentUrl("")))));
        JsonNode supervisor = json(ok(send(supervisorToken, get(developmentUrl("")))));
        assertThat(owner.get("overallProgressPercent").asInt()).isGreaterThan(0);
        assertThat(supervisor.get("overallProgressPercent").asInt()).isZero();
        assertThat(supervisor.get("completedUnitCount").asInt()).isZero();

        JsonNode listed = json(ok(send(supervisorToken, get("/api/developments")))).get(0);
        assertThat(listed.get("overallProgressPercent").asInt()).isZero();
    }

    @Test
    @DisplayName("el estado comercial de una casa no le llega al encargado; al dueño sí")
    void salesStatus() throws Exception {
        JsonNode supervisorUnits = json(ok(send(supervisorToken, get(developmentUrl("/units")))));
        assertThat(supervisorUnits.get(0).get("salesStatus").isNull()).isTrue();
        JsonNode overview = json(ok(send(supervisorToken, get("/api/units/" + casa1 + "/overview"))));
        assertThat(overview.get("unit").get("salesStatus").isNull()).isTrue();

        assertThat(json(ok(send(ownerToken, get(developmentUrl("/units"))))).get(0).get("salesStatus").asString())
                .isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("Ver la casa: lo que lee el encargado no trae nada económico, comercial ni de compradores")
    void fieldHouseView() throws Exception {
        // El dueño carga todo lo que no tiene que llegarle: precio, presupuesto, comprador y enlace.
        ok(send(ownerToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/units/" + casa1 + "/sales-sheet"), """
                {"modelName":"Aromo","listPrice":185000,"priceCurrency":"USD"}"""));
        ok(send(ownerToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/units/" + casa1 + "/sales-status"), """
                {"status":"SOLD"}"""));
        UUID stage = UUID.fromString(json(ok(send(ownerToken, get("/api/units/" + casa1 + "/stages")))).get(0).get("id").asString());
        ok(send(ownerToken, patch("/api/stages/" + stage + "/plan"), """
                {"budgetAmount":1500000}"""));
        ok(send(ownerToken, post("/api/units/" + casa1 + "/members"), """
                {"email":"compradora-%s@obraviva.test","fullName":"Lucía Paz","password":"clave-segura-1"}"""
                .formatted(UUID.randomUUID())));
        ok(send(ownerToken, post("/api/units/" + casa1 + "/share-links"), """
                {"label":"Para la familia"}"""));

        for (String path : List.of("/api/units/%s/stages", "/api/my-units/%s", "/api/my-work")) {
            JsonNode body = json(ok(send(supervisorToken, get(path.formatted(casa1)))));
            assertThat(exposed(body, "")).as(path).isEmpty();
        }
        // El dueño sí lo ve, así que el recorrido de arriba no pasa por no tener nada que encontrar.
        assertThat(exposed(json(ok(send(ownerToken, get("/api/units/" + casa1 + "/stages")))), "")).isNotEmpty();
    }

    /** Campos con valor cuyo nombre habla de dinero, venta, compradores o enlaces. */
    private static List<String> exposed(JsonNode node, String path) {
        List<String> found = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(child -> found.addAll(exposed(child, path + "[]")));
        } else if (node.isObject()) {
            for (var property : node.properties()) {
                String name = property.getKey();
                JsonNode value = property.getValue();
                if (name.matches("(?i).*(price|budget|buyer|sales|economic|expense|spent|member|share|token|currency).*")
                        && !value.isNull() && !(value.isArray() && value.isEmpty())) {
                    found.add(path + "." + name);
                }
                found.addAll(exposed(value, path + "." + name));
            }
        }
        return found;
    }

    // --- Armado ---

    private UUID progressEntry(UUID unitId, String description) throws Exception {
        String unit = unitId == null ? "" : ",\"unitId\":\"%s\"".formatted(unitId);
        return idOf(ok(send(ownerToken, post(developmentUrl("/progress")), """
                {"entryDate":"%s","description":"%s"%s}""".formatted(DAY, description, unit))), "rootId");
    }

    private void photo(UUID unitId) throws Exception {
        String unit = unitId == null ? "" : ",\"unitId\":\"%s\"".formatted(unitId);
        UUID mediaId = idOf(ok(send(ownerToken, post(developmentUrl("/media/upload-url")), """
                {"filename":"f.jpg","contentType":"image/jpeg"%s}""".formatted(unit))), "mediaId");
        ok(send(ownerToken, post("/api/media/" + mediaId + "/confirm"), "{}"));
    }

    private void confirmedSpz(UUID unitId) throws Exception {
        UUID captureId = idOf(ok(send(ownerToken, post(developmentUrl("/captures")), """
                {"captureDate":"%s","unitId":"%s","zone":"Frente","filename":"s.spz","fileSize":%d}"""
                .formatted(DAY, unitId, SpzFixtures.TYPICAL_SIZE))), "captureId");
        ok(send(ownerToken, post("/api/captures/" + captureId + "/confirm"), "{}"));
    }

    private void reconstructingVideo(UUID unitId) throws Exception {
        UUID captureId = idOf(ok(send(ownerToken, post(developmentUrl("/captures/from-video")), """
                {"captureDate":"%s","unitId":"%s","zone":"Baño","videoFilename":"b.mp4","videoContentType":"video/mp4"}"""
                .formatted(DAY, unitId))), "captureId");
        ok(send(ownerToken, post("/api/captures/" + captureId + "/confirm-video"), "{}"));
    }

    private UUID readyForReview(String token, UUID unitId) throws Exception {
        UUID extractionId = idOf(ok(send(token, post(developmentUrl("/audio-extractions")), """
                {"audioFilename":"parte.ogg","audioContentType":"audio/ogg","unitId":"%s"}""".formatted(unitId))),
                "extractionId");
        ok(send(token, post("/api/audio-extractions/" + extractionId + "/confirm-audio")));
        audioWorker.processNext();   // TRANSCRIBE
        audioWorker.processNext();   // INTERPRET
        return extractionId;
    }

    private String developmentUrl(String path) {
        return "/api/developments/" + developmentId + path;
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

    private static String ok(ResultActions actions) throws Exception {
        return actions.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static UUID idOf(String body, String field) {
        return UUID.fromString(JSON.readTree(body).get(field).asString());
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    private static List<String> field(JsonNode array, String name) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.get(name).isNull() ? "null" : node.get(name).asString()));
        return values;
    }

    private static List<String> descriptions(JsonNode array) {
        return field(array, "description");
    }

    private static List<String> unitIds(JsonNode array) {
        return field(array, "unitId");
    }

    private static List<String> ids(JsonNode array) {
        return field(array, "id");
    }
}
