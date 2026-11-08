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
import com.obraviva.platform.reports.ReportDtos.GenerateReportRequest;
import com.obraviva.platform.reports.narration.Narration;
import com.obraviva.platform.reports.narration.NarrationException;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica la orquestación de la redacción: el proveedor está mockeado porque lo que
 * se prueba acá es el recorrido por la cola y el desenlace, no la calidad del texto.
 *
 * <p><strong>Las tablas se vacían en cada método.</strong> La cola es FIFO por tipo de
 * trabajo, así que un método que deja un trabajo pendiente hace que el siguiente
 * reclame aquél en vez del suyo. Sin esta limpieza el fallo aparece como un reporte
 * que quedó en PENDING sin explicación, y —peor— los métodos de la ruta de fallo
 * pasan igual, porque su desenlace esperado coincide con el de no haber tocado nada.
 * Por ese mismo motivo cada método verifica cuántas veces se invocó al narrador.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false",
        "obraviva.jobs.max-attempts=2"
})
class ReportNarrationWorkerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

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
        // La cola arranca vacía en cada método: ver el comentario de la clase.
        jobRepository.deleteAll();
        reportRepository.deleteAll();

        String email = "narracion-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(
                new User(email, passwordEncoder.encode(PASSWORD), "Usuario Narracion"));
        token = login(email);

        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateOrganizationRequest("Constructora Narracion"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Torre Narracion", null))))
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

    @Test
    @DisplayName("el reporte redactado espera a una persona y sale de la cola")
    void narratedReportAwaitsHumanApproval() {
        when(narrator.narrate(any())).thenReturn(new Narration(
                "El período cerró con un avance publicado.", "v-test", "modelo-test", "{}"));

        worker.processNext();

        verify(narrator, times(1)).narrate(any());

        Report report = reportRepository.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.READY_FOR_REVIEW);
        assertThat(report.getNarrative()).contains("un avance publicado");
        assertThat(report.getModelId()).isEqualTo("modelo-test");

        ProcessingJob job = jobService.findByResource(reportId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.NEEDS_REVIEW);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.AWAIT_REVIEW);
    }

    @Test
    @DisplayName("un fallo del proveedor devuelve el trabajo a la cola sin perder el snapshot")
    void providerFailureRequeuesWithoutLosingTheSnapshot() {
        when(narrator.narrate(any()))
                .thenThrow(new NarrationException("El proveedor no respondió"));

        worker.processNext();

        verify(narrator, times(1)).narrate(any());

        Report report = reportRepository.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(report.getSourceData()).contains("Torre Narracion");

        ProcessingJob job = jobService.findByResource(reportId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.NARRATE);
        // El intento se contabilizó: es lo que después agota los reintentos.
        assertThat(job.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("agotados los intentos, el reporte queda fallido y no pendiente para siempre")
    void exhaustedRetriesFailTheReport() {
        when(narrator.narrate(any()))
                .thenThrow(new NarrationException("El proveedor no respondió"));

        // max-attempts=2: la segunda toma agota los intentos del paso.
        worker.processNext();
        worker.processNext();

        verify(narrator, times(2)).narrate(any());

        Report report = reportRepository.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.FAILED);

        ProcessingJob job = jobService.findByResource(reportId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    }
}