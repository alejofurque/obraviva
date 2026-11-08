package com.obraviva.platform.publication;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.media.MediaAsset;
import com.obraviva.platform.media.MediaAssetRepository;
import com.obraviva.platform.media.MediaStatus;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Publicar un avance con sus fotos y el cambio de su etapa en una sola operación.
 *
 * <p>Lo que se prueba es lo que ve el comprador: o aparece todo junto (texto, fotos y la
 * etapa con su porcentaje nuevo) o no cambia nada. Las fallas a mitad de camino se
 * verifican contra los tres lugares que podrían quedar a medias: el avance, la etapa y el
 * estado de las fotos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class ProgressPublicationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";
    private static final String DATE = "2026-09-10";

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private MediaAssetRepository mediaRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private String supervisorToken;
    private String outsiderToken;
    private UUID developmentId;
    private UUID casa1;
    private UUID casa2;
    private UUID stage1;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.exists(anyString())).thenReturn(true);

        Organization organization = organizationRepository.save(new Organization("Casas del Sur"));
        ownerToken = login(createUser("duenio", organization, OrganizationRole.OWNER));
        String supervisorEmail = createUser("carlos", organization, OrganizationRole.MEMBER);
        UUID supervisorId = userRepository.findByEmail(supervisorEmail).orElseThrow().getId();
        supervisorToken = login(supervisorEmail);
        outsiderToken = login(createUser("ajeno", organizationRepository.save(new Organization("Otra")), OrganizationRole.OWNER));

        developmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio del Sur"}""".formatted(organization.getId()))));
        JsonNode houses = json(ok(send(ownerToken, post("/api/developments/" + developmentId + "/units/batch"), """
                {"prefix":"Casa","firstNumber":1,"count":2,"withStandardStages":true}""")));
        casa1 = UUID.fromString(houses.get(0).get("id").asString());
        casa2 = UUID.fromString(houses.get(1).get("id").asString());
        stage1 = UUID.fromString(firstStage(casa1).get("id").asString());
        ok(send(ownerToken, post("/api/developments/" + developmentId + "/team"), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(supervisorId, casa1)));
    }

    // --- Lo que funciona ---

    @Test
    @DisplayName("en la historia del dueño, cada avance dice quién lo publicó y qué le hizo a la etapa, aun corregido")
    void timelineCarriesAuthorAndStageChange() throws Exception {
        JsonNode published = json(ok(publish(supervisorToken, """
                {"stageId":"%s","entryDate":"%s","description":"Obrador listo","unitId":"%s",
                 "stageUpdate":{"progressPercent":35,"status":"IN_PROGRESS"}}""".formatted(stage1, DATE, casa1))));
        ok(send(ownerToken, post("/api/progress/" + published.get("id").asString() + "/corrections"), """
                {"stageId":"%s","entryDate":"%s","description":"Obrador y replanteo listos"}""".formatted(stage1, DATE)));

        JsonNode entry = timeline().get(0);
        assertThat(entry.get("version").asString()).isEqualTo("2");
        assertThat(entry.get("description").asString()).isEqualTo("Obrador y replanteo listos");
        assertThat(entry.get("authorName").asString()).isEqualTo("Usuario carlos");
        assertThat(entry.get("stageChange").get("percentBefore").asString()).isEqualTo("0");
        assertThat(entry.get("stageChange").get("percentAfter").asString()).isEqualTo("35");
        assertThat(entry.get("stageChange").get("statusAfter").asString()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("el encargado publica con fotos y la etapa al 35%, y el comprador ve todo junto en el enlace público")
    void supervisorPublishesAndBuyerSeesItTogether() throws Exception {
        UUID photoA = pendingPhoto(supervisorToken, casa1, "revoque-1.jpg");
        UUID photoB = pendingPhoto(supervisorToken, casa1, "revoque-2.jpg");

        ok(publish(supervisorToken, """
                {"stageId":"%s","entryDate":"%s","description":"Obrador y replanteo terminados","unitId":"%s",
                 "photoIds":["%s","%s"],"stageUpdate":{"progressPercent":35,"status":"IN_PROGRESS"}}"""
                .formatted(stage1, DATE, casa1, photoA, photoB)));

        JsonNode stage = firstStage(casa1);
        assertThat(stage.get("progressPercent").asString()).isEqualTo("35");
        assertThat(stage.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(stage.get("startedOn").asString()).isEqualTo(DATE);
        assertThat(mediaRepository.findById(photoA).orElseThrow().getStatus()).isEqualTo(MediaStatus.AVAILABLE);

        JsonNode view = publicView(casa1);
        assertThat(view.get("stages").get(0).get("progressPercent").asString()).isEqualTo("35");
        assertThat(view.get("timeline").get(0).get("description").asString()).isEqualTo("Obrador y replanteo terminados");
        assertThat(view.get("timeline").get(0).get("photos")).hasSize(2);
        // "Este mes en tu casa" dice qué le hizo el avance a su etapa: 0% → 35%, Pendiente → En ejecución.
        JsonNode change = view.get("timeline").get(0).get("stageChange");
        assertThat(change.get("percentBefore").asString()).isEqualTo("0");
        assertThat(change.get("percentAfter").asString()).isEqualTo("35");
        assertThat(change.get("statusBefore").asString()).isEqualTo("PENDING");
        assertThat(change.get("statusAfter").asString()).isEqualTo("IN_PROGRESS");
        assertThat(view.get("gallery")).hasSize(2);
        assertThat(view.get("showcase")).isEmpty();
    }

    @Test
    @DisplayName("una foto subida y todavía sin publicar no aparece en ningún lado, ni como foto de galería")
    void pendingPhotoIsNotGallery() throws Exception {
        pendingPhoto(supervisorToken, casa1, "suelta.jpg");
        pendingPhoto(ownerToken, casa1, "del-duenio.jpg");

        assertThat(json(ok(send(ownerToken, get("/api/developments/" + developmentId + "/media"))))).isEmpty();
        JsonNode view = publicView(casa1);
        assertThat(view.get("gallery")).isEmpty();
        assertThat(view.get("showcase")).isEmpty();
        assertThat(view.get("timeline")).isEmpty();
    }

    @Test
    @DisplayName("Completado lleva la etapa a 100 aunque llegue otro número, con la fecha del avance como fin")
    void completedMeansFullProgress() throws Exception {
        ok(publish(supervisorToken, """
                {"stageId":"%s","entryDate":"%s","description":"Terminado","unitId":"%s",
                 "stageUpdate":{"progressPercent":60,"status":"COMPLETED"}}""".formatted(stage1, DATE, casa1)));

        JsonNode stage = firstStage(casa1);
        assertThat(stage.get("progressPercent").asString()).isEqualTo("100");
        assertThat(stage.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(stage.get("completedOn").asString()).isEqualTo(DATE);
    }

    @Test
    @DisplayName("llegar a 100 no completa la etapa, y un avance sin etapa (general de la casa) sigue andando")
    void fullProgressDoesNotComplete() throws Exception {
        ok(publish(supervisorToken, """
                {"stageId":"%s","entryDate":"%s","description":"Todo hecho, falta la inspección","unitId":"%s",
                 "stageUpdate":{"progressPercent":100}}""".formatted(stage1, DATE, casa1)));
        ok(publish(supervisorToken, """
                {"stageId":null,"entryDate":"%s","description":"Llegó el hierro","unitId":"%s","photoIds":[],"stageUpdate":null}"""
                .formatted(DATE, casa1)));

        JsonNode stage = firstStage(casa1);
        assertThat(stage.get("progressPercent").asString()).isEqualTo("100");
        assertThat(stage.get("status").asString()).isEqualTo("PENDING");
        assertThat(timeline()).hasSize(2);
    }

    @Test
    @DisplayName("el dueño publica como siempre, sin los campos nuevos, también en las obras comunes")
    void ownerKeepsPublishing() throws Exception {
        ok(publish(ownerToken, """
                {"entryDate":"%s","description":"Cordón cuneta"}""".formatted(DATE)));
        ok(publish(ownerToken, """
                {"stageId":"%s","entryDate":"%s","description":"Replanteo","unitId":"%s"}""".formatted(stage1, DATE, casa1)));

        assertThat(timeline()).hasSize(2);
        assertThat(firstStage(casa1).get("progressPercent").asString()).isEqualTo("0");
    }

    // --- Lo que se rechaza ---

    @Test
    @DisplayName("casa no asignada: 403 como hoy; otra organización: 404. No cambia nada")
    void foreignHouseAndOrganization() throws Exception {
        UUID otherStage = UUID.fromString(firstStage(casa2).get("id").asString());

        publish(supervisorToken, """
                {"stageId":"%s","entryDate":"%s","description":"x","unitId":"%s","stageUpdate":{"progressPercent":50}}"""
                .formatted(otherStage, DATE, casa2)).andExpect(status().isForbidden());
        publish(outsiderToken, """
                {"stageId":"%s","entryDate":"%s","description":"x","unitId":"%s","stageUpdate":{"progressPercent":50}}"""
                .formatted(stage1, DATE, casa1)).andExpect(status().isNotFound());

        assertThat(firstStage(casa2).get("progressPercent").asString()).isEqualTo("0");
        assertThat(firstStage(casa1).get("progressPercent").asString()).isEqualTo("0");
        assertThat(timeline()).isEmpty();
    }

    @Test
    @DisplayName("presupuesto o peso dentro de stageUpdate: 400, y la etapa no cambia")
    void stageUpdateCannotCarryBudgetOrWeight() throws Exception {
        publish(supervisorToken, """
                {"stageId":"%s","entryDate":"%s","description":"x","unitId":"%s",
                 "stageUpdate":{"progressPercent":50,"budgetAmount":1000000}}""".formatted(stage1, DATE, casa1))
                .andExpect(status().isBadRequest());
        publish(ownerToken, """
                {"stageId":"%s","entryDate":"%s","description":"x","unitId":"%s","stageUpdate":{"weight":40}}"""
                .formatted(stage1, DATE, casa1))
                .andExpect(status().isBadRequest());

        JsonNode stage = firstStage(casa1);
        assertThat(stage.get("progressPercent").asString()).isEqualTo("0");
        assertThat(stage.get("budgetAmount").isNull()).isTrue();
        assertThat(timeline()).isEmpty();
    }

    @Test
    @DisplayName("stageUpdate sin etapa, o volviendo la etapa a pendiente: 400")
    void stageUpdateNeedsStageAndCannotGoBackToPending() throws Exception {
        publish(supervisorToken, """
                {"stageId":null,"entryDate":"%s","description":"x","unitId":"%s","stageUpdate":{"progressPercent":50}}"""
                .formatted(DATE, casa1)).andExpect(status().isBadRequest());
        publish(supervisorToken, """
                {"stageId":"%s","entryDate":"%s","description":"x","unitId":"%s","stageUpdate":{"status":"PENDING"}}"""
                .formatted(stage1, DATE, casa1)).andExpect(status().isBadRequest());

        assertThat(timeline()).isEmpty();
    }

    @Test
    @DisplayName("la fecha del avance no puede ser futura, para el dueño ni para el encargado")
    void futureDateIsRejected() throws Exception {
        String tomorrow = LocalDate.now().plusDays(1).toString();

        publish(supervisorToken, """
                {"entryDate":"%s","description":"x","unitId":"%s"}""".formatted(tomorrow, casa1))
                .andExpect(status().isBadRequest());
        publish(ownerToken, """
                {"entryDate":"%s","description":"x"}""".formatted(tomorrow))
                .andExpect(status().isBadRequest());
        ok(publish(ownerToken, """
                {"entryDate":"%s","description":"Hoy sí"}""".formatted(LocalDate.now())));

        assertThat(timeline()).hasSize(1);
    }

    // --- Fallas a mitad de camino: no cambia nada ---

    @Test
    @DisplayName("foto de otra casa: 400 y no cambia ni el avance, ni la etapa, ni la foto")
    void photoFromAnotherHouse() throws Exception {
        UUID foreign = pendingPhoto(ownerToken, casa2, "otra-casa.jpg");

        publishWithStageUpdate(ownerToken, foreign).andExpect(status().isBadRequest());

        assertNothingChanged(foreign);
    }

    @Test
    @DisplayName("foto de otro usuario: 400 y no cambia nada")
    void photoFromAnotherUser() throws Exception {
        UUID supervisors = pendingPhoto(supervisorToken, casa1, "del-encargado.jpg");

        publishWithStageUpdate(ownerToken, supervisors).andExpect(status().isBadRequest());

        assertNothingChanged(supervisors);
    }

    @Test
    @DisplayName("foto que no está en el almacenamiento: 400 y no cambia nada")
    void photoMissingInStorage() throws Exception {
        UUID ok = pendingPhoto(supervisorToken, casa1, "subida.jpg");
        UUID missing = pendingPhoto(supervisorToken, casa1, "cortada.jpg");
        MediaAsset missingAsset = mediaRepository.findById(missing).orElseThrow();
        when(storageService.exists(missingAsset.getStorageKey())).thenReturn(false);

        publish(supervisorToken, """
                {"stageId":"%s","entryDate":"%s","description":"x","unitId":"%s",
                 "photoIds":["%s","%s"],"stageUpdate":{"progressPercent":50,"status":"IN_PROGRESS"}}"""
                .formatted(stage1, DATE, casa1, ok, missing)).andExpect(status().isBadRequest());

        assertNothingChanged(ok);
        assertNothingChanged(missing);
    }

    @Test
    @DisplayName("etapa de otra casa: 400 y no cambia nada, ni la etapa ajena")
    void stageFromAnotherHouse() throws Exception {
        UUID photo = pendingPhoto(ownerToken, casa1, "propia.jpg");
        UUID otherStage = UUID.fromString(firstStage(casa2).get("id").asString());

        publish(ownerToken, """
                {"stageId":"%s","entryDate":"%s","description":"x","unitId":"%s",
                 "photoIds":["%s"],"stageUpdate":{"progressPercent":50,"status":"IN_PROGRESS"}}"""
                .formatted(otherStage, DATE, casa1, photo)).andExpect(status().isBadRequest());

        assertNothingChanged(photo);
        assertThat(firstStage(casa2).get("progressPercent").asString()).isEqualTo("0");
        assertThat(firstStage(casa2).get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("hasta 10 fotos por avance: con 11, 400 y no cambia nada; con 10, se publica")
    void atMostTenPhotos() throws Exception {
        List<UUID> photos = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            photos.add(pendingPhoto(supervisorToken, casa1, "foto-" + i + ".jpg"));
        }
        String ids = photos.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));

        publish(supervisorToken, """
                {"stageId":"%s","entryDate":"%s","description":"x","unitId":"%s",
                 "photoIds":[%s],"stageUpdate":{"progressPercent":50,"status":"IN_PROGRESS"}}"""
                .formatted(stage1, DATE, casa1, ids)).andExpect(status().isBadRequest());

        assertNothingChanged(photos.getFirst());

        String ten = photos.subList(0, 10).stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        publish(supervisorToken, """
                {"stageId":"%s","entryDate":"%s","description":"x","unitId":"%s","photoIds":[%s]}"""
                .formatted(stage1, DATE, casa1, ten)).andExpect(status().is2xxSuccessful());
    }

    // --- Armado ---

    private ResultActions publishWithStageUpdate(String token, UUID photoId) throws Exception {
        return publish(token, """
                {"stageId":"%s","entryDate":"%s","description":"x","unitId":"%s",
                 "photoIds":["%s"],"stageUpdate":{"progressPercent":50,"status":"IN_PROGRESS"}}"""
                .formatted(stage1, DATE, casa1, photoId));
    }

    /** El avance no existe, la etapa sigue como estaba y la foto sigue pendiente y suelta. */
    private void assertNothingChanged(UUID photoId) throws Exception {
        assertThat(timeline()).isEmpty();
        JsonNode stage = firstStage(casa1);
        assertThat(stage.get("progressPercent").asString()).isEqualTo("0");
        assertThat(stage.get("status").asString()).isEqualTo("PENDING");
        assertThat(stage.get("startedOn").isNull()).isTrue();
        MediaAsset asset = mediaRepository.findById(photoId).orElseThrow();
        assertThat(asset.getStatus()).isEqualTo(MediaStatus.PENDING);
        assertThat(asset.getProgressEntryId()).isNull();
    }

    private ResultActions publish(String token, String body) throws Exception {
        return send(token, post("/api/developments/" + developmentId + "/progress"), body);
    }

    private UUID pendingPhoto(String token, UUID unitId, String filename) throws Exception {
        String response = ok(send(token, post("/api/developments/" + developmentId + "/media/upload-url"), """
                {"filename":"%s","contentType":"image/jpeg","unitId":"%s"}""".formatted(filename, unitId)));
        return UUID.fromString(json(response).get("mediaId").asString());
    }

    private JsonNode timeline() throws Exception {
        return json(ok(send(ownerToken, get("/api/developments/" + developmentId + "/progress"))));
    }

    private JsonNode firstStage(UUID unitId) throws Exception {
        JsonNode stages = json(ok(send(ownerToken, get("/api/units/" + unitId + "/stages"))));
        JsonNode first = stages.get(0);
        for (JsonNode stage : stages) {
            if (Integer.parseInt(stage.get("orderIndex").asString()) < Integer.parseInt(first.get("orderIndex").asString())) {
                first = stage;
            }
        }
        return first;
    }

    private JsonNode publicView(UUID unitId) throws Exception {
        String link = ok(send(ownerToken, post("/api/units/" + unitId + "/share-links"), """
                {"label":"Familia"}"""));
        String token = json(link).get("token").asString();
        return json(mockMvc.perform(get("/api/public/share/" + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
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

    private static UUID idOf(String body) {
        return UUID.fromString(JSON.readTree(body).get("id").asString());
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }
}
