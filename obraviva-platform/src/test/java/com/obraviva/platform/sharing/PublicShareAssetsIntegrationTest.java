package com.obraviva.platform.sharing;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.captures3d.Capture3d;
import com.obraviva.platform.captures3d.Capture3dRepository;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.developments.CreateUnitRequest;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
import com.obraviva.platform.sharing.ShareLinkDtos.CreateShareLinkRequest;
import com.obraviva.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recorridos 3D en la vista pública, con foco en la vigencia de las URLs firmadas.
 *
 * <p>El almacenamiento se mockea: lo que se verifica es con qué vigencia se firma,
 * que es una decisión de seguridad, y no que MinIO sepa firmar.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false",
        "obraviva.sharing.asset-url-ttl=5m"
})
class PublicShareAssetsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private Capture3dRepository captureRepository;
    @Autowired private ShareLinkRepository shareLinkRepository;

    private String token;
    private UUID userId;
    private UUID organizationId;
    private UUID developmentId;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        shareLinkRepository.deleteAll();

        when(storageService.presignDownload(anyString(), any(Duration.class)))
                .thenReturn("https://almacenamiento.test/firmada");

        String email = "assets-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(
                new User(email, passwordEncoder.encode(PASSWORD), "Usuario Assets"));
        userId = user.getId();
        token = login(email);

        organizationId = UUID.fromString(JSON.readTree(
                perform(post("/api/organizations"), new CreateOrganizationRequest("Constructora Assets")))
                .get("id").asString());

        developmentId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments"),
                        new CreateDevelopmentRequest(organizationId, "Torre Assets", null)))
                .get("id").asString());

        unitId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments/" + developmentId + "/units"),
                        new CreateUnitRequest("3C", "Tres dormitorios")))
                .get("id").asString());

        Capture3d capture = new Capture3d(
                organizationId, developmentId, null, null,
                LocalDate.of(2026, 8, 20), "Recorrido del segundo piso", userId);
        capture.markAvailable();
        captureRepository.save(capture);
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
                        .content(JSON.writeValueAsString(new CreateShareLinkRequest(null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("token").asString();
    }

    @Test
    @DisplayName("la vista pública entrega el recorrido con su transformación")
    void publicViewIncludesCaptures() throws Exception {
        mockMvc.perform(get("/api/public/share/" + issueLink()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captures[0].date").value("2026-08-20"))
                .andExpect(jsonPath("$.captures[0].description").value("Recorrido del segundo piso"))
                .andExpect(jsonPath("$.captures[0].splatUrl").value("https://almacenamiento.test/firmada"))
                .andExpect(jsonPath("$.captures[0].transform.scale").exists());
    }

    @Test
    @DisplayName("las URLs públicas se firman con vigencia corta")
    void publicUrlsAreSignedWithShortTtl() throws Exception {
        mockMvc.perform(get("/api/public/share/" + issueLink()))
                .andExpect(status().isOk());

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(storageService, atLeastOnce()).presignDownload(anyString(), ttl.capture());

        // Una URL firmada sobrevive a la revocación del enlace: la vigencia es lo
        // único que acota esa ventana, así que no puede ser la larga del panel.
        assertThat(ttl.getValue()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("la vista pública sigue sin exponer identificadores internos")
    void assetsDoNotLeakIdentifiers() throws Exception {
        String body = mockMvc.perform(get("/api/public/share/" + issueLink()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain(unitId.toString())
                .doesNotContain(developmentId.toString())
                .doesNotContain(organizationId.toString());
    }
}