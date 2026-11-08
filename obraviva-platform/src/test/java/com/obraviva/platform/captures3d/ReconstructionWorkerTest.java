package com.obraviva.platform.captures3d;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureFromVideoRequest;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.PipelineStep;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobRepository;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
import com.obraviva.platform.reconstruction.ReconstructionException;
import com.obraviva.platform.reconstruction.ReconstructionRejectedException;
import com.obraviva.platform.reconstruction.ReconstructionService;
import com.obraviva.platform.reconstruction.ReconstructionStatus;
import com.obraviva.platform.storage.StorageService;
import com.obraviva.platform.video.TranscodeResult;
import com.obraviva.platform.video.VideoMetadata;
import com.obraviva.platform.video.VideoProcessor;
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

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica los tres pasos que dialogan con el servicio externo: envío, espera del
 * resultado y descarga.
 *
 * <p>El servicio de reconstrucción está mockeado: lo que se prueba es la orquestación,
 * no el adaptador, que tiene sus propios tests contra un servidor HTTP local. Ningún
 * test de esta clase toca la red ni consume créditos.
 *
 * <p>Cada método arranca corriendo el worker de preprocesamiento para dejar el trabajo
 * posicionado en SUBMIT. Como los dos workers comparten el mock de
 * {@code StorageService}, las interacciones se limpian al terminar la preparación:
 * sin eso, una verificación sobre el worker de envío contaría también las descargas
 * legítimas del preprocesamiento.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false",
        "obraviva.jobs.max-attempts=3"
})
class ReconstructionWorkerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @MockitoBean
    private StorageService storageService;

    @MockitoBean
    private VideoProcessor videoProcessor;

    @MockitoBean
    private ReconstructionService reconstructionService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private VideoPreprocessingWorker preprocessingWorker;
    @Autowired private ReconstructionWorker reconstructionWorker;
    @Autowired private ProcessingJobService jobService;
    @Autowired private Capture3dRepository captureRepository;
    @Autowired private ProcessingJobRepository jobRepository;

    private String token;
    private UUID developmentId;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();
        captureRepository.deleteAll();

        when(storageService.presignUpload(anyString(), anyString()))
                .thenReturn("http://localhost:9000/fake-upload");
        when(storageService.presignDownload(anyString()))
                .thenReturn("http://localhost:9000/fake-download");
        when(storageService.exists(anyString())).thenReturn(true);
        when(videoProcessor.prepareForReconstruction(any(), any())).thenReturn(fakeResult());

        String email = "reconstruction-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Reconstrucción"));
        token = login(email);

        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateOrganizationRequest("Constructora Reconstrucción"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Obra de reconstrucción", null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        developmentId = UUID.fromString(JSON.readTree(devResponse).get("id").asString());
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

    private static TranscodeResult fakeResult() {
        VideoMetadata source = new VideoMetadata(2160, 3840, 200.0, 400_000_000L, "hevc");
        VideoMetadata output = new VideoMetadata(1080, 1920, 180.0, 90_000_000L, "h264");
        return new TranscodeResult(Path.of("salida.mp4"), source, output, true, true);
    }

    /** Crea la captura, confirma el video y corre el preprocesamiento: queda en SUBMIT. */
    private UUID captureReadyToSubmit() throws Exception {
        String response = mockMvc.perform(
                        post("/api/developments/" + developmentId + "/captures/from-video")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(new CreateCaptureFromVideoRequest(
                                        LocalDate.of(2026, 8, 20), "Avance semanal",
                                        null, null, "obra.mov", "video/quicktime"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID captureId = UUID.fromString(JSON.readTree(response).get("captureId").asString());

        mockMvc.perform(post("/api/captures/" + captureId + "/confirm-video")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        preprocessingWorker.processNext();

        assertThat(jobOf(captureId).getPipelineStep()).isEqualTo(PipelineStep.SUBMIT);

        clearInvocations(storageService);
        return captureId;
    }

    /** Lleva el trabajo hasta AWAIT_EXTERNAL con una referencia del servicio. */
    private UUID captureAwaitingExternal() throws Exception {
        UUID captureId = captureReadyToSubmit();
        when(reconstructionService.submitVideo(any())).thenReturn("kiri-serialize-123");
        reconstructionWorker.processNext();

        assertThat(jobOf(captureId).getPipelineStep()).isEqualTo(PipelineStep.AWAIT_EXTERNAL);
        return captureId;
    }

    private ProcessingJob jobOf(UUID captureId) {
        return jobService.findByResource(captureId).orElseThrow();
    }

    // --- Envío ---

    @Test
    @DisplayName("un envío exitoso guarda la referencia y pasa a esperar el resultado")
    void successfulSubmissionStoresReferenceAndAwaits() throws Exception {
        UUID captureId = captureReadyToSubmit();
        when(reconstructionService.submitVideo(any())).thenReturn("kiri-serialize-123");

        reconstructionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.AWAIT_EXTERNAL);
        assertThat(job.getExternalReference()).isEqualTo("kiri-serialize-123");
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getLastError()).isNull();
    }

    @Test
    @DisplayName("el paso de envío descarga el video normalizado, no el original")
    void submitsTheNormalizedVideo() throws Exception {
        UUID captureId = captureReadyToSubmit();
        when(reconstructionService.submitVideo(any())).thenReturn("kiri-serialize-123");

        reconstructionWorker.processNext();

        Capture3d capture = captureRepository.findById(captureId).orElseThrow();
        verify(storageService).download(eq(capture.getNormalizedVideoStorageKey()), any());
        verify(storageService, never()).download(eq(capture.getVideoStorageKey()), any());
    }

    @Test
    @DisplayName("un rechazo del servicio marca el trabajo como fallido sin reintentar")
    void rejectionFailsWithoutRetry() throws Exception {
        UUID captureId = captureReadyToSubmit();
        when(reconstructionService.submitVideo(any()))
                .thenThrow(new ReconstructionRejectedException(
                        ReconstructionRejectedException.Reason.VIDEO_REJECTED,
                        "El servicio rechazó el video porque no cumple sus requisitos."));

        reconstructionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.SUBMIT);
        assertThat(job.getLastError()).contains("no cumple sus requisitos");
    }

    @Test
    @DisplayName("la falta de créditos es un fallo definitivo, no reintentable")
    void insufficientCreditsIsTerminal() throws Exception {
        UUID captureId = captureReadyToSubmit();
        when(reconstructionService.submitVideo(any()))
                .thenThrow(new ReconstructionRejectedException(
                        ReconstructionRejectedException.Reason.INSUFFICIENT_CREDITS,
                        "No hay créditos suficientes en la cuenta del servicio."));

        reconstructionWorker.processNext();
        reconstructionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getLastError()).contains("créditos suficientes");
        verify(reconstructionService, times(1)).submitVideo(any());
    }

    @Test
    @DisplayName("un fallo técnico en el envío devuelve el trabajo a la cola sin avanzar")
    void technicalFailureRequeuesAtSubmit() throws Exception {
        UUID captureId = captureReadyToSubmit();
        when(reconstructionService.submitVideo(any()))
                .thenThrow(new ReconstructionException("El servicio no responde"));

        reconstructionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.SUBMIT);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLastError()).contains("no responde");
    }

    @Test
    @DisplayName("al agotar los reintentos el envío termina fallido")
    void repeatedTechnicalFailuresExhaustRetries() throws Exception {
        UUID captureId = captureReadyToSubmit();
        when(reconstructionService.submitVideo(any()))
                .thenThrow(new ReconstructionException("El servicio no responde"));

        reconstructionWorker.processNext();
        reconstructionWorker.processNext();
        reconstructionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(3);
        assertThat(job.getLastError()).contains("Se agotaron los reintentos");
    }

    // --- Espera ---

    @Test
    @DisplayName("mientras el servicio trabaja el trabajo espera sin avanzar ni acumular fallos")
    void stillProcessingKeepsWaiting() throws Exception {
        UUID captureId = captureAwaitingExternal();
        when(reconstructionService.checkStatus(anyString()))
                .thenReturn(ReconstructionStatus.PROCESSING);

        reconstructionWorker.processNext();
        reconstructionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.AWAIT_EXTERNAL);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getLastError()).isNull();
        verify(reconstructionService, times(2)).checkStatus(anyString());
    }

    @Test
    @DisplayName("los estados intermedios del servicio no resuelven el trabajo")
    void queuedAndUploadingKeepWaiting() throws Exception {
        UUID captureId = captureAwaitingExternal();
        when(reconstructionService.checkStatus(anyString()))
                .thenReturn(ReconstructionStatus.QUEUED, ReconstructionStatus.UPLOADING);

        reconstructionWorker.processNext();
        reconstructionWorker.processNext();

        assertThat(jobOf(captureId).getPipelineStep()).isEqualTo(PipelineStep.AWAIT_EXTERNAL);
    }

    @Test
    @DisplayName("cuando el servicio termina bien el trabajo avanza a la descarga")
    void successfulReconstructionAdvancesToDownload() throws Exception {
        UUID captureId = captureAwaitingExternal();
        when(reconstructionService.checkStatus(anyString()))
                .thenReturn(ReconstructionStatus.SUCCEEDED);

        reconstructionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.DOWNLOAD);
        assertThat(job.getExternalReference()).isEqualTo("kiri-serialize-123");
    }

    @Test
    @DisplayName("un fallo del servicio es terminal: el crédito ya se gastó")
    void failedReconstructionIsTerminal() throws Exception {
        UUID captureId = captureAwaitingExternal();
        when(reconstructionService.checkStatus(anyString()))
                .thenReturn(ReconstructionStatus.FAILED);

        reconstructionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getLastError()).contains("no pudo procesar el video");
    }

    @Test
    @DisplayName("un resultado vencido es terminal y lo dice con claridad")
    void expiredResultIsTerminal() throws Exception {
        UUID captureId = captureAwaitingExternal();
        when(reconstructionService.checkStatus(anyString()))
                .thenReturn(ReconstructionStatus.EXPIRED);

        reconstructionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getLastError()).contains("venció");
    }

    @Test
    @DisplayName("un fallo técnico en la consulta devuelve el trabajo a la espera")
    void technicalFailureWhilePollingRequeues() throws Exception {
        UUID captureId = captureAwaitingExternal();
        when(reconstructionService.checkStatus(anyString()))
                .thenThrow(new ReconstructionException("Timeout de red"));

        reconstructionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.AWAIT_EXTERNAL);
        assertThat(job.getAttempts()).isEqualTo(1);
    }

    // --- Descarga ---

    @Test
    @DisplayName("el resultado se descarga, se guarda y el trabajo avanza a la conversión")
    void downloadStoresResultAndAdvancesToConvert() throws Exception {
        UUID captureId = captureAwaitingExternal();
        when(reconstructionService.checkStatus(anyString()))
                .thenReturn(ReconstructionStatus.SUCCEEDED);
        reconstructionWorker.processNext();

        reconstructionWorker.processNext();

        String expectedKey =
                "developments/" + developmentId + "/captures/" + captureId + "-result.zip";
        verify(storageService).upload(any(), eq(expectedKey), eq("application/zip"));
        verify(reconstructionService).downloadResult(eq("kiri-serialize-123"), any());

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.CONVERT);

        Capture3d capture = captureRepository.findById(captureId).orElseThrow();
        assertThat(capture.getResultStorageKey()).isEqualTo(expectedKey);
        // El ZIP no pisa la clave del SPZ final, que se escribe en la conversión.
        assertThat(capture.getResultStorageKey()).isNotEqualTo(capture.getStorageKey());
    }

    @Test
    @DisplayName("un resultado que ya no existe en el servicio es terminal")
    void missingResultIsTerminal() throws Exception {
        UUID captureId = captureAwaitingExternal();
        when(reconstructionService.checkStatus(anyString()))
                .thenReturn(ReconstructionStatus.SUCCEEDED);
        reconstructionWorker.processNext();

        org.mockito.Mockito.doThrow(new ReconstructionRejectedException(
                        ReconstructionRejectedException.Reason.EXPIRED,
                        "El resultado venció y fue borrado del servicio."))
                .when(reconstructionService).downloadResult(anyString(), any());

        reconstructionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getLastError()).contains("venció");
        verify(storageService, never()).upload(any(), anyString(), eq("application/zip"));
    }

    @Test
    @DisplayName("un trabajo en conversión ya no lo toma este worker")
    void convertStepIsNotClaimedHere() throws Exception {
        UUID captureId = captureAwaitingExternal();
        when(reconstructionService.checkStatus(anyString()))
                .thenReturn(ReconstructionStatus.SUCCEEDED);
        reconstructionWorker.processNext();
        reconstructionWorker.processNext();

        clearInvocations(reconstructionService, storageService);
        reconstructionWorker.processNext();
        reconstructionWorker.processNext();

        verify(reconstructionService, never()).downloadResult(anyString(), any());
        assertThat(jobOf(captureId).getPipelineStep()).isEqualTo(PipelineStep.CONVERT);
    }
}