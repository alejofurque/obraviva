package com.obraviva.platform.developments;

import com.obraviva.platform.developments.DevelopmentMemberRepository;
import com.obraviva.platform.support.SupervisorAssignments;
import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.jobs.JobType;
import com.obraviva.platform.jobs.PipelineStep;
import com.obraviva.platform.jobs.ProcessingJobRepository;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.storage.StorageService;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reglas de la carga diaria de una obra: etapas, avances, fotos y recorridos.
 *
 * <p>Qué hace cada rol. El dueño estructura la obra: crea y nombra etapas y define su
 * peso. Dueño y encargado cargan el día a día: declaran avance y estado, publican avances
 * con fotos, suben videos y corrigen. Borrar lo que ya se publicó es más delicado: el
 * dueño puede con cualquier cosa y el encargado sólo con lo que subió él.
 *
 * <p>Además, todo identificador que llega del navegador se valida contra la obra: una
 * etapa o un avance de otra obra no se puede usar para colgarle contenido.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class DailyWorkRulesIntegrationTest {

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
    @Autowired private DevelopmentMemberRepository assignmentRepository;
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private ProcessingJobService jobService;
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private String memberToken;
    private String otherMemberToken;
    private UUID developmentId;
    private UUID otherDevelopmentId;
    private UUID stageId;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/firmada");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/firmada");
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.size(anyString())).thenReturn(com.obraviva.platform.support.SpzFixtures.TYPICAL_SIZE);
        when(storageService.readPrefix(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(com.obraviva.platform.support.SpzFixtures.valid());

        Organization organization = organizationRepository.save(new Organization("Constructora Diaria"));
        ownerToken = login(user("duenio", organization, OrganizationRole.OWNER));
        memberToken = login(user("encargado", organization, OrganizationRole.MEMBER));
        otherMemberToken = login(user("otro-encargado", organization, OrganizationRole.MEMBER));

        developmentId = idOf(ok(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Los Aromos"}""".formatted(organization.getId())));
        otherDevelopmentId = idOf(ok(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Otra obra"}""".formatted(organization.getId())));
        SupervisorAssignments.assignAllSupervisors(
                memberRepository, assignmentRepository, organization.getId(), developmentId, otherDevelopmentId);
        stageId = idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/stages"), """
                {"name":"Estructura","orderIndex":1}"""));
    }

    // --- Armado ---

    private String user(String prefix, Organization organization, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User saved = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        memberRepository.save(new OrganizationMember(organization, saved.getId(), role));
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

    private ResultActions send(String token, MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String ok(String token, MockHttpServletRequestBuilder builder, String body) throws Exception {
        return send(token, builder, body).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private static UUID idOf(String json) {
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    private UUID entry(String token, String body) throws Exception {
        return idOf(ok(token, post("/api/developments/" + developmentId + "/progress"), body));
    }

    private UUID photo(String token, UUID entryId) throws Exception {
        String response = ok(token, post("/api/developments/" + developmentId + "/media/upload-url"), """
                {"filename":"foto.jpg","contentType":"image/jpeg","progressEntryId":"%s"}""".formatted(entryId));
        UUID mediaId = UUID.fromString(JSON.readTree(response).get("mediaId").asString());
        ok(token, post("/api/media/" + mediaId + "/confirm"), "{}");
        return mediaId;
    }

    // --- Etapas ---

    @Test
    @DisplayName("el dueño renombra una etapa; el encargado no")
    void onlyOwnerRenamesStages() throws Exception {
        send(ownerToken, patch("/api/stages/" + stageId + "/plan"), """
                {"name":"Estructura de hormigón"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Estructura de hormigón"));

        send(memberToken, patch("/api/stages/" + stageId + "/plan"), """
                {"name":"Otro nombre"}""").andExpect(status().isForbidden());
        send(ownerToken, patch("/api/stages/" + stageId + "/plan"), """
                {"name":"   "}""").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("marcar una etapa como completada lleva su avance a 100")
    void completingStageSetsFullProgress() throws Exception {
        ok(memberToken, patch("/api/stages/" + stageId + "/plan"), """
                {"progressPercent":40}""");

        send(memberToken, patch("/api/developments/stages/" + stageId), """
                {"status":"COMPLETED"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.progressPercent").value(100));
    }

    // --- Avances ---

    @Test
    @DisplayName("un avance o su corrección no pueden usar la etapa de otra obra")
    void progressRejectsForeignStage() throws Exception {
        UUID foreignStage = idOf(ok(ownerToken, post("/api/developments/" + otherDevelopmentId + "/stages"), """
                {"name":"Techos","orderIndex":1}"""));

        send(memberToken, post("/api/developments/" + developmentId + "/progress"), """
                {"stageId":"%s","entryDate":"2026-09-10","description":"Avance"}""".formatted(foreignStage))
                .andExpect(status().isBadRequest());

        UUID entryId = entry(memberToken, """
                {"stageId":"%s","entryDate":"2026-09-10","description":"Avance"}""".formatted(stageId));
        send(memberToken, post("/api/progress/" + entryId + "/corrections"), """
                {"stageId":"%s","entryDate":"2026-09-10","description":"Corregido"}""".formatted(foreignStage))
                .andExpect(status().isBadRequest());
    }

    // --- Fotos ---

    @Test
    @DisplayName("una foto no se puede colgar de un avance de otra obra")
    void photoRejectsForeignEntry() throws Exception {
        UUID foreignEntry = idOf(ok(ownerToken, post("/api/developments/" + otherDevelopmentId + "/progress"), """
                {"entryDate":"2026-09-10","description":"De la otra obra"}"""));

        send(memberToken, post("/api/developments/" + developmentId + "/media/upload-url"), """
                {"filename":"foto.jpg","contentType":"image/jpeg","progressEntryId":"%s"}""".formatted(foreignEntry))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("el listado de medios informa el avance raíz, aunque la foto sea de una versión anterior")
    void mediaListReportsRootEntry() throws Exception {
        UUID entryId = entry(memberToken, """
                {"entryDate":"2026-09-10","description":"Losa"}""");
        photo(memberToken, entryId);
        ok(memberToken, post("/api/progress/" + entryId + "/corrections"), """
                {"entryDate":"2026-09-10","description":"Losa del primer piso"}""");

        mockMvc.perform(get("/api/developments/" + developmentId + "/media").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$[0].progressEntryId").value(entryId.toString()))
                .andExpect(jsonPath("$[0].progressRootId").value(entryId.toString()))
                .andExpect(jsonPath("$[0].mine").value(false));
        mockMvc.perform(get("/api/developments/" + developmentId + "/media").header("Authorization", "Bearer " + memberToken))
                .andExpect(jsonPath("$[0].mine").value(true));
    }

    @Test
    @DisplayName("el encargado borra sus fotos y no las de otros; el dueño borra cualquiera")
    void photoDeletionByRole() throws Exception {
        UUID entryId = entry(memberToken, """
                {"entryDate":"2026-09-10","description":"Losa"}""");
        UUID mine = photo(memberToken, entryId);
        UUID others = photo(otherMemberToken, entryId);
        UUID forOwner = photo(memberToken, entryId);

        send(memberToken, delete("/api/media/" + others), "").andExpect(status().isForbidden());
        send(memberToken, delete("/api/media/" + mine), "").andExpect(status().isNoContent());
        send(ownerToken, delete("/api/media/" + forOwner), "").andExpect(status().isNoContent());

        mockMvc.perform(get("/api/developments/" + developmentId + "/media").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("fuera de la organización una foto no existe")
    void outsiderCannotDeletePhoto() throws Exception {
        UUID entryId = entry(memberToken, """
                {"entryDate":"2026-09-10","description":"Losa"}""");
        UUID mediaId = photo(memberToken, entryId);
        Organization other = organizationRepository.save(new Organization("Ajena"));
        String outsider = login(user("ajeno", other, OrganizationRole.OWNER));

        send(outsider, delete("/api/media/" + mediaId), "").andExpect(status().isNotFound());
    }

    // --- Recorridos ---

    private UUID video(String token) throws Exception {
        String response = ok(token, post("/api/developments/" + developmentId + "/captures/from-video"), """
                {"captureDate":"2026-09-10","videoFilename":"recorrido.mp4","videoContentType":"video/mp4"}""");
        return UUID.fromString(JSON.readTree(response).get("captureId").asString());
    }

    @Test
    @DisplayName("un recorrido fallido se descarta: el encargado el suyo, el dueño cualquiera")
    void failedCaptureDiscardByRole() throws Exception {
        UUID mine = video(memberToken);
        UUID others = video(otherMemberToken);

        mockMvc.perform(get("/api/developments/" + developmentId + "/captures/processing")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(jsonPath("$[?(@.captureId == '" + mine + "')].mine").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$[?(@.captureId == '" + others + "')].mine").value(org.hamcrest.Matchers.contains(false)));

        send(memberToken, delete("/api/captures/" + others), "").andExpect(status().isForbidden());
        send(memberToken, delete("/api/captures/" + mine), "").andExpect(status().isNoContent());
        send(ownerToken, delete("/api/captures/" + others), "").andExpect(status().isNoContent());

        mockMvc.perform(get("/api/developments/" + developmentId + "/captures/processing")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("un recorrido que se está procesando o ya está disponible no se descarta")
    void activeOrAvailableCaptureIsNotDiscarded() throws Exception {
        UUID processing = video(memberToken);
        ok(memberToken, post("/api/captures/" + processing + "/confirm-video"), "{}");
        jobService.claimNext(JobType.CAPTURE_3D_RECONSTRUCTION, Set.of(PipelineStep.PREPROCESS));

        send(ownerToken, delete("/api/captures/" + processing), "").andExpect(status().isConflict());

        UUID manual = UUID.fromString(JSON.readTree(ok(ownerToken, post("/api/developments/" + developmentId + "/captures"), """
                {"captureDate":"2026-09-10"}""")).get("captureId").asString());
        ok(ownerToken, post("/api/captures/" + manual + "/confirm"), "{}");
        send(ownerToken, delete("/api/captures/" + manual), "").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("un recorrido fallido del worker también se descarta")
    void workerFailedCaptureIsDiscarded() throws Exception {
        UUID captureId = video(memberToken);
        ok(memberToken, post("/api/captures/" + captureId + "/confirm-video"), "{}");
        UUID jobId = jobService.claimNext(JobType.CAPTURE_3D_RECONSTRUCTION, Set.of(PipelineStep.PREPROCESS))
                .orElseThrow().getId();
        jobService.fail(jobId, "El video es demasiado corto");

        send(memberToken, delete("/api/captures/" + captureId), "").andExpect(status().isNoContent());
    }

    // --- Modo de reconstrucción ---

    @Test
    @DisplayName("la interfaz puede saber si subir un video consume créditos del servicio real")
    void reconstructionModeIsExposed() throws Exception {
        mockMvc.perform(get("/api/reconstruction/mode").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true));
    }

    @Test
    @DisplayName("los créditos de reconstrucción los ve sólo el dueño, y en modo simulado no hay número")
    void creditsAreForTheOwner() throws Exception {
        mockMvc.perform(get("/api/reconstruction/credits").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.credits").doesNotExist());
        mockMvc.perform(get("/api/reconstruction/credits").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }
}
