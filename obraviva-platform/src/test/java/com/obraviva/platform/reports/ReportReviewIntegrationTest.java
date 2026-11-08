package com.obraviva.platform.reports;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.PipelineStep;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobRepository;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
import com.obraviva.platform.progress.CreateProgressEntryRequest;
import com.obraviva.platform.reports.ReportDtos.ApproveReportRequest;
import com.obraviva.platform.reports.ReportDtos.GenerateReportRequest;
import com.obraviva.platform.reports.ReportDtos.RejectReportRequest;
import com.obraviva.platform.reports.narration.Narration;
import com.obraviva.platform.reports.narration.ReportNarrator;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo de revisión humana del reporte de período.
 *
 * <p>Las tablas se vacían en cada método: la cola es FIFO y varios métodos dejan
 * trabajos en distintos estados.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class ReportReviewIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    private static final String MODEL_TEXT = "Texto redactado por el modelo.";

    @MockitoBean private ReportNarrator narrator;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ReportWorker worker;
    @Autowired private ReportRepository reportRepository;
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private ProcessingJobService jobService;

    private String token;
    private UUID developmentId;
    private UUID reportId;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();
        reportRepository.deleteAll();

        when(narrator.narrate(any()))
                .thenReturn(new Narration(MODEL_TEXT, "v-test", "modelo-test", "{}"));

        String email = "revision-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(
                new User(email, passwordEncoder.encode(PASSWORD), "Usuario Revision"));
        token = login(email);

        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateOrganizationRequest("Constructora Revision"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Torre Revision", null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        developmentId = UUID.fromString(JSON.readTree(devResponse).get("id").asString());

        mockMvc.perform(post("/api/developments/" + developmentId + "/progress")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateProgressEntryRequest(
                                null, LocalDate.of(2026, 8, 12), "Se hormigono la losa."))))
                .andExpect(status().isCreated());

        String reportResponse = mockMvc.perform(
                        post("/api/developments/" + developmentId + "/reports")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(new GenerateReportRequest(
                                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        reportId = UUID.fromString(JSON.readTree(reportResponse).get("id").asString());
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

    /** Deja el reporte redactado y esperando decisión. */
    private void narrate() {
        worker.processNext();
    }

    @Test
    @DisplayName("se emite el texto que dejó el revisor, no el del modelo")
    void approvalPublishesTheReviewersText() throws Exception {
        narrate();

        mockMvc.perform(post("/api/reports/" + reportId + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ApproveReportRequest(
                                "Texto corregido por el revisor.", "Ajusté la redacción"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.narrative").value("Texto corregido por el revisor."));

        Report report = reportRepository.findById(reportId).orElseThrow();
        assertThat(report.getReviewedBy()).isNotNull();
        // La versión del modelo no se pierde: queda archivada para trazabilidad.
        assertThat(report.getRawOutput()).isNotNull();
        assertThat(report.getNarrative()).isNotEqualTo(MODEL_TEXT);
    }

    @Test
    @DisplayName("aprobar cierra el trabajo de la cola")
    void approvalClosesTheJob() throws Exception {
        narrate();

        mockMvc.perform(post("/api/reports/" + reportId + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new ApproveReportRequest("Texto final.", null))))
                .andExpect(status().isOk());

        ProcessingJob job = jobService.findByResource(reportId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.DONE);
    }

    @Test
    @DisplayName("rechazar cierra el trabajo como completado, no como fallido")
    void rejectionClosesTheJobAsCompleted() throws Exception {
        narrate();

        mockMvc.perform(post("/api/reports/" + reportId + "/reject")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new RejectReportRequest("No refleja el período"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // Un rechazo es una decisión editorial, no un error del sistema: confundirlos
        // vaciaría de significado al estado FAILED.
        ProcessingJob job = jobService.findByResource(reportId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    @DisplayName("un reporte sin redactar todavía no se puede aprobar")
    void unnarratedReportCannotBeApproved() throws Exception {
        // Sin llamar a narrate(): el reporte sigue en PENDING.
        mockMvc.perform(post("/api/reports/" + reportId + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new ApproveReportRequest("Texto.", null))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("un reporte resuelto no vuelve atrás")
    void resolvedReportCannotBeReviewedAgain() throws Exception {
        narrate();

        mockMvc.perform(post("/api/reports/" + reportId + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new ApproveReportRequest("Texto final.", null))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/reports/" + reportId + "/reject")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new RejectReportRequest(null))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("la bandeja muestra sólo lo que espera decisión")
    void pendingTrayShowsOnlyReportsAwaitingDecision() throws Exception {
        mockMvc.perform(get("/api/reports/pending-review")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        narrate();

        mockMvc.perform(get("/api/reports/pending-review")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(reportId.toString()));

        mockMvc.perform(post("/api/reports/" + reportId + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new ApproveReportRequest("Texto final.", null))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/reports/pending-review")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("no se puede aprobar el reporte de otra organizacion")
    void cannotApproveForeignReport() throws Exception {
        narrate();

        String intruderEmail = "intruso-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(
                new User(intruderEmail, passwordEncoder.encode(PASSWORD), "Usuario Ajeno"));
        String intruderToken = login(intruderEmail);

        mockMvc.perform(post("/api/reports/" + reportId + "/approve")
                        .header("Authorization", "Bearer " + intruderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new ApproveReportRequest("Texto ajeno.", null))))
                .andExpect(status().isNotFound());
    }
}