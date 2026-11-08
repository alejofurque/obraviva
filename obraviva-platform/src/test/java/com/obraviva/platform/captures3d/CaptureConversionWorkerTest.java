package com.obraviva.platform.captures3d;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureFromVideoRequest;
import com.obraviva.platform.conversion.ConversionResult;
import com.obraviva.platform.conversion.InvalidReconstructionArchiveException;
import com.obraviva.platform.conversion.SplatConversionException;
import com.obraviva.platform.conversion.SplatConverter;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.PipelineStep;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobRepository;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
import com.obraviva.platform.reconstruction.ReconstructionService;
import com.obraviva.platform.reconstruction.ReconstructionStatus;
import com.obraviva.platform.storage.StorageException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica el último paso del pipeline: conversión al formato de distribución y
 * publicación de la captura.
 *
 * <p>El convertidor y el almacenamiento están mockeados: lo que se prueba es la
 * orquestación, no gsbox, que tiene sus propios tests contra la herramienta real.
 *
 * <p>Cada método arranca recorriendo el pipeline completo con los workers previos
 * para dejar el trabajo posicionado en CONVERT. Eso ejercita de paso el recorrido
 * entero, que es lo que finalmente importa.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false",
        "obraviva.jobs.max-attempts=3"
})
class CaptureConversionWorkerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @MockitoBean private StorageService storageService;
    @MockitoBean private VideoProcessor videoProcessor;
    @MockitoBean private ReconstructionService reconstructionService;
    @MockitoBean private SplatConverter splatConverter;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private VideoPreprocessingWorker preprocessingWorker;
    @Autowired private ReconstructionWorker reconstructionWorker;
    @Autowired private CaptureConversionWorker conversionWorker;
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
        when(videoProcessor.prepareForReconstruction(any(), any())).thenReturn(fakeTranscode());
        when(reconstructionService.submitVideo(any())).thenReturn("mock-ref-123");
        when(reconstructionService.checkStatus(anyString()))
                .thenReturn(ReconstructionStatus.SUCCEEDED);
        when(splatConverter.extractModel(any(), any())).thenReturn(Path.of("model.ply"));
        when(splatConverter.convertToSpz(any(), any())).thenReturn(fakeConversion());

        String email = "conversion-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Conversión"));
        token = login(email);

        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateOrganizationRequest("Constructora Conversión"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Obra de conversión", null))))
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

    private static TranscodeResult fakeTranscode() {
        VideoMetadata source = new VideoMetadata(2160, 3840, 200.0, 400_000_000L, "hevc");
        VideoMetadata output = new VideoMetadata(1080, 1920, 180.0, 90_000_000L, "h264");
        return new TranscodeResult(Path.of("salida.mp4"), source, output, true, true);
    }

    private static ConversionResult fakeConversion() {
        return new ConversionResult(Path.of("model.spz"), 97_000_000L, 6_700_000L);
    }

    /** Recorre el pipeline hasta dejar el trabajo posicionado en CONVERT. */
    private UUID captureReadyToConvert() throws Exception {
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

        preprocessingWorker.processNext();   // PREPROCESS -> SUBMIT
        reconstructionWorker.processNext();  // SUBMIT -> AWAIT_EXTERNAL
        reconstructionWorker.processNext();  // AWAIT_EXTERNAL -> DOWNLOAD
        reconstructionWorker.processNext();  // DOWNLOAD -> CONVERT

        assertThat(jobOf(captureId).getPipelineStep()).isEqualTo(PipelineStep.CONVERT);

        clearInvocations(storageService);
        return captureId;
    }

    private ProcessingJob jobOf(UUID captureId) {
        return jobService.findByResource(captureId).orElseThrow();
    }

    @Test
    @DisplayName("la conversión sube el SPZ, publica la captura y cierra el trabajo")
    void successfulConversionPublishesCapture() throws Exception {
        UUID captureId = captureReadyToConvert();

        conversionWorker.processNext();

        Capture3d capture = captureRepository.findById(captureId).orElseThrow();
        verify(storageService).upload(any(), eq(capture.getStorageKey()), anyString());

        assertThat(capture.getStatus()).isEqualTo(CaptureStatus.AVAILABLE);
        assertThat(capture.getConfirmedAt()).isNotNull();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.DONE);
        assertThat(job.getLastError()).isNull();
    }

    @Test
    @DisplayName("el SPZ se sube a la clave que el visor consulta, no a una nueva")
    void spzGoesToTheKeyTheViewerReads() throws Exception {
        UUID captureId = captureReadyToConvert();
        Capture3d before = captureRepository.findById(captureId).orElseThrow();
        String expectedKey =
                "developments/" + developmentId + "/captures/" + captureId + ".spz";

        conversionWorker.processNext();

        // La clave existe desde la creación de la captura: convertir es llenar ese
        // lugar reservado, no inventar uno nuevo.
        assertThat(before.getStorageKey()).isEqualTo(expectedKey);
        verify(storageService).upload(any(), eq(expectedKey), anyString());
    }

    @Test
    @DisplayName("se descarga el resultado comprimido, no el video")
    void downloadsTheArchiveNotTheVideo() throws Exception {
        UUID captureId = captureReadyToConvert();
        Capture3d capture = captureRepository.findById(captureId).orElseThrow();

        conversionWorker.processNext();

        verify(storageService).download(eq(capture.getResultStorageKey()), any());
        verify(storageService, never()).download(eq(capture.getVideoStorageKey()), any());
        verify(storageService, never())
                .download(eq(capture.getNormalizedVideoStorageKey()), any());
    }

    @Test
    @DisplayName("un resultado sin modelo dentro marca el trabajo como fallido sin reintentar")
    void archiveWithoutModelFailsWithoutRetry() throws Exception {
        UUID captureId = captureReadyToConvert();
        when(splatConverter.extractModel(any(), any()))
                .thenThrow(new InvalidReconstructionArchiveException(
                        InvalidReconstructionArchiveException.Reason.NO_MODEL_FOUND,
                        "El archivo no contiene ningún modelo PLY."));

        conversionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getLastError()).contains("no contiene ningún modelo");

        Capture3d capture = captureRepository.findById(captureId).orElseThrow();
        assertThat(capture.getStatus()).isEqualTo(CaptureStatus.PENDING);
        verify(storageService, never()).upload(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("un fallo de la herramienta devuelve el trabajo a la cola sin publicar")
    void converterFailureRequeuesWithoutPublishing() throws Exception {
        UUID captureId = captureReadyToConvert();
        when(splatConverter.convertToSpz(any(), any()))
                .thenThrow(new SplatConversionException("gsbox no está disponible"));

        conversionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.CONVERT);
        assertThat(job.getAttempts()).isEqualTo(1);

        Capture3d capture = captureRepository.findById(captureId).orElseThrow();
        assertThat(capture.getStatus()).isEqualTo(CaptureStatus.PENDING);
    }

    @Test
    @DisplayName("si la subida falla la captura no queda publicada")
    void uploadFailureDoesNotPublishCapture() throws Exception {
        UUID captureId = captureReadyToConvert();
        doThrow(new StorageException("MinIO no responde"))
                .when(storageService).upload(any(), anyString(), anyString());

        conversionWorker.processNext();

        // Publicar una captura cuyo archivo no llegó al almacenamiento dejaría un
        // recorrido roto en el visor.
        Capture3d capture = captureRepository.findById(captureId).orElseThrow();
        assertThat(capture.getStatus()).isEqualTo(CaptureStatus.PENDING);
        assertThat(jobOf(captureId).getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    @DisplayName("un trabajo terminado no se vuelve a procesar")
    void completedJobIsNotReprocessed() throws Exception {
        UUID captureId = captureReadyToConvert();

        conversionWorker.processNext();
        clearInvocations(splatConverter, storageService);
        conversionWorker.processNext();
        conversionWorker.processNext();

        verify(splatConverter, never()).convertToSpz(any(), any());
        assertThat(jobOf(captureId).getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    @DisplayName("al agotar los reintentos la conversión termina fallida")
    void repeatedFailuresExhaustRetries() throws Exception {
        UUID captureId = captureReadyToConvert();
        when(splatConverter.convertToSpz(any(), any()))
                .thenThrow(new SplatConversionException("gsbox no está disponible"));

        conversionWorker.processNext();
        conversionWorker.processNext();
        conversionWorker.processNext();

        ProcessingJob job = jobOf(captureId);
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(3);
        verify(splatConverter, times(3)).convertToSpz(any(), any());
    }
}