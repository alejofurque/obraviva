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
import com.obraviva.platform.storage.StorageException;
import com.obraviva.platform.storage.StorageService;
import com.obraviva.platform.video.TranscodeResult;
import com.obraviva.platform.video.VideoMetadata;
import com.obraviva.platform.video.VideoProcessor;
import com.obraviva.platform.video.VideoValidationException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica la orquestación del worker: toma de trabajos, avance de pasos y ruteo de
 * las dos familias de error.
 *
 * <p>El almacenamiento y el preprocesador están mockeados a propósito. Lo que se
 * prueba acá es la lógica del worker, no ffmpeg ni MinIO, que tienen sus propios
 * tests contra las herramientas reales.
 *
 * <p>La cola se vacía antes de cada método. Sin eso, un método que deja un trabajo en
 * PENDING a propósito contamina al siguiente: la cola es FIFO y el reclamo toma el
 * pendiente más antiguo. El contenedor de Postgres es estático, así que la
 * contaminación es entre métodos de esta clase, no entre clases.
 *
 * <p>El acceso directo a {@code ProcessingJobRepository} desde el módulo de capturas
 * es una excepción deliberada y limitada a este test: es limpieza de infraestructura,
 * no lógica de dominio.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false",
        "obraviva.jobs.max-attempts=3"
})
class VideoPreprocessingWorkerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @MockitoBean
    private StorageService storageService;

    @MockitoBean
    private VideoProcessor videoProcessor;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private VideoPreprocessingWorker worker;
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

        String email = "worker-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Worker"));
        token = login(email);

        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateOrganizationRequest("Constructora Worker"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Obra del worker", null))))
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

    /** Deja una captura con su video subido y el trabajo en PENDING, paso PREPROCESS. */
    private UUID queueCaptureFromVideo() throws Exception {
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

        return captureId;
    }

    private TranscodeResult fakeResult() {
        VideoMetadata source = new VideoMetadata(2160, 3840, 200.0, 400_000_000L, "hevc");
        VideoMetadata output = new VideoMetadata(1080, 1920, 180.0, 90_000_000L, "h264");
        return new TranscodeResult(Path.of("salida.mp4"), source, output, true, true);
    }

    private ProcessingJob jobOf(UUID captureId) {
        return jobService.findByResource(captureId).orElseThrow();
    }

    @Test
    @DisplayName("un video válido se normaliza, se sube y el trabajo avanza al paso de envío")
    void successfulPreprocessingAdvancesToSubmitStep() throws Exception {
        UUID captureId = queueCaptureFromVideo();
        when(videoProcessor.prepareForReconstruction(any(), any())).thenReturn(fakeResult());

        worker.processNext();

        String expectedKey =
                "developments/" + developmentId + "/videos/" + captureId + "-normalized.mp4";
        verify(storageService).upload(any(), eq(expectedKey), eq("video/mp4"));

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.SUBMIT);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getLastError()).isNull();

        Capture3d capture = captureRepository.findById(captureId).orElseThrow();
        assertThat(capture.getNormalizedVideoStorageKey()).isEqualTo(expectedKey);
        assertThat(capture.getVideoStorageKey()).isNotEqualTo(expectedKey);
    }

    @Test
    @DisplayName("un trabajo en un paso no soportado no se reclama")
    void jobAtUnsupportedStepIsNotClaimed() throws Exception {
        UUID captureId = queueCaptureFromVideo();
        when(videoProcessor.prepareForReconstruction(any(), any())).thenReturn(fakeResult());

        worker.processNext();
        // El trabajo quedó en SUBMIT, paso que este worker todavía no atiende.
        worker.processNext();
        worker.processNext();

        verify(storageService, times(1)).download(anyString(), any());

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.SUBMIT);
        assertThat(job.getAttempts()).isZero();
    }

    @Test
    @DisplayName("un video rechazado marca el trabajo como fallido sin reintentar")
    void rejectedVideoFailsWithoutRetry() throws Exception {
        UUID captureId = queueCaptureFromVideo();
        when(videoProcessor.prepareForReconstruction(any(), any()))
                .thenThrow(new VideoValidationException(
                        VideoValidationException.Reason.TOO_SHORT,
                        "El video dura 5 segundos y no alcanza para reconstruir el ambiente."));

        worker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.PREPROCESS);
        assertThat(job.getLastError()).contains("no alcanza para reconstruir");

        verify(storageService, never()).upload(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("un fallo técnico devuelve el trabajo a la cola sin avanzar de paso")
    void technicalFailureRequeuesAtSameStep() throws Exception {
        UUID captureId = queueCaptureFromVideo();
        doThrow(new StorageException("MinIO no responde"))
                .when(storageService).download(anyString(), any());

        worker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.PREPROCESS);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLastError()).contains("MinIO no responde");
    }

    @Test
    @DisplayName("al agotar los reintentos el trabajo termina fallido")
    void repeatedTechnicalFailuresExhaustRetries() throws Exception {
        UUID captureId = queueCaptureFromVideo();
        doThrow(new StorageException("MinIO no responde"))
                .when(storageService).download(anyString(), any());

        worker.processNext();
        worker.processNext();
        worker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(3);
        assertThat(job.getLastError()).contains("Se agotaron los reintentos");
    }

    @Test
    @DisplayName("sin trabajos pendientes el worker no hace nada")
    void idleWorkerDoesNothing() {
        worker.processNext();

        verify(storageService, never()).download(anyString(), any());
        verify(storageService, never()).upload(any(), anyString(), anyString());
    }
}