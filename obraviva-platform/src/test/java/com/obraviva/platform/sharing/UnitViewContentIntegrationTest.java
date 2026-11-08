package com.obraviva.platform.sharing;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.captures3d.Capture3d;
import com.obraviva.platform.captures3d.Capture3dRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Contenido de la vista de una unidad: lo que el comprador ve en "Mi propiedad" y en el
 * enlace público.
 *
 * <p>Cada cifra se verifica contra un cálculo hecho a mano sobre datos conocidos. El
 * avance que ve una familia es el mismo que calcula el panel del desarrollista, y
 * ninguno sale de un modelo de lenguaje.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class UnitViewContentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private Capture3dRepository captureRepository;
    @Autowired private ShareLinkRepository shareLinkRepository;

    private String token;
    private UUID userId;
    private UUID organizationId;
    private UUID developmentId;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        shareLinkRepository.deleteAll();

        when(storageService.presignUpload(anyString(), anyString()))
                .thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString(), any(Duration.class)))
                .thenReturn("https://almacenamiento.test/firmada");
        when(storageService.presignDownload(anyString()))
                .thenReturn("https://almacenamiento.test/firmada");
        when(storageService.exists(anyString())).thenReturn(true);

        String email = "vista-" + UUID.randomUUID() + "@obraviva.test";
        userId = userRepository.save(
                new User(email, passwordEncoder.encode(PASSWORD), "Usuario Vista")).getId();
        token = login(email);

        organizationId = idOf(send(post("/api/organizations"), """
                {"name":"Constructora Vista"}"""));
        developmentId = idOf(send(post("/api/developments"), """
                {"organizationId":"%s","name":"Los Aromos","address":"Córdoba"}""".formatted(organizationId)));
        unitId = idOf(send(post("/api/developments/" + developmentId + "/units"), """
                {"code":"Casa 12","description":"Tres dormitorios"}"""));
    }

    // --- Armado ---

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("accessToken").asString();
    }

    private String send(MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private static UUID idOf(String json) {
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    private UUID stage(String name, int order, int weight, int progress, String status)
            throws Exception {
        UUID stageId = idOf(send(post("/api/developments/" + developmentId + "/stages"), """
                {"name":"%s","orderIndex":%d}""".formatted(name, order)));
        send(patch("/api/stages/" + stageId + "/plan"), """
                {"progressPercent":%d,"weight":%d,"budgetAmount":1500000.00}""".formatted(progress, weight));
        send(patch("/api/developments/stages/" + stageId), """
                {"status":"%s"}""".formatted(status));
        return stageId;
    }

    private UUID photo(String filename, UUID progressEntryId) throws Exception {
        String body = progressEntryId == null
                ? """
                  {"filename":"%s","contentType":"image/jpeg"}""".formatted(filename)
                : """
                  {"filename":"%s","contentType":"image/jpeg","progressEntryId":"%s"}"""
                        .formatted(filename, progressEntryId);
        String response = send(post("/api/developments/" + developmentId + "/media/upload-url"), body);
        UUID mediaId = UUID.fromString(JSON.readTree(response).get("mediaId").asString());
        send(post("/api/media/" + mediaId + "/confirm"), "{}");
        return mediaId;
    }

    private String view() throws Exception {
        return mockMvc.perform(get("/api/my-units/" + unitId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // --- Avance ---

    @Test
    @DisplayName("el avance es la suma ponderada de las etapas, igual que en el panel")
    void overallProgressIsWeighted() throws Exception {
        stage("Fundaciones", 1, 3, 100, "COMPLETED");
        stage("Estructura", 2, 1, 40, "IN_PROGRESS");
        stage("Terminaciones", 3, 1, 0, "PENDING");

        // (100×3 + 40×1 + 0×1) / (3+1+1) = 340 / 5 = 68
        mockMvc.perform(get("/api/my-units/" + unitId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.property.overallProgressPercent").value(68))
                .andExpect(jsonPath("$.property.currentStage.name").value("Estructura"))
                .andExpect(jsonPath("$.property.currentStage.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.property.currentStage.progressPercent").value(40))
                .andExpect(jsonPath("$.property.stages[0].progressPercent").value(100))
                .andExpect(jsonPath("$.property.stages[2].progressPercent").value(0));
    }

    @Test
    @DisplayName("una etapa demorada es la actual antes que una pendiente")
    void delayedStageIsCurrent() throws Exception {
        stage("Fundaciones", 1, 1, 100, "COMPLETED");
        stage("Mampostería", 2, 1, 70, "DELAYED");
        stage("Instalaciones", 3, 1, 0, "PENDING");

        mockMvc.perform(get("/api/my-units/" + unitId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.property.currentStage.name").value("Mampostería"))
                .andExpect(jsonPath("$.property.currentStage.status").value("DELAYED"));
    }

    @Test
    @DisplayName("sin etapas en curso, la actual es la primera pendiente")
    void firstPendingIsCurrentWhenNothingIsRunning() throws Exception {
        stage("Fundaciones", 1, 1, 100, "COMPLETED");
        stage("Estructura", 2, 1, 0, "PENDING");
        stage("Techos", 3, 1, 0, "PENDING");

        mockMvc.perform(get("/api/my-units/" + unitId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.property.currentStage.name").value("Estructura"));
    }

    @Test
    @DisplayName("con todas las etapas completas no hay etapa actual, y sin etapas el avance es cero")
    void noCurrentStageWhenFinishedOrEmpty() throws Exception {
        mockMvc.perform(get("/api/my-units/" + unitId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.property.overallProgressPercent").value(0))
                .andExpect(jsonPath("$.property.currentStage").value(nullValue()));

        stage("Fundaciones", 1, 1, 100, "COMPLETED");
        stage("Estructura", 2, 1, 100, "COMPLETED");

        mockMvc.perform(get("/api/my-units/" + unitId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.property.overallProgressPercent").value(100))
                .andExpect(jsonPath("$.property.currentStage").value(nullValue()));
    }

    // --- Historia ---

    @Test
    @DisplayName("las fotos de un avance siguen en él después de corregirlo")
    void photosSurviveCorrections() throws Exception {
        UUID stageId = stage("Fundaciones", 1, 1, 100, "COMPLETED");
        UUID entryId = idOf(send(post("/api/developments/" + developmentId + "/progress"), """
                {"stageId":"%s","entryDate":"2026-02-10","description":"Se hormigonó la platea."}"""
                .formatted(stageId)));
        photo("platea.jpg", entryId);

        send(post("/api/progress/" + entryId + "/corrections"), """
                {"stageId":"%s","entryDate":"2026-02-10","description":"Se hormigonó la platea completa."}"""
                .formatted(stageId));

        mockMvc.perform(get("/api/my-units/" + unitId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.property.timeline.length()").value(1))
                .andExpect(jsonPath("$.property.timeline[0].description").value("Se hormigonó la platea completa."))
                .andExpect(jsonPath("$.property.timeline[0].corrected").value(true))
                .andExpect(jsonPath("$.property.timeline[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.property.timeline[0].photos.length()").value(1))
                .andExpect(jsonPath("$.property.timeline[0].photos[0].stageName").value("Fundaciones"))
                .andExpect(jsonPath("$.property.timeline[0].photos[0].entryDate").value("2026-02-10"));
    }

    @Test
    @DisplayName("un avance sin corregir ni fotos lo dice, y la galería distingue fotos sueltas")
    void uncorrectedEntryAndLooseGalleryPhoto() throws Exception {
        send(post("/api/developments/" + developmentId + "/progress"), """
                {"entryDate":"2026-03-01","description":"Llegó el hierro."}""");
        photo("obrador.jpg", null);

        mockMvc.perform(get("/api/my-units/" + unitId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.property.timeline[0].corrected").value(false))
                .andExpect(jsonPath("$.property.timeline[0].photos").isEmpty())
                .andExpect(jsonPath("$.property.gallery.length()").value(1))
                .andExpect(jsonPath("$.property.gallery[0].entryDate").value(nullValue()))
                .andExpect(jsonPath("$.property.gallery[0].stageName").value(nullValue()))
                .andExpect(jsonPath("$.property.gallery[0].uploadedAt").isString());
    }

    // --- Recorrido ---

    @Test
    @DisplayName("cada captura informa la etapa que registra")
    void capturesCarryTheirStage() throws Exception {
        UUID stageId = stage("Estructura", 1, 1, 50, "IN_PROGRESS");
        Capture3d capture = new Capture3d(
                organizationId, developmentId, null, stageId,
                LocalDate.of(2026, 6, 15), "Recorrido planta baja", userId);
        capture.markAvailable();
        captureRepository.save(capture);

        mockMvc.perform(get("/api/my-units/" + unitId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.property.captures[0].stageName").value("Estructura"))
                .andExpect(jsonPath("$.property.captures[0].date").value("2026-06-15"));
    }

    // --- Privacidad ---

    @Test
    @DisplayName("el enlace público muestra el avance pero nunca el presupuesto ni identificadores")
    void publicViewHasProgressButNoMoneyOrIds() throws Exception {
        UUID stageId = stage("Estructura", 1, 1, 50, "IN_PROGRESS");
        UUID entryId = idOf(send(post("/api/developments/" + developmentId + "/progress"), """
                {"stageId":"%s","entryDate":"2026-06-01","description":"Losa del primer piso."}"""
                .formatted(stageId)));
        UUID mediaId = photo("losa.jpg", entryId);

        String link = send(post("/api/units/" + unitId + "/share-links"), """
                {"label":"Familia"}""");
        String shareToken = JSON.readTree(link).get("token").asString();

        String body = mockMvc.perform(get("/api/public/share/" + shareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallProgressPercent").value(50))
                .andExpect(jsonPath("$.timeline[0].photos.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("budget")
                .doesNotContain("1500000")
                .doesNotContain(stageId.toString())
                .doesNotContain(entryId.toString())
                .doesNotContain(mediaId.toString())
                .doesNotContain(organizationId.toString());
    }
}
