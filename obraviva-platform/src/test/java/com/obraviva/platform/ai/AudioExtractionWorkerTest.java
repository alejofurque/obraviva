package com.obraviva.platform.ai;

import com.obraviva.platform.ai.AudioDtos.CreateAudioExtractionRequest;
import com.obraviva.platform.ai.extraction.AiExtraction;
import com.obraviva.platform.ai.extraction.AiExtractionRepository;
import com.obraviva.platform.ai.extraction.ExtractionException;
import com.obraviva.platform.ai.extraction.ProgressExtractor;
import com.obraviva.platform.ai.extraction.ReviewStatus;
import com.obraviva.platform.ai.transcription.AudioTranscriber;
import com.obraviva.platform.ai.transcription.Transcription;
import com.obraviva.platform.ai.transcription.TranscriptionException;
import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.JobType;
import com.obraviva.platform.jobs.PipelineStep;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobRepository;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica el desenlace de los fallos técnicos del pipeline de audio: reintento
 * mientras quedan intentos y extracción fallida cuando se agotan.
 *
 * <p>Antes de este test la extracción quedaba en PENDING para siempre: el trabajo
 * moría en FAILED pero nadie marcaba la extracción, y la pantalla de audios seguía
 * consultando un estado que no iba a cambiar. Mismo criterio que
 * {@code ReportNarrationWorkerTest}.
 *
 * <p>Las tablas se vacían en cada método y cada método verifica cuántas veces se
 * invocó al adaptador mockeado: la cola es FIFO por tipo de trabajo, y un método de
 * la ruta de fallo contaminado por otro pasaría igual, porque PENDING es también el
 * estado de no haber tocado nada.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false",
        "obraviva.jobs.max-attempts=2",
        "obraviva.ai.extraction.provider=mock"
})
class AudioExtractionWorkerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @MockitoBean private StorageService storageService;
    @MockitoBean private AudioTranscriber transcriber;
    @MockitoBean private ProgressExtractor progressExtractor;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AudioExtractionWorker worker;
    @Autowired private AiExtractionRepository extractionRepository;
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private ProcessingJobService jobService;
    @Autowired private AudioExtractionService extractionService;

    private UUID extractionId;

    @BeforeEach
    void setUp() throws Exception {
        // La cola arranca vacía en cada método: ver el comentario de la clase.
        jobRepository.deleteAll();
        extractionRepository.deleteAll();

        when(storageService.presignUpload(anyString(), anyString()))
                .thenReturn("http://localhost:9000/fake-upload");
        when(storageService.exists(anyString())).thenReturn(true);

        String email = "audio-worker-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Audio"));
        String token = login(email);

        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateOrganizationRequest("Constructora Audio"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Torre Audio", null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID developmentId = UUID.fromString(JSON.readTree(devResponse).get("id").asString());

        String extractionResponse = mockMvc.perform(
                        post("/api/developments/" + developmentId + "/audio-extractions")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(new CreateAudioExtractionRequest(
                                        "parte.ogg", "audio/ogg"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        extractionId = UUID.fromString(
                JSON.readTree(extractionResponse).get("extractionId").asString());

        mockMvc.perform(post("/api/audio-extractions/" + extractionId + "/confirm-audio")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
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
    @DisplayName("un fallo técnico de transcripción reencola sin marcar la extracción")
    void transcriptionFailureRequeuesWithoutFailingTheExtraction() {
        when(transcriber.transcribe(any()))
                .thenThrow(new TranscriptionException("whisper no respondió"));

        worker.processNext();

        verify(transcriber, times(1)).transcribe(any());

        AiExtraction extraction = extractionRepository.findById(extractionId).orElseThrow();
        assertThat(extraction.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);

        ProcessingJob job = jobService.findByResource(extractionId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.TRANSCRIBE);
        assertThat(job.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("agotados los intentos de transcripción, la extracción queda fallida")
    void exhaustedTranscriptionRetriesFailTheExtraction() {
        when(transcriber.transcribe(any()))
                .thenThrow(new TranscriptionException("whisper no respondió"));

        // max-attempts=2: la segunda toma agota los intentos del paso.
        worker.processNext();
        worker.processNext();

        verify(transcriber, times(2)).transcribe(any());

        AiExtraction extraction = extractionRepository.findById(extractionId).orElseThrow();
        assertThat(extraction.getReviewStatus()).isEqualTo(ReviewStatus.FAILED);
        // El motivo lo lee el revisor en pantalla: mensaje para una persona, sin el
        // detalle técnico, que queda en el trabajo.
        assertThat(extraction.getReviewNotes())
                .contains("varios intentos")
                .doesNotContain("whisper");

        ProcessingJob job = jobService.findByResource(extractionId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getLastError()).contains("whisper no respondió");
    }

    @Test
    @DisplayName("agotados los intentos de interpretación, falla la extracción y se conserva la respuesta cruda")
    void exhaustedInterpretationRetriesFailTheExtractionKeepingRawOutput() {
        when(transcriber.transcribe(any())).thenReturn(new Transcription(
                "Terminamos la losa del segundo piso.", Duration.ofSeconds(5), Duration.ofSeconds(1)));
        when(progressExtractor.extract(any())).thenThrow(new ExtractionException(
                "La respuesta no es JSON válido", "{\"stageProposals\": [", null));

        worker.processNext();   // TRANSCRIBE: completa el paso y reinicia los intentos
        worker.processNext();   // INTERPRET, primera toma
        worker.processNext();   // INTERPRET, segunda toma: agota

        verify(transcriber, times(1)).transcribe(any());
        verify(progressExtractor, times(2)).extract(any());

        AiExtraction extraction = extractionRepository.findById(extractionId).orElseThrow();
        assertThat(extraction.getReviewStatus()).isEqualTo(ReviewStatus.FAILED);
        assertThat(extraction.getTranscript()).contains("losa del segundo piso");
        assertThat(extraction.getRawOutput()).isEqualTo("{\"stageProposals\": [");

        ProcessingJob job = jobService.findByResource(extractionId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.INTERPRET);
    }

    @Test
    @DisplayName("un trabajo ya fallido no vuelve a ser tomado")
    void failedJobIsNotClaimedAgain() {
        when(transcriber.transcribe(any()))
                .thenThrow(new TranscriptionException("whisper no respondió"));

        worker.processNext();
        worker.processNext();
        worker.processNext();   // no hay nada que tomar

        verify(transcriber, times(2)).transcribe(any());
        verify(progressExtractor, never()).extract(any());
    }

    // --- Trabajos interrumpidos ---

    @Test
    @DisplayName("al arrancar, un trabajo de audio que quedó tomado vuelve a la cola y se retoma")
    void interruptedJobIsRequeuedAndResumed() {
        // El worker lo tomó y el proceso se cortó antes de terminar el paso.
        jobService.claimNext(JobType.AUDIO_EXTRACTION, Set.of(PipelineStep.TRANSCRIBE)).orElseThrow();
        assertThat(jobService.findByResource(extractionId).orElseThrow().getStatus()).isEqualTo(JobStatus.PROCESSING);

        assertThat(extractionService.recoverInterruptedJobs()).isEqualTo(1);

        ProcessingJob job = jobService.findByResource(extractionId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getPipelineStep()).isEqualTo(PipelineStep.TRANSCRIBE);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLastError()).contains("interrumpió");
        assertThat(extractionRepository.findById(extractionId).orElseThrow().getReviewStatus())
                .isEqualTo(ReviewStatus.PENDING);

        // Retomado, sigue como cualquier trabajo de la cola.
        when(transcriber.transcribe(any())).thenReturn(new Transcription(
                "Terminamos el obrador.", Duration.ofSeconds(5), Duration.ofSeconds(1)));
        worker.processNext();
        verify(transcriber, times(1)).transcribe(any());
        assertThat(jobService.findByResource(extractionId).orElseThrow().getPipelineStep())
                .isEqualTo(PipelineStep.INTERPRET);
    }

    @Test
    @DisplayName("si el corte fue en el último intento, la extracción pasa a FAILED y no queda pendiente para siempre")
    void interruptedLastAttemptFailsTheExtraction() {
        // max-attempts=2: dos tomas cortadas agotan los intentos del paso.
        jobService.claimNext(JobType.AUDIO_EXTRACTION, Set.of(PipelineStep.TRANSCRIBE)).orElseThrow();
        extractionService.recoverInterruptedJobs();
        jobService.claimNext(JobType.AUDIO_EXTRACTION, Set.of(PipelineStep.TRANSCRIBE)).orElseThrow();
        extractionService.recoverInterruptedJobs();

        assertThat(jobService.findByResource(extractionId).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        AiExtraction extraction = extractionRepository.findById(extractionId).orElseThrow();
        assertThat(extraction.getReviewStatus()).isEqualTo(ReviewStatus.FAILED);
        assertThat(extraction.getReviewNotes()).contains("varios intentos");
        verify(transcriber, never()).transcribe(any());
    }

    @Test
    @DisplayName("sin trabajos tomados, arrancar no toca nada")
    void nothingToRecover() {
        assertThat(extractionService.recoverInterruptedJobs()).isZero();

        ProcessingJob job = jobService.findByResource(extractionId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getAttempts()).isZero();
    }
}
