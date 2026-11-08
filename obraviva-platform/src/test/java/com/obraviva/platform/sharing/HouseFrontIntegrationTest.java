package com.obraviva.platform.sharing;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.comparisons.ProjectComparisonRepository;
import com.obraviva.platform.developments.UnitRepository;
import com.obraviva.platform.media.MediaAssetRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Tu casa, de frente" con el render del frente como dato de la casa.
 *
 * <p>Se prueba lo que el comprador ve y lo que no debe ver nunca: el render del frente se
 * compara con la foto de hoy del lugar marcado; sin render, se usa la comparación más
 * reciente de la casa; la galería nunca es la foto de hoy; y la marca de "Ver como el
 * comprador" sólo le llega a quien mira desde la organización.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class HouseFrontIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";
    private static final String SIGNED = "https://almacenamiento.test/";

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MediaAssetRepository mediaRepository;
    @Autowired private UnitRepository unitRepository;
    @Autowired private ProjectComparisonRepository comparisonRepository;

    private String ownerToken;
    private String supervisorToken;
    private String buyerToken;
    private UUID developmentId;
    private UUID casa1;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn(SIGNED + "subida");
        when(storageService.presignDownload(anyString())).thenAnswer(invocation -> SIGNED + invocation.getArgument(0));
        // Una URL por objeto: así se sabe qué foto y qué render eligió la vista.
        when(storageService.presignDownload(anyString(), any(Duration.class)))
                .thenAnswer(invocation -> SIGNED + invocation.getArgument(0));
        when(storageService.exists(anyString())).thenReturn(true);

        Organization organization = organizationRepository.save(new Organization("Casas del Frente"));
        ownerToken = login(createUser("duenio", organization, OrganizationRole.OWNER));
        String supervisorEmail = createUser("encargado", organization, OrganizationRole.MEMBER);
        UUID supervisorId = userRepository.findByEmail(supervisorEmail).orElseThrow().getId();
        supervisorToken = login(supervisorEmail);
        String buyerEmail = createUser("comprador", null, null);
        buyerToken = login(buyerEmail);

        developmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio del Frente"}""".formatted(organization.getId()))));
        JsonNode houses = json(ok(send(ownerToken, post("/api/developments/" + developmentId + "/units/batch"), """
                {"prefix":"Casa","firstNumber":1,"count":1,"withStandardStages":true}""")));
        casa1 = UUID.fromString(houses.get(0).get("id").asString());
        ok(send(ownerToken, post("/api/developments/" + developmentId + "/team"), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(supervisorId, casa1)));
        ok(send(ownerToken, post("/api/units/" + casa1 + "/members"), """
                {"email":"%s"}""".formatted(buyerEmail)));
    }

    @Test
    @DisplayName("render del frente más foto de hoy del frente: El proyecto compara los dos")
    void frontRenderAgainstTodaysPhoto() throws Exception {
        uploadFrontRender();
        UUID today = sessionPhoto("Frente", "2026-10-20");
        // La galería con el sector del frente, subida después, no es la foto de hoy.
        UUID gallery = galleryPhoto("Frente");

        JsonNode front = publicView().get("front");
        String render = SIGNED + unitRepository.findById(casa1).orElseThrow().getFrontRenderStorageKey();

        assertThat(front.get("sector").asString()).isEqualTo("Frente");
        assertThat(front.get("renderUrl").asString()).isEqualTo(render);
        assertThat(front.get("latest").get("url").asString()).isEqualTo(urlOf(today));
        assertThat(front.get("project").get("renderUrl").asString()).isEqualTo(render);
        assertThat(front.get("project").get("photoUrl").asString()).isEqualTo(urlOf(today));
        assertThat(front.get("project").get("photoDate").asString()).isEqualTo("2026-10-20");
        assertThat(front.toString()).doesNotContain(urlOf(gallery));
        // Una sola foto del frente: no hay "Cuando empezó".
        assertThat(front.get("first").isNull()).isTrue();
    }

    @Test
    @DisplayName("sin render del frente, El proyecto es la comparación más reciente de la casa, render contra su propia foto")
    void oldComparisonIsTheFallback() throws Exception {
        UUID entry = idOf(ok(send(ownerToken, post("/api/developments/" + developmentId + "/progress"), """
                {"entryDate":"2026-10-09","description":"Fachada revocada","unitId":"%s"}""".formatted(casa1))));
        UUID avance = idOf(ok(send(ownerToken, post(uploadUrl()), """
                {"filename":"fachada.jpg","contentType":"image/jpeg","progressEntryId":"%s"}""".formatted(entry))), "mediaId");
        ok(send(ownerToken, post("/api/media/" + avance + "/confirm"), "{}"));
        UUID comparison = idOf(ok(send(ownerToken, post("/api/developments/" + developmentId + "/comparisons"), """
                {"title":"Casa","unitId":"%s"}""".formatted(casa1))));
        ok(send(ownerToken, post("/api/comparisons/" + comparison + "/render/upload-url"), """
                {"filename":"render.jpg","contentType":"image/jpeg"}"""));
        ok(send(ownerToken, post("/api/comparisons/" + comparison + "/render/confirm"), ""));
        ok(send(ownerToken, put("/api/comparisons/" + comparison), """
                {"title":"Casa","unitId":"%s","photoMediaId":"%s"}""".formatted(casa1, avance)));
        sessionPhoto("Frente", "2026-10-20");

        JsonNode project = publicView().get("front").get("project");

        assertThat(project.get("renderUrl").asString()).isEqualTo(
                SIGNED + comparisonRepository.findById(comparison).orElseThrow().getRenderStorageKey());
        assertThat(project.get("photoUrl").asString()).isEqualTo(urlOf(avance));
        assertThat(project.get("photoDate").asString()).isEqualTo("2026-10-09");
    }

    @Test
    @DisplayName("sin nada cargado no hay qué comparar; la marca de vista previa le llega al dueño, nunca al comprador")
    void previewMarkOnlyForTheOrganization() throws Exception {
        JsonNode buyer = json(ok(send(buyerToken, get("/api/my-units/" + casa1))));
        JsonNode owner = json(ok(send(ownerToken, get("/api/my-units/" + casa1))));
        JsonNode supervisor = json(ok(send(supervisorToken, get("/api/my-units/" + casa1))));
        String share = publicViewRaw();

        assertThat(buyer.get("property").get("front").get("project").isNull()).isTrue();
        assertThat(buyer.get("property").get("front").get("latest").isNull()).isTrue();
        assertThat(buyer.get("preview").isNull()).isTrue();
        assertThat(owner.get("preview").get("developmentId").asString()).isEqualTo(developmentId.toString());
        assertThat(supervisor.get("preview").get("developmentId").asString()).isEqualTo(developmentId.toString());
        assertThat(share).doesNotContain("preview").doesNotContain(developmentId.toString());
    }

    @Test
    @DisplayName("el render del frente lo carga el dueño, y tiene que ser una imagen")
    void frontRenderRules() throws Exception {
        send(supervisorToken, post("/api/units/" + casa1 + "/front-render/upload-url"), """
                {"filename":"render.jpg","contentType":"image/jpeg"}""").andExpect(status().isForbidden());
        send(buyerToken, post("/api/units/" + casa1 + "/front-render/upload-url"), """
                {"filename":"render.jpg","contentType":"image/jpeg"}""").andExpect(status().isNotFound());
        send(ownerToken, post("/api/units/" + casa1 + "/front-render/upload-url"), """
                {"filename":"render.pdf","contentType":"application/pdf"}""").andExpect(status().isUnsupportedMediaType());
        send(ownerToken, post("/api/units/" + casa1 + "/front-render/confirm"), "").andExpect(status().isConflict());

        uploadFrontRender();
        assertThat(unitRepository.findById(casa1).orElseThrow().getFrontRenderStorageKey())
                .contains("/units/" + casa1 + "/front-render-");
        // La portada no se toca.
        assertThat(unitRepository.findById(casa1).orElseThrow().getCoverStorageKey()).isNull();
    }

    @Test
    @DisplayName("la galería es de venta y no entra en lugar por lugar; \"Mover a fotos de obra\" la pasa a su lugar con su fecha")
    void galleryIsForSaleAndMovesToWork() throws Exception {
        UUID gallery = galleryPhoto("Frente");
        JsonNode view = publicView();
        assertThat(view.get("showcase")).hasSize(1);
        assertThat(view.get("places").toString()).doesNotContain(urlOf(gallery));

        String path = "/api/media/" + gallery + "/to-work";
        send(supervisorToken, post(path), """
                {"sector":"Frente","takenOn":"2026-10-05"}""").andExpect(status().isForbidden());
        send(ownerToken, post(path), """
                {"sector":"Frente","takenOn":"2999-01-01"}""").andExpect(status().isBadRequest());
        send(ownerToken, post(path), """
                {"sector":null,"takenOn":"2026-10-05"}""").andExpect(status().isBadRequest());
        send(ownerToken, post(path), """
                {"sector":"Altillo","takenOn":"2026-10-05"}""").andExpect(status().isBadRequest());

        JsonNode moved = json(ok(send(ownerToken, post(path), """
                {"sector":"Frente","takenOn":"2026-10-05"}""")));
        assertThat(moved.get("showcase").asString()).isEqualTo("false");
        assertThat(moved.get("sector").asString()).isEqualTo("Frente");
        assertThat(moved.get("takenOn").asString()).isEqualTo("2026-10-05");
        view = publicView();
        assertThat(view.get("showcase")).isEmpty();
        assertThat(view.get("places").toString()).contains(urlOf(gallery));

        // Ya no es de la galería: no se vuelve a mover, y una foto de sesión tampoco.
        send(ownerToken, post(path), """
                {"sector":"Frente","takenOn":"2026-10-05"}""").andExpect(status().isConflict());
    }

    // --- Armado ---

    private void uploadFrontRender() throws Exception {
        ok(send(ownerToken, post("/api/units/" + casa1 + "/front-render/upload-url"), """
                {"filename":"render.jpg","contentType":"image/jpeg"}"""));
        ok(send(ownerToken, post("/api/units/" + casa1 + "/front-render/confirm"), ""));
    }

    private UUID sessionPhoto(String sector, String takenOn) throws Exception {
        UUID mediaId = idOf(ok(send(supervisorToken, post(uploadUrl()), """
                {"filename":"frente.jpg","contentType":"image/jpeg","unitId":"%s","sector":"%s","showcase":false,"takenOn":"%s"}"""
                .formatted(casa1, sector, takenOn))), "mediaId");
        ok(send(supervisorToken, post("/api/media/" + mediaId + "/confirm"), "{}"));
        return mediaId;
    }

    private UUID galleryPhoto(String sector) throws Exception {
        UUID mediaId = idOf(ok(send(ownerToken, post(uploadUrl()), """
                {"filename":"galeria.jpg","contentType":"image/jpeg","unitId":"%s","sector":"%s","showcase":true}"""
                .formatted(casa1, sector))), "mediaId");
        ok(send(ownerToken, post("/api/media/" + mediaId + "/confirm"), "{}"));
        return mediaId;
    }

    private String urlOf(UUID mediaId) {
        return SIGNED + mediaRepository.findById(mediaId).orElseThrow().getStorageKey();
    }

    private JsonNode publicView() throws Exception {
        return json(publicViewRaw());
    }

    private String publicViewRaw() throws Exception {
        String token = json(ok(send(ownerToken, post("/api/units/" + casa1 + "/share-links"), """
                {"label":"Familia"}"""))).get("token").asString();
        return mockMvc.perform(get("/api/public/share/" + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String uploadUrl() {
        return "/api/developments/" + developmentId + "/media/upload-url";
    }

    private String createUser(String prefix, Organization organization, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        if (organization != null) {
            memberRepository.save(new OrganizationMember(organization, user.getId(), role));
        }
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

    private static String ok(ResultActions result) throws Exception {
        return result.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    private static UUID idOf(String body) {
        return idOf(body, "id");
    }

    private static UUID idOf(String body, String field) {
        return UUID.fromString(JSON.readTree(body).get(field).asString());
    }
}
