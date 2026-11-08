package com.obraviva.platform.ai;

import com.obraviva.platform.ai.extraction.AiExtractionRepository;
import com.obraviva.platform.ai.extraction.ReviewStatus;
import com.obraviva.platform.ai.transcription.AudioTranscriber;
import com.obraviva.platform.ai.transcription.Transcription;
import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.jobs.ProcessingJobRepository;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;
import com.obraviva.platform.media.MediaAssetRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El audio de una casa, para un encargado de casas puntuales: sube, revisa y publica el
 * de sus casas, y recibe 403 en el de las demás, también al ver el detalle, confirmar la
 * subida, aprobar o descartar. Antes alcanzaba con llegar al desarrollo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false",
        "obraviva.ai.extraction.provider=mock"
})
class AudioHousePermissionsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @MockitoBean private StorageService storageService;
    @MockitoBean private AudioTranscriber transcriber;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AudioExtractionWorker worker;
    @Autowired private AiExtractionRepository extractionRepository;
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private MediaAssetRepository mediaRepository;

    private String ownerToken;
    private String supervisorToken;
    private String otherSupervisorToken;
    private String outsiderToken;
    private UUID developmentId;
    private UUID casa1;
    private UUID casa2;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();
        extractionRepository.deleteAll();
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.exists(anyString())).thenReturn(true);
        when(transcriber.transcribe(any())).thenReturn(new Transcription(
                "Terminamos el obrador y arrancamos el replanteo.", Duration.ofSeconds(8), Duration.ofSeconds(1)));

        Organization organization = organizationRepository.save(new Organization("Casas del Sur"));
        ownerToken = login(createUser("duenio", organization, OrganizationRole.OWNER));
        String supervisorEmail = createUser("carlos", organization, OrganizationRole.MEMBER);
        UUID supervisorId = userRepository.findByEmail(supervisorEmail).orElseThrow().getId();
        supervisorToken = login(supervisorEmail);
        outsiderToken = login(createUser("ajeno", organizationRepository.save(new Organization("Otra")), OrganizationRole.OWNER));

        developmentId = UUID.fromString(json(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio del Sur"}""".formatted(organization.getId())))).get("id").asString());
        JsonNode houses = json(ok(send(ownerToken, post("/api/developments/" + developmentId + "/units/batch"), """
                {"prefix":"Casa","firstNumber":1,"count":2,"withStandardStages":true}""")));
        casa1 = UUID.fromString(houses.get(0).get("id").asString());
        casa2 = UUID.fromString(houses.get(1).get("id").asString());
        ok(send(ownerToken, post("/api/developments/" + developmentId + "/team"), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(supervisorId, casa1)));
        // Otro encargado de la misma casa.
        String otherEmail = createUser("ana", organization, OrganizationRole.MEMBER);
        UUID otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        otherSupervisorToken = login(otherEmail);
        ok(send(ownerToken, post("/api/developments/" + developmentId + "/team"), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(otherId, casa1)));
    }

    @Test
    @DisplayName("el encargado sube, revisa y publica el audio de su casa")
    void supervisorPublishesTheAudioOfHisHouse() throws Exception {
        UUID extractionId = readyForReview(supervisorToken, casa1);

        ok(send(supervisorToken, get("/api/audio-extractions/" + extractionId)));
        ok(send(supervisorToken, post("/api/audio-extractions/" + extractionId + "/approve"), """
                {"entryDate":"2026-09-07","entries":[{"stageId":null,"description":"Obrador terminado"}]}"""));

        assertThat(extractionRepository.findById(extractionId).orElseThrow().getReviewStatus())
                .isEqualTo(ReviewStatus.APPROVED);
        send(ownerToken, get("/api/developments/" + developmentId + "/progress"))
                .andExpect(jsonPath("$[0].unitId").value(casa1.toString()))
                .andExpect(jsonPath("$[0].source").value("AUDIO"));
    }

    @Test
    @DisplayName("el audio de otra casa: 403 al verlo, confirmarlo, aprobarlo o descartarlo; otra organización: 404")
    void supervisorCannotTouchTheAudioOfAnotherHouse() throws Exception {
        send(supervisorToken, post("/api/developments/" + developmentId + "/audio-extractions"), """
                {"audioFilename":"parte.ogg","audioContentType":"audio/ogg","unitId":"%s"}""".formatted(casa2))
                .andExpect(status().isForbidden());

        UUID extractionId = readyForReview(ownerToken, casa2);
        String approval = """
                {"entryDate":"2026-09-07","entries":[{"stageId":null,"description":"x"}]}""";

        send(supervisorToken, get("/api/audio-extractions/" + extractionId)).andExpect(status().isForbidden());
        send(supervisorToken, post("/api/audio-extractions/" + extractionId + "/confirm-audio")).andExpect(status().isForbidden());
        send(supervisorToken, post("/api/audio-extractions/" + extractionId + "/approve"), approval).andExpect(status().isForbidden());
        send(supervisorToken, post("/api/audio-extractions/" + extractionId + "/reject"), "{}").andExpect(status().isForbidden());
        send(outsiderToken, post("/api/audio-extractions/" + extractionId + "/approve"), approval).andExpect(status().isNotFound());

        assertThat(extractionRepository.findById(extractionId).orElseThrow().getReviewStatus())
                .isEqualTo(ReviewStatus.READY_FOR_REVIEW);
    }

    @Test
    @DisplayName("en \"Para revisar\", el audio dice qué propone: cuántas novedades y cuántos cambios de etapa")
    void pendingAudioSaysWhatItProposes() throws Exception {
        when(transcriber.transcribe(any())).thenReturn(new Transcription(
                "Terminamos las fundaciones de la casa.", Duration.ofSeconds(5), Duration.ofSeconds(1)));
        UUID extractionId = readyForReview(supervisorToken, casa1);

        JsonNode proposal = json(ok(send(supervisorToken, get("/api/audio-extractions/" + extractionId)))).get("proposal");
        JsonNode pending = json(ok(send(ownerToken, get("/api/developments/" + developmentId + "/activity/pending-audios")))).get(0);
        assertThat(pending.get("extractionId").asString()).isEqualTo(extractionId.toString());
        assertThat(proposal.get("stageProposals").size()).isPositive();
        assertThat(pending.get("progressNotes").asInt()).isEqualTo(proposal.get("progressNotes").size());
        assertThat(pending.get("stageChanges").asInt()).isEqualTo(proposal.get("stageProposals").size());
    }

    private UUID readyForReview(String token, UUID unitId) throws Exception {
        UUID extractionId = UUID.fromString(json(ok(send(token, post("/api/developments/" + developmentId + "/audio-extractions"), """
                {"audioFilename":"parte.ogg","audioContentType":"audio/ogg","unitId":"%s"}""".formatted(unitId))))
                .get("extractionId").asString());
        ok(send(token, post("/api/audio-extractions/" + extractionId + "/confirm-audio")));
        worker.processNext();   // TRANSCRIBE
        worker.processNext();   // INTERPRET
        assertThat(extractionRepository.findById(extractionId).orElseThrow().getReviewStatus())
                .isEqualTo(ReviewStatus.READY_FOR_REVIEW);
        return extractionId;
    }

    private String createUser(String prefix, Organization organization, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        memberRepository.save(new OrganizationMember(organization, user.getId(), role));
        return email;
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("accessToken").asString();
    }

    private ResultActions send(String token, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    private ResultActions send(String token, MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String ok(ResultActions actions) throws Exception {
        return actions.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    @Test
    @DisplayName("cada encargado aprueba o descarta sólo sus audios: el de otro encargado de la casa da 403; el dueño, todos")
    void onlyTheSenderOrTheOwnerReviews() throws Exception {
        UUID carlos = readyForReview(supervisorToken, casa1);
        UUID otro = readyForReview(supervisorToken, casa1);
        String approval = """
                {"entryDate":"2026-09-07","entries":[{"stageId":null,"description":"Obrador terminado"}]}""";

        // Verlo sí: es la misma casa.
        ok(send(otherSupervisorToken, get("/api/audio-extractions/" + carlos)));
        send(otherSupervisorToken, post("/api/audio-extractions/" + carlos + "/approve"), approval)
                .andExpect(status().isForbidden());
        send(otherSupervisorToken, post("/api/audio-extractions/" + carlos + "/reject"), "{}")
                .andExpect(status().isForbidden());
        assertThat(extractionRepository.findById(carlos).orElseThrow().getReviewStatus())
                .isEqualTo(ReviewStatus.READY_FOR_REVIEW);
        send(ownerToken, get("/api/developments/" + developmentId + "/progress"))
                .andExpect(jsonPath("$.length()").value(0));

        ok(send(ownerToken, post("/api/audio-extractions/" + carlos + "/approve"), approval));
        ok(send(ownerToken, post("/api/audio-extractions/" + otro + "/reject"), "{}"));
        assertThat(extractionRepository.findById(carlos).orElseThrow().getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(extractionRepository.findById(otro).orElseThrow().getReviewStatus()).isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    @DisplayName("las fotos de un audio llevan su lugar; un lugar que no es de la casa no publica nada")
    void audioPhotosCarryTheirPlace() throws Exception {
        UUID extractionId = readyForReview(supervisorToken, casa1);
        UUID frente = pendingPhoto(supervisorToken, casa1);
        UUID suelta = pendingPhoto(supervisorToken, casa1);

        send(supervisorToken, post("/api/audio-extractions/" + extractionId + "/approve"), """
                {"entryDate":"2026-09-07","entries":[{"stageId":null,"description":"Frente revocado",
                  "photoIds":["%s","%s"],"photoSectors":{"%s":"Quincho"}}]}""".formatted(frente, suelta, frente))
                .andExpect(status().isBadRequest());
        assertThat(extractionRepository.findById(extractionId).orElseThrow().getReviewStatus())
                .isEqualTo(ReviewStatus.READY_FOR_REVIEW);
        assertThat(mediaRepository.findById(frente).orElseThrow().getSector()).isNull();

        ok(send(supervisorToken, post("/api/audio-extractions/" + extractionId + "/approve"), """
                {"entryDate":"2026-09-07","entries":[{"stageId":null,"description":"Frente revocado",
                  "photoIds":["%s","%s"],"photoSectors":{"%s":"Frente"}}]}""".formatted(frente, suelta, frente)));

        assertThat(mediaRepository.findById(frente).orElseThrow().getSector()).isEqualTo("Frente");
        assertThat(mediaRepository.findById(suelta).orElseThrow().getSector()).isNull();
        assertThat(mediaRepository.findById(frente).orElseThrow().getProgressEntryId()).isNotNull();
    }

    private UUID pendingPhoto(String token, UUID unitId) throws Exception {
        return UUID.fromString(json(ok(send(token, post("/api/developments/" + developmentId + "/media/upload-url"), """
                {"filename":"foto.jpg","contentType":"image/jpeg","unitId":"%s"}""".formatted(unitId))))
                .get("mediaId").asString());
    }
}
