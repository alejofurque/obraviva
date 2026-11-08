package com.obraviva.platform.reports;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.captures3d.Capture3d;
import com.obraviva.platform.captures3d.Capture3dRepository;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.developments.CreateStageRequest;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.PipelineStep;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
import com.obraviva.platform.progress.CreateProgressEntryRequest;
import com.obraviva.platform.reports.ReportDtos.GenerateReportRequest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica la composición del snapshot y el ciclo de vida inicial del reporte.
 *
 * <p>Los workers se desactivan a propósito: {@code jobWaitsAtNarrateStep} afirma que
 * el trabajo espera en la cola, y con el worker de redacción activo ese enunciado
 * dependería de si la clase corre antes o después del retraso inicial de arranque.
 */
@SpringBootTest
@AutoConfigureMockMvc // resolver import con quick-fix
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class ReportGenerationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 8, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 8, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private Capture3dRepository captureRepository;
    @Autowired private ProcessingJobService jobService;

    private String token;
    private UUID userId;
    private UUID organizationId;
    private UUID developmentId;
    private UUID stageId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "reports-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(
                new User(email, passwordEncoder.encode(PASSWORD), "Usuario Reportes"));
        userId = user.getId();
        token = login(email);

        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateOrganizationRequest("Constructora Reportes"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Torre Reportes", "Av. Colon 100"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        developmentId = UUID.fromString(JSON.readTree(devResponse).get("id").asString());

        String stageResponse = mockMvc.perform(
                        post("/api/developments/" + developmentId + "/stages")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(
                                        new CreateStageRequest("Estructura", 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        stageId = UUID.fromString(JSON.readTree(stageResponse).get("id").asString());
    }

    private String login(String email) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("accessToken").asString();
    }

    private void createEntry(UUID stage, LocalDate date, String description) throws Exception {
        mockMvc.perform(post("/api/developments/" + developmentId + "/progress")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateProgressEntryRequest(stage, date, description))))
                .andExpect(status().isCreated());
    }

    private void createAvailableCapture(LocalDate date, String description) {
        Capture3d capture = new Capture3d(
                organizationId, developmentId, null, null, date, description, userId);
        capture.markAvailable();
        captureRepository.save(capture);
    }

    private String generate() throws Exception {
        return mockMvc.perform(post("/api/developments/" + developmentId + "/reports")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new GenerateReportRequest(PERIOD_START, PERIOD_END))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("el snapshot incluye sólo los avances del período")
    void snapshotIncludesOnlyEntriesInPeriod() throws Exception {
        createEntry(stageId, LocalDate.of(2026, 8, 12), "Se hormigono la losa del cuarto piso.");
        createEntry(null, LocalDate.of(2026, 8, 20), "Llegaron los canos corrugados.");
        createEntry(stageId, LocalDate.of(2026, 7, 15), "Avance de un periodo anterior.");
        createEntry(stageId, LocalDate.of(2026, 9, 3), "Avance de un periodo posterior.");

        String response = generate();

        assertThat(JSON.readTree(response).get("data").get("progressEntries").size()).isEqualTo(2);
        assertThat(JSON.readTree(response).get("data").get("totals").get("progressEntries").asString())
                .isEqualTo("2");
        assertThat(response).doesNotContain("periodo anterior").doesNotContain("periodo posterior");
    }

    @Test
    @DisplayName("el snapshot nombra las etapas y las capturas disponibles del período")
    void snapshotIncludesStagesAndCaptures() throws Exception {
        createEntry(stageId, LocalDate.of(2026, 8, 12), "Se hormigono la losa.");
        createAvailableCapture(LocalDate.of(2026, 8, 20), "Recorrido del segundo piso");

        String response = generate();

        mockMvc.perform(get("/api/reports/"
                        + JSON.readTree(response).get("id").asString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.narrative").doesNotExist())
                .andExpect(jsonPath("$.data.developmentName").value("Torre Reportes"))
                .andExpect(jsonPath("$.data.stages[0].name").value("Estructura"))
                // El nombre del campo es literal: el modelo de datos no versiona el
                // estado de las etapas, así que esto es el estado al componer.
                .andExpect(jsonPath("$.data.stages[0].statusAtCompositionTime").value("PENDING"))
                .andExpect(jsonPath("$.data.progressEntries[0].stageName").value("Estructura"))
                .andExpect(jsonPath("$.data.captures[0].description")
                        .value("Recorrido del segundo piso"))
                .andExpect(jsonPath("$.data.totals.captures").value(1));
    }

    @Test
    @DisplayName("un periodo sin avances ni capturas se rechaza en vez de encolarse")
    void emptyPeriodIsRejected() throws Exception {
        createEntry(stageId, LocalDate.of(2026, 7, 15), "Avance fuera del periodo.");

        mockMvc.perform(post("/api/developments/" + developmentId + "/reports")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new GenerateReportRequest(PERIOD_START, PERIOD_END))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("un reporte de otra organizacion no existe")
    void reportOfAnotherOrganizationIsNotFound() throws Exception {
        createEntry(stageId, LocalDate.of(2026, 8, 12), "Se hormigono la losa.");
        String reportId = JSON.readTree(generate()).get("id").asString();

        String intruderEmail = "intruso-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(
                new User(intruderEmail, passwordEncoder.encode(PASSWORD), "Usuario Ajeno"));
        String intruderToken = login(intruderEmail);

        mockMvc.perform(get("/api/reports/" + reportId)
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el trabajo queda encolado en la redaccion")
    void jobWaitsAtNarrateStep() throws Exception {
        createEntry(stageId, LocalDate.of(2026, 8, 12), "Se hormigono la losa.");
        UUID reportId = UUID.fromString(JSON.readTree(generate()).get("id").asString());

        Optional<ProcessingJob> job = jobService.findByResource(reportId);

        assertThat(job).isPresent();
        assertThat(job.get().getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.get().getPipelineStep()).isEqualTo(PipelineStep.NARRATE);
    }
}