package com.obraviva.platform.captures3d;

import com.obraviva.platform.developments.DevelopmentMemberRepository;
import com.obraviva.platform.support.SupervisorAssignments;
import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
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
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Datos descriptivos de una captura: ambiente, fecha, descripción y etapa.
 *
 * <p>El ambiente alimenta el selector del recorrido. La etapa y la unidad que manda el
 * cliente se validan contra la obra: lo que llega del navegador no es confiable.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class CaptureDetailsIntegrationTest {

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
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private String memberToken;
    private Organization organization;
    private UUID developmentId;
    private UUID otherDevelopmentId;
    private UUID stageId;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/firmada");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/firmada");
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.size(anyString())).thenReturn(com.obraviva.platform.support.SpzFixtures.TYPICAL_SIZE);
        when(storageService.readPrefix(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(com.obraviva.platform.support.SpzFixtures.valid());

        organization = organizationRepository.save(new Organization("Constructora Ambientes"));
        ownerToken = login(user("duenio", organization, OrganizationRole.OWNER));
        memberToken = login(user("encargado", organization, OrganizationRole.MEMBER));

        developmentId = idOf(ok(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Casa Los Aromos"}""".formatted(organization.getId())));
        otherDevelopmentId = idOf(ok(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Otra obra"}""".formatted(organization.getId())));
        SupervisorAssignments.assignAllSupervisors(
                memberRepository, assignmentRepository, organization.getId(), developmentId, otherDevelopmentId);
        stageId = idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/stages"), """
                {"name":"Instalaciones","orderIndex":1}"""));
        unitId = idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/units"), """
                {"code":"Casa 12"}"""));
    }

    // --- Armado ---

    private String user(String prefix, Organization org, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User saved = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        if (org != null) {
            memberRepository.save(new OrganizationMember(org, saved.getId(), role));
        }
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

    private UUID manualCapture(String zone) throws Exception {
        String body = zone == null
                ? """
                  {"captureDate":"2026-09-10","description":"Recorrido","stageId":"%s"}""".formatted(stageId)
                : """
                  {"captureDate":"2026-09-10","description":"Recorrido","stageId":"%s","zone":"%s"}"""
                        .formatted(stageId, zone);
        UUID captureId = UUID.fromString(JSON.readTree(
                ok(ownerToken, post("/api/developments/" + developmentId + "/captures"), body))
                .get("captureId").asString());
        ok(ownerToken, post("/api/captures/" + captureId + "/confirm"), "{}");
        return captureId;
    }

    // --- Ambiente ---

    @Test
    @DisplayName("el ambiente se carga al subir y llega al listado y al recorrido de la unidad")
    void zoneIsStoredAndExposed() throws Exception {
        manualCapture("Living + Cocina");

        mockMvc.perform(get("/api/developments/" + developmentId + "/captures")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$[0].zone").value("Living + Cocina"));

        mockMvc.perform(get("/api/my-units/" + unitId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.property.captures[0].zone").value("Living + Cocina"))
                .andExpect(jsonPath("$.property.captures[0].stageName").value("Instalaciones"));
    }

    @Test
    @DisplayName("el ambiente también se carga al subir un video, y se ve mientras se procesa")
    void zoneTravelsWithVideoUpload() throws Exception {
        ok(ownerToken, post("/api/developments/" + developmentId + "/captures/from-video"), """
                {"captureDate":"2026-09-10","videoFilename":"cocina.mp4","videoContentType":"video/mp4",
                 "zone":"Dormitorio principal"}""");

        mockMvc.perform(get("/api/developments/" + developmentId + "/captures/processing")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$[0].zone").value("Dormitorio principal"));
    }

    @Test
    @DisplayName("un ambiente en blanco se guarda como vacío")
    void blankZoneIsNull() throws Exception {
        manualCapture("   ");

        mockMvc.perform(get("/api/developments/" + developmentId + "/captures")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$[0].zone").value(nullValue()));
    }

    // --- Edición ---

    @Test
    @DisplayName("el encargado corrige fecha, descripción, ambiente y etapa de una captura existente")
    void memberUpdatesCaptureDetails() throws Exception {
        UUID captureId = manualCapture(null);

        send(memberToken, patch("/api/captures/" + captureId), """
                {"captureDate":"2026-09-12","description":"Recorrido corregido","zone":"Galería","stageId":null}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captureDate").value("2026-09-12"))
                .andExpect(jsonPath("$.description").value("Recorrido corregido"))
                .andExpect(jsonPath("$.zone").value("Galería"))
                .andExpect(jsonPath("$.stageId").value(nullValue()));
    }

    @Test
    @DisplayName("la fecha es obligatoria al corregir")
    void dateIsRequiredOnUpdate() throws Exception {
        UUID captureId = manualCapture(null);

        send(ownerToken, patch("/api/captures/" + captureId), """
                {"zone":"Galería"}""").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("fuera de la organización la captura no existe")
    void outsiderCannotUpdate() throws Exception {
        UUID captureId = manualCapture(null);
        Organization other = organizationRepository.save(new Organization("Ajena"));
        String outsiderToken = login(user("ajeno", other, OrganizationRole.OWNER));

        send(outsiderToken, patch("/api/captures/" + captureId), """
                {"captureDate":"2026-09-12"}""").andExpect(status().isNotFound());
    }

    // --- Referencias ---

    @Test
    @DisplayName("una etapa o unidad de otra obra se rechaza al crear y al corregir")
    void foreignReferencesAreRejected() throws Exception {
        UUID foreignStage = idOf(ok(ownerToken, post("/api/developments/" + otherDevelopmentId + "/stages"), """
                {"name":"Techos","orderIndex":1}"""));
        UUID foreignUnit = idOf(ok(ownerToken, post("/api/developments/" + otherDevelopmentId + "/units"), """
                {"code":"Casa 1"}"""));

        send(ownerToken, post("/api/developments/" + developmentId + "/captures"), """
                {"captureDate":"2026-09-10","stageId":"%s"}""".formatted(foreignStage))
                .andExpect(status().isBadRequest());
        send(ownerToken, post("/api/developments/" + developmentId + "/captures"), """
                {"captureDate":"2026-09-10","unitId":"%s"}""".formatted(foreignUnit))
                .andExpect(status().isBadRequest());
        send(ownerToken, post("/api/developments/" + developmentId + "/captures/from-video"), """
                {"captureDate":"2026-09-10","stageId":"%s","videoFilename":"a.mp4","videoContentType":"video/mp4"}"""
                .formatted(foreignStage))
                .andExpect(status().isBadRequest());

        UUID captureId = manualCapture(null);
        send(ownerToken, patch("/api/captures/" + captureId), """
                {"captureDate":"2026-09-12","stageId":"%s"}""".formatted(foreignStage))
                .andExpect(status().isBadRequest());
    }
}
