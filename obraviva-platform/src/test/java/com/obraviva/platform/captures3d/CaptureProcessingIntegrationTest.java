package com.obraviva.platform.captures3d;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureFromVideoRequest;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureRequest;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.JobType;
import com.obraviva.platform.jobs.PipelineStep;
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

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica el listado de capturas en proceso: lo que la pantalla de subida consulta
 * para mostrar en qué punto del pipeline está cada video.
 *
 * <p>Los workers están apagados porque los tests afirman estados de la cola; con los
 * workers activos, un trabajo podría avanzar entre la acción y la consulta.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class CaptureProcessingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private ProcessingJobService jobService;
    @Autowired private Capture3dRepository captureRepository;

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

        token = registerAndLogin("proceso");
        developmentId = createDevelopment(token, "Obra en proceso");
    }

    // --- Armado ---

    private String registerAndLogin(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("accessToken").asString();
    }

    private UUID createDevelopment(String userToken, String name) throws Exception {
        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateOrganizationRequest("Constructora " + name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateDevelopmentRequest(organizationId, name, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(devResponse).get("id").asString());
    }

    private UUID uploadVideo(String description) throws Exception {
        String response = mockMvc.perform(
                        post("/api/developments/" + developmentId + "/captures/from-video")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(new CreateCaptureFromVideoRequest(
                                        LocalDate.of(2026, 9, 1), description, null, null,
                                        "recorrido.mp4", "video/mp4"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(response).get("captureId").asString());
    }

    private void confirmVideo(UUID captureId) throws Exception {
        mockMvc.perform(post("/api/captures/" + captureId + "/confirm-video")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String processingUrl() {
        return "/api/developments/" + developmentId + "/captures/processing";
    }

    // --- Tests ---

    @Test
    @DisplayName("un video registrado pero sin confirmar figura esperando la subida")
    void registeredVideoAwaitsUpload() throws Exception {
        UUID captureId = uploadVideo("Recorrido planta baja");

        mockMvc.perform(get(processingUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].captureId").value(captureId.toString()))
                .andExpect(jsonPath("$[0].description").value("Recorrido planta baja"))
                .andExpect(jsonPath("$[0].captureDate").value("2026-09-01"))
                .andExpect(jsonPath("$[0].jobStatus").value("UPLOADED"))
                .andExpect(jsonPath("$[0].pipelineStep").value("PREPROCESS"))
                .andExpect(jsonPath("$[0].failureReason").value(nullValue()));
    }

    @Test
    @DisplayName("un video confirmado queda en cola para el preprocesamiento")
    void confirmedVideoIsQueued() throws Exception {
        UUID captureId = uploadVideo("Recorrido confirmado");
        confirmVideo(captureId);

        mockMvc.perform(get(processingUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].pipelineStep").value("PREPROCESS"))
                // Texto ISO y no número: el frontend tipa estos campos como string.
                .andExpect(jsonPath("$[0].stepStartedAt").isString())
                .andExpect(jsonPath("$[0].createdAt").isString());
    }

    /**
     * Toma el trabajo como lo haría el worker de preprocesamiento. Los desenlaces se
     * registran sobre un trabajo en proceso: llamarlos sobre uno recién encolado es un
     * camino que el sistema real no recorre, y la máquina de estados lo rechaza.
     */
    private UUID claimPreprocessing(UUID captureId) {
        UUID jobId = jobService
                .claimNext(JobType.CAPTURE_3D_RECONSTRUCTION, Set.of(PipelineStep.PREPROCESS))
                .orElseThrow()
                .getId();
        assertThat(jobService.findByResource(captureId).orElseThrow().getId()).isEqualTo(jobId);
        return jobId;
    }

    @Test
    @DisplayName("un trabajo fallido expone el motivo registrado")
    void failedJobExposesItsReason() throws Exception {
        UUID captureId = uploadVideo("Recorrido rechazado");
        confirmVideo(captureId);
        UUID jobId = claimPreprocessing(captureId);
        jobService.fail(jobId, "El video dura menos de lo necesario para reconstruir");

        mockMvc.perform(get(processingUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobStatus").value("FAILED"))
                .andExpect(jsonPath("$[0].failureReason")
                        .value("El video dura menos de lo necesario para reconstruir"));
    }

    @Test
    @DisplayName("un error transitorio no se expone mientras el trabajo siga vivo")
    void transientErrorIsNotExposedWhileJobIsAlive() throws Exception {
        UUID captureId = uploadVideo("Recorrido con reintento");
        confirmVideo(captureId);
        UUID jobId = claimPreprocessing(captureId);
        // Con intentos de sobra, retryOrFail reencola y guarda el error igual.
        jobService.retryOrFail(jobId, "timeout de ffmpeg", 99);

        mockMvc.perform(get(processingUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].failureReason").value(nullValue()));
    }

    @Test
    @DisplayName("una captura ya disponible sale del listado")
    void availableCaptureLeavesTheList() throws Exception {
        UUID captureId = uploadVideo("Recorrido terminado");
        confirmVideo(captureId);
        Capture3d capture = captureRepository.findById(captureId).orElseThrow();
        capture.markAvailable();
        captureRepository.save(capture);

        mockMvc.perform(get(processingUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("una captura cargada a mano, sin trabajo asociado, no figura en proceso")
    void manualCaptureWithoutJobIsNotListed() throws Exception {
        mockMvc.perform(post("/api/developments/" + developmentId + "/captures")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateCaptureRequest(
                                LocalDate.of(2026, 9, 1), "SPZ subido a mano", null, null))))
                .andExpect(status().isCreated());

        mockMvc.perform(get(processingUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("las capturas se listan de la más reciente a la más antigua")
    void mostRecentFirst() throws Exception {
        uploadVideo("Primera subida");
        uploadVideo("Segunda subida");

        mockMvc.perform(get(processingUrl()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].description").value("Segunda subida"))
                .andExpect(jsonPath("$[1].description").value("Primera subida"));
    }

    @Test
    @DisplayName("el desarrollo de otra organización responde 404")
    void foreignDevelopmentIsNotFound() throws Exception {
        uploadVideo("Recorrido privado");
        String intruderToken = registerAndLogin("intruso");

        mockMvc.perform(get(processingUrl()).header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isNotFound());
    }

    // --- Alta desde video: lo que se rechaza ---

    @Test
    @DisplayName("un archivo que no es video se rechaza con 415 y no crea captura ni trabajo")
    void nonVideoIsRejected() throws Exception {
        mockMvc.perform(post("/api/developments/" + developmentId + "/captures/from-video")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateCaptureFromVideoRequest(
                                LocalDate.of(2026, 9, 1), "Foto suelta", null, null,
                                "frente.jpg", "image/jpeg"))))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(captureRepository.count()).isZero();
        assertThat(jobRepository.count()).isZero();
    }

    @Test
    @DisplayName("confirmar sin el video en el almacenamiento es un conflicto y el trabajo no entra a la cola")
    void confirmWithoutUploadedVideoIsAConflict() throws Exception {
        UUID captureId = uploadVideo("Subida cortada");
        when(storageService.exists(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/captures/" + captureId + "/confirm-video")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        assertThat(jobService.findByResource(captureId).orElseThrow().getStatus()).isEqualTo(JobStatus.UPLOADED);
    }

    @Test
    @DisplayName("una captura de SPZ no se confirma como video")
    void spzCaptureIsNotConfirmedAsVideo() throws Exception {
        String response = mockMvc.perform(post("/api/developments/" + developmentId + "/captures")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateCaptureRequest(
                                LocalDate.of(2026, 9, 1), "Scaniverse", null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID captureId = UUID.fromString(JSON.readTree(response).get("captureId").asString());

        mockMvc.perform(post("/api/captures/" + captureId + "/confirm-video")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        assertThat(jobService.findByResource(captureId)).isEmpty();
        assertThat(captureRepository.findById(captureId).orElseThrow().getStatus()).isEqualTo(CaptureStatus.PENDING);
    }
}
