package com.obraviva.platform.sharing;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.developments.CreateStageRequest;
import com.obraviva.platform.developments.CreateUnitRequest;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
import com.obraviva.platform.progress.CreateProgressEntryRequest;
import com.obraviva.platform.sharing.ShareLinkDtos.CreateShareLinkRequest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de aislamiento de los enlaces públicos.
 *
 * <p>Son obligatorias por el documento maestro y no opcionales: un error acá expone
 * datos privados a cualquiera con un enlace. Los casos intentan explícitamente ver lo
 * que no corresponde y verifican que falle.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class PublicShareIsolationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ShareLinkRepository shareLinkRepository;

    private String token;
    private UUID userId;
    private UUID organizationId;
    private UUID developmentId;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        shareLinkRepository.deleteAll();

        String email = "sharing-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(
                new User(email, passwordEncoder.encode(PASSWORD), "Usuario Sharing"));
        userId = user.getId();
        token = login(email);

        organizationId = UUID.fromString(JSON.readTree(
                perform(post("/api/organizations"), new CreateOrganizationRequest("Constructora Sharing")))
                .get("id").asString());

        developmentId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments"),
                        new CreateDevelopmentRequest(organizationId, "Torre Compartida", "Bv. Test 100")))
                .get("id").asString());

        unitId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments/" + developmentId + "/units"),
                        new CreateUnitRequest("2B", "Dos dormitorios al frente")))
                .get("id").asString());

        perform(post("/api/developments/" + developmentId + "/stages"),
                new CreateStageRequest("Estructura", 0));

        perform(post("/api/developments/" + developmentId + "/progress"),
                new CreateProgressEntryRequest(null, LocalDate.of(2026, 8, 12), "Se hormigono la losa."));
    }

    private String perform(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return mockMvc.perform(builder
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
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

    private String issueLink() throws Exception {
        String response = mockMvc.perform(post("/api/units/" + unitId + "/share-links")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateShareLinkRequest("familia", null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("token").asString();
    }

    private UUID linkIdOf(String shareToken) {
        return shareLinkRepository.findByTokenHash(ShareToken.hash(shareToken))
                .orElseThrow().getId();
    }

    private String intruderToken() throws Exception {
        String email = "intruso-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Ajeno"));
        return login(email);
    }

    @Test
    @DisplayName("el enlace se abre sin sesión y muestra la obra")
    void publicViewWorksWithoutSession() throws Exception {
        String shareToken = issueLink();

        mockMvc.perform(get("/api/public/share/" + shareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitCode").value("2B"))
                .andExpect(jsonPath("$.developmentName").value("Torre Compartida"))
                .andExpect(jsonPath("$.stages[0].name").value("Estructura"))
                .andExpect(jsonPath("$.timeline[0].description").value("Se hormigono la losa."));
    }

    @Test
    @DisplayName("la vista pública no expone ningún identificador interno")
    void publicViewLeaksNoInternalIdentifiers() throws Exception {
        String shareToken = issueLink();

        String body = mockMvc.perform(get("/api/public/share/" + shareToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Con estos valores, quien tenga el enlace podría probar contra los endpoints
        // autenticados. No los necesita para nada.
        assertThat(body)
                .doesNotContain(unitId.toString())
                .doesNotContain(developmentId.toString())
                .doesNotContain(organizationId.toString());
    }

    @Test
    @DisplayName("el token sólo existe en la respuesta de creación, nunca en el listado")
    void tokenIsShownOnceAndNeverListed() throws Exception {
        String shareToken = issueLink();

        String listed = mockMvc.perform(get("/api/units/" + unitId + "/share-links")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(listed).doesNotContain(shareToken);
        // Tampoco el hash: no hace falta para nada fuera de la base.
        assertThat(listed).doesNotContain(ShareToken.hash(shareToken));
    }

    @Test
    @DisplayName("un enlace revocado deja de servir en el acto")
    void revokedLinkStopsWorking() throws Exception {
        String shareToken = issueLink();

        mockMvc.perform(post("/api/share-links/" + linkIdOf(shareToken) + "/revoke")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/public/share/" + shareToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un enlace vencido deja de servir sin que nadie lo revoque")
    void expiredLinkStopsWorking() throws Exception {
        String rawToken = ShareToken.generate();
        shareLinkRepository.save(new ShareLink(
                organizationId,
                developmentId,
                unitId,
                ShareToken.hash(rawToken),
                "vencido",
                Instant.now().minus(Duration.ofMinutes(1)),
                userId));

        mockMvc.perform(get("/api/public/share/" + rawToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un token inexistente responde igual que uno revocado")
    void unknownTokenIsIndistinguishable() throws Exception {
        String shareToken = issueLink();
        mockMvc.perform(post("/api/share-links/" + linkIdOf(shareToken) + "/revoke")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // El mensaje se lee de getErrorMessage y no del cuerpo: MockMvc no ejecuta el
        // reenvío interno a /error, que es donde Spring Boot arma el JSON con el
        // campo message. En el servidor real ese cuerpo sí llega; acá el texto de la
        // ResponseStatusException queda en la respuesta simulada.
        String revokedMessage = mockMvc.perform(get("/api/public/share/" + shareToken))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getErrorMessage();

        String unknownMessage = mockMvc.perform(get("/api/public/share/" + ShareToken.generate()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getErrorMessage();

        // Mismo mensaje: quien prueba tokens no puede deducir cuál existió.
        assertThat(revokedMessage).isNotBlank().isEqualTo(unknownMessage);
    }

    @Test
    @DisplayName("no se puede emitir un enlace para una unidad ajena")
    void cannotIssueLinkForForeignUnit() throws Exception {
        mockMvc.perform(post("/api/units/" + unitId + "/share-links")
                        .header("Authorization", "Bearer " + intruderToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateShareLinkRequest(null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("no se puede revocar el enlace de otra organización")
    void cannotRevokeForeignLink() throws Exception {
        String shareToken = issueLink();

        mockMvc.perform(post("/api/share-links/" + linkIdOf(shareToken) + "/revoke")
                        .header("Authorization", "Bearer " + intruderToken()))
                .andExpect(status().isNotFound());

        // Y el enlace sigue sirviendo: el intento ajeno no tuvo efecto.
        mockMvc.perform(get("/api/public/share/" + shareToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("las visitas quedan contadas")
    void visitsAreCounted() throws Exception {
        String shareToken = issueLink();

        mockMvc.perform(get("/api/public/share/" + shareToken)).andExpect(status().isOk());
        mockMvc.perform(get("/api/public/share/" + shareToken)).andExpect(status().isOk());

        assertThat(shareLinkRepository.findByTokenHash(ShareToken.hash(shareToken))
                .orElseThrow().getAccessCount()).isEqualTo(2);
    }
}