package com.obraviva.platform.ai;

import com.obraviva.platform.ai.AudioDtos.CreateAudioExtractionRequest;
import com.obraviva.platform.ai.AudioDtos.RejectExtractionRequest;
import com.obraviva.platform.ai.extraction.AiExtractionRepository;
import com.obraviva.platform.ai.extraction.ReviewStatus;
import com.obraviva.platform.ai.extraction.ProposalCodec;
import com.obraviva.platform.ai.transcription.AudioTranscriber;
import com.obraviva.platform.ai.transcription.Transcription;
import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.jobs.JobStatus;
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

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica el flujo de revisión humana: consultar la propuesta, aprobarla con
 * correcciones, rechazarla, y el aislamiento entre organizaciones.
 *
 * <p>El transcriptor está mockeado; el intérprete es el adaptador simulado, que se
 * ejercita de verdad. Ningún test toca la red.
 *
 * <p>Los tests de aislamiento son obligatorios según el documento maestro. Acá tienen
 * un peso particular: el endpoint de aprobación recibe la propuesta <strong>desde el
 * navegador</strong>, porque el revisor pudo corregirla, y es la superficie por la
 * que alguien podría intentar publicar contra etapas de otra organización.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false",
        "obraviva.ai.extraction.provider=mock"
})
class AudioExtractionReviewIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    /** Transcripción real del audio de prueba, con sus errores incluidos. */
    private static final String TRANSCRIPT = """
            Hola, te mando el avance de esta semana. Terminamos el rego que gruesos \
            los departamentos del segundo piso. Falta todavía la instalación eléctrica \
            del tercero. Se demoró porque no llegaron los caños corrobados. La estructura \
            del cuarto piso arranca el lunes.""";

    @MockitoBean private StorageService storageService;
    @MockitoBean private AudioTranscriber transcriber;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AudioExtractionWorker worker;
    @Autowired private AiExtractionRepository extractionRepository;
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private ProcessingJobService jobService;
    @Autowired private StageCatalogService stageCatalog;
    @Autowired private ProposalCodec proposalCodec;
    @Autowired private com.obraviva.platform.media.MediaAssetRepository mediaRepository;

    private String ownerToken;
    private UUID ownerId;
    private UUID organizationId;
    private UUID developmentId;
    private UUID structureStageId;

    private String intruderToken;
    private UUID intruderDevelopmentId;
    private UUID intruderStageId;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();
        extractionRepository.deleteAll();

        when(storageService.presignUpload(anyString(), anyString()))
                .thenReturn("http://localhost:9000/fake-upload");
        when(storageService.presignDownload(anyString()))
                .thenReturn("http://localhost:9000/fake-download");
        when(storageService.exists(anyString())).thenReturn(true);
        when(transcriber.transcribe(any())).thenReturn(new Transcription(
                TRANSCRIPT, Duration.ofSeconds(19), Duration.ofSeconds(3)));

        // Organización propietaria
        String ownerEmail = "owner-" + UUID.randomUUID() + "@obraviva.test";
        ownerId = userRepository.save(
                new User(ownerEmail, passwordEncoder.encode(PASSWORD), "Dueño")).getId();
        ownerToken = login(ownerEmail);
        organizationId = createOrganization(ownerToken, "Constructora Propietaria");
        developmentId = createDevelopment(ownerToken, organizationId, "Torre Propia");
        structureStageId = createStage(ownerToken, developmentId, "Estructura", 1);

        // Organización ajena, sin relación con la anterior
        String intruderEmail = "intruso-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(
                new User(intruderEmail, passwordEncoder.encode(PASSWORD), "Ajeno"));
        intruderToken = login(intruderEmail);
        UUID intruderOrg = createOrganization(intruderToken, "Constructora Ajena");
        intruderDevelopmentId = createDevelopment(intruderToken, intruderOrg, "Torre Ajena");
        intruderStageId = createStage(intruderToken, intruderDevelopmentId, "Cimientos", 1);
    }

    // --- Utilidades de armado ---

    private String login(String email) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("accessToken").asString();
    }

    private UUID createOrganization(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateOrganizationRequest(name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(response).get("id").asString());
    }

    private UUID createDevelopment(String token, UUID orgId, String name) throws Exception {
        String response = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateDevelopmentRequest(orgId, name, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(response).get("id").asString());
    }

    private UUID createStage(String token, UUID devId, String name, int order) throws Exception {
        String body = """
                {"name":"%s","orderIndex":%d}""".formatted(name, order);
        String response = mockMvc.perform(post("/api/developments/" + devId + "/stages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(response).get("id").asString());
    }

    /** Sube un audio y lo procesa hasta dejarlo listo para revisión. */
    private UUID extractionReadyForReview() throws Exception {
        String response = mockMvc.perform(
                        post("/api/developments/" + developmentId + "/audio-extractions")
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(new CreateAudioExtractionRequest(
                                        "parte.ogg", "audio/ogg"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID extractionId = UUID.fromString(JSON.readTree(response).get("extractionId").asString());

        mockMvc.perform(post("/api/audio-extractions/" + extractionId + "/confirm-audio")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        worker.processNext();   // TRANSCRIBE
        worker.processNext();   // INTERPRET

        assertThat(extractionRepository.findById(extractionId).orElseThrow().getReviewStatus())
                .isEqualTo(ReviewStatus.READY_FOR_REVIEW);
        return extractionId;
    }

    private String approvalBody(String entriesJson) {
        return """
                {"entryDate":"2026-09-07","notes":"revisado","entries":[%s]}""".formatted(entriesJson);
    }

    private org.springframework.test.web.servlet.ResultActions approve(UUID extractionId, String body) throws Exception {
        return mockMvc.perform(post("/api/audio-extractions/" + extractionId + "/approve")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private tools.jackson.databind.JsonNode commonStage() throws Exception {
        String stages = mockMvc.perform(get("/api/developments/" + developmentId + "/stages")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(stages).get(0);
    }

    private void assertNothingPublished(UUID extractionId) throws Exception {
        mockMvc.perform(get("/api/developments/" + developmentId + "/progress")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(commonStage().get("status").asString()).isEqualTo("PENDING");
        assertThat(commonStage().get("progressPercent").asString()).isEqualTo("0");
        assertThat(extractionRepository.findById(extractionId).orElseThrow().getReviewStatus())
                .isEqualTo(ReviewStatus.READY_FOR_REVIEW);
    }

    // --- Consulta ---

    @Test
    @DisplayName("el detalle muestra la transcripción junto a la propuesta")
    void detailShowsTranscriptAlongsideProposal() throws Exception {
        UUID extractionId = extractionReadyForReview();

        // El documento maestro lo exige: sin la transcripción al lado, el revisor no
        // puede contrastar lo que dijo el encargado con lo que el modelo interpretó.
        mockMvc.perform(get("/api/audio-extractions/" + extractionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("READY_FOR_REVIEW"))
                .andExpect(jsonPath("$.transcript").value(TRANSCRIPT))
                .andExpect(jsonPath("$.proposal").exists())
                .andExpect(jsonPath("$.modelId").exists());
    }

    @Test
    @DisplayName("el listado de pendientes muestra las que esperan revisión, con el paso de su trabajo")
    void pendingReviewListingWorks() throws Exception {
        extractionReadyForReview();

        mockMvc.perform(get("/api/audio-extractions/pending-review")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reviewStatus").value("READY_FOR_REVIEW"));
        mockMvc.perform(get("/api/developments/" + developmentId + "/audio-extractions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$[0].jobStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$[0].pipelineStep").value("AWAIT_REVIEW"))
                .andExpect(jsonPath("$[0].mine").value(true));
    }

    @Test
    @DisplayName("mientras se transcribe, el listado dice en qué paso está")
    void listingShowsTheStepWhileProcessing() throws Exception {
        String response = mockMvc.perform(post("/api/developments/" + developmentId + "/audio-extractions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateAudioExtractionRequest("parte.ogg", "audio/ogg"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID extractionId = UUID.fromString(JSON.readTree(response).get("extractionId").asString());
        mockMvc.perform(post("/api/audio-extractions/" + extractionId + "/confirm-audio")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/developments/" + developmentId + "/audio-extractions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$[0].reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].jobStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].pipelineStep").value("TRANSCRIBE"));
        worker.processNext();
        mockMvc.perform(get("/api/developments/" + developmentId + "/audio-extractions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$[0].pipelineStep").value("INTERPRET"));
    }

    // --- Aprobación ---

    @Test
    @DisplayName("aprobar publica un avance de origen audio con el cambio de estado, fechado en el parte")
    void approvalPublishesEntriesAndUpdatesStages() throws Exception {
        UUID extractionId = extractionReadyForReview();

        approve(extractionId, approvalBody("""
                {"stageId":"%s","description":"La estructura arranca el lunes","stageUpdate":{"status":"IN_PROGRESS"}}"""
                .formatted(structureStageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.publishedEntries").value(1))
                .andExpect(jsonPath("$.updatedStages").value(1));

        mockMvc.perform(get("/api/developments/" + developmentId + "/progress")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].source").value("AUDIO"))
                .andExpect(jsonPath("$[0].entryDate").value("2026-09-07"));

        // La IA propuso el estado y nadie tocó el porcentaje: queda como estaba.
        tools.jackson.databind.JsonNode stage = commonStage();
        assertThat(stage.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(stage.get("progressPercent").asString()).isEqualTo("0");
        assertThat(stage.get("startedOn").asString()).isEqualTo("2026-09-07");
    }

    @Test
    @DisplayName("el porcentaje lo pone la persona en la revisión, y Completado lo lleva a 100")
    void reviewerSetsThePercent() throws Exception {
        UUID extractionId = extractionReadyForReview();

        approve(extractionId, approvalBody("""
                {"stageId":"%s","description":"Avanza la estructura","stageUpdate":{"status":"IN_PROGRESS","progressPercent":35}}"""
                .formatted(structureStageId)))
                .andExpect(status().isOk());

        assertThat(commonStage().get("progressPercent").asString()).isEqualTo("35");
    }

    @Test
    @DisplayName("se publica lo que el revisor dejó, no lo que el modelo propuso, y se conservan las alertas")
    void publishesTheReviewedProposalNotTheGeneratedOne() throws Exception {
        UUID extractionId = extractionReadyForReview();
        int alerts = proposalCodec.decode(extractionRepository.findById(extractionId).orElseThrow().getProposal())
                .supplyAlerts().size();

        approve(extractionId, approvalBody("""
                {"stageId":null,"description":"Se terminó el revoque grueso del segundo piso"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedEntries").value(1))
                .andExpect(jsonPath("$.updatedStages").value(0));

        mockMvc.perform(get("/api/developments/" + developmentId + "/progress")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$[0].description")
                        .value("Se terminó el revoque grueso del segundo piso"));
        mockMvc.perform(get("/api/audio-extractions/" + extractionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.proposal.progressNotes.length()").value(1))
                .andExpect(jsonPath("$.proposal.stageProposals.length()").value(0))
                .andExpect(jsonPath("$.proposal.supplyAlerts.length()").value(alerts));
        assertThat(commonStage().get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("las fotos subidas en la revisión se publican con su avance")
    void photosArePublishedWithTheirEntry() throws Exception {
        UUID extractionId = extractionReadyForReview();
        String upload = mockMvc.perform(post("/api/developments/" + developmentId + "/media/upload-url")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"filename":"losa.jpg","contentType":"image/jpeg"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID photoId = UUID.fromString(JSON.readTree(upload).get("mediaId").asString());

        approve(extractionId, approvalBody("""
                {"stageId":"%s","description":"Losa hormigonada","photoIds":["%s"]}""".formatted(structureStageId, photoId)))
                .andExpect(status().isOk());

        com.obraviva.platform.media.MediaAsset photo = mediaRepository.findById(photoId).orElseThrow();
        assertThat(photo.getStatus()).isEqualTo(com.obraviva.platform.media.MediaStatus.AVAILABLE);
        assertThat(photo.getProgressEntryId()).isNotNull();
    }

    @Test
    @DisplayName("si un avance no pasa, no se publica ninguno y el audio sigue en revisión")
    void approvalIsAtomic() throws Exception {
        UUID extractionId = extractionReadyForReview();

        // El primero es válido y cambia la etapa; el segundo trae una foto que no existe.
        approve(extractionId, approvalBody("""
                {"stageId":"%s","description":"Arranca la estructura","stageUpdate":{"status":"IN_PROGRESS","progressPercent":20}},
                {"stageId":null,"description":"Llegó el hierro","photoIds":["%s"]}"""
                .formatted(structureStageId, UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        assertNothingPublished(extractionId);
    }

    @Test
    @DisplayName("fecha futura, presupuesto dentro del cambio de etapa o un estado inexistente: 400 y nada publicado")
    void invalidApprovalsPublishNothing() throws Exception {
        UUID extractionId = extractionReadyForReview();

        approve(extractionId, """
                {"entryDate":"%s","entries":[{"stageId":null,"description":"x"}]}""".formatted(LocalDate.now().plusDays(1)))
                .andExpect(status().isBadRequest());
        approve(extractionId, approvalBody("""
                {"stageId":"%s","description":"x","stageUpdate":{"status":"IN_PROGRESS","budgetAmount":1000}}"""
                .formatted(structureStageId)))
                .andExpect(status().isBadRequest());
        approve(extractionId, approvalBody("""
                {"stageId":"%s","description":"x","stageUpdate":{"status":"CASI_LISTO"}}""".formatted(structureStageId)))
                .andExpect(status().isBadRequest());

        assertNothingPublished(extractionId);
    }

    @Test
    @DisplayName("una etapa de otra organización se rechaza y no se publica nada")
    void proposalWithForeignStageIsRejected() throws Exception {
        // La propuesta llega desde el navegador porque el revisor pudo corregirla, así
        // que un cliente podría enviar etapas ajenas. Antes se descartaban en silencio;
        // ahora pasan por la publicación, que valida cada avance como el alta manual.
        UUID extractionId = extractionReadyForReview();

        approve(extractionId, approvalBody("""
                {"stageId":"%s","description":"avance inyectado","stageUpdate":{"status":"COMPLETED"}}"""
                .formatted(intruderStageId)))
                .andExpect(status().isBadRequest());

        assertNothingPublished(extractionId);
    }

    @Test
    @DisplayName("al aprobar, la interpretación queda revisada por quien aprobó")
    void approvalClosesTheJob() throws Exception {
        UUID extractionId = extractionReadyForReview();

        assertThat(jobService.findByResource(extractionId).orElseThrow().getStatus())
                .isEqualTo(JobStatus.NEEDS_REVIEW);

        approve(extractionId, approvalBody("")).andExpect(status().isOk());

        assertThat(extractionRepository.findById(extractionId).orElseThrow().getReviewedBy())
                .isEqualTo(ownerId);
    }

    @Test
    @DisplayName("no se puede aprobar dos veces")
    void cannotApproveTwice() throws Exception {
        UUID extractionId = extractionReadyForReview();

        approve(extractionId, approvalBody("")).andExpect(status().isOk());
        approve(extractionId, approvalBody("")).andExpect(status().isConflict());
    }

    // --- Rechazo ---

    @Test
    @DisplayName("rechazar no publica nada")
    void rejectionPublishesNothing() throws Exception {
        UUID extractionId = extractionReadyForReview();

        mockMvc.perform(post("/api/audio-extractions/" + extractionId + "/reject")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new RejectExtractionRequest("el audio no era de esta obra"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REJECTED"));

        mockMvc.perform(get("/api/developments/" + developmentId + "/progress")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.length()").value(0));

        assertThat(stageCatalog.stagesOf(developmentId, List.of(organizationId)))
                .allSatisfy(stage -> assertThat(stage.status()).isEqualTo("PENDING"));
    }

    // --- Aislamiento entre organizaciones ---

    @Test
    @DisplayName("un usuario de otra organización no ve la interpretación")
    void foreignUserCannotSeeExtraction() throws Exception {
        UUID extractionId = extractionReadyForReview();

        // 404 y no 403: no se confirma la existencia de recursos ajenos.
        mockMvc.perform(get("/api/audio-extractions/" + extractionId)
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un usuario de otra organización no puede aprobar la interpretación")
    void foreignUserCannotApprove() throws Exception {
        UUID extractionId = extractionReadyForReview();

        mockMvc.perform(post("/api/audio-extractions/" + extractionId + "/approve")
                        .header("Authorization", "Bearer " + intruderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalBody("")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el trabajo del pipeline de audio queda en revisión y sale de la cola")
    void jobLeavesTheQueueWhileAwaitingReview() throws Exception {
        UUID extractionId = extractionReadyForReview();

        assertThat(jobService.findByResource(extractionId).orElseThrow().getPipelineStep())
                .isEqualTo(PipelineStep.AWAIT_REVIEW);

        // El reclamo filtra por PENDING, así que nadie lo vuelve a tomar.
        worker.processNext();
        worker.processNext();

        assertThat(extractionRepository.findById(extractionId).orElseThrow().getReviewStatus())
                .isEqualTo(ReviewStatus.READY_FOR_REVIEW);
    }
}