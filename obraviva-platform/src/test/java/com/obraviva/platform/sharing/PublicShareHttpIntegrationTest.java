package com.obraviva.platform.sharing;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.developments.CreateUnitRequest;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
import com.obraviva.platform.sharing.ShareLinkDtos.CreateShareLinkRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La vista pública contra un servidor HTTP real.
 *
 * <p>Existe porque MockMvc <strong>no</strong> ejecuta el reenvío interno a
 * {@code /error} con el que Spring arma el cuerpo de las respuestas de error. Esa
 * diferencia escondió un bug real: el 404 de un enlace revocado llegaba al navegador
 * como 401, porque Security rechazaba el reenvío al ser anónima la petición, y los
 * tests de MockMvc no lo veían.
 *
 * <p>El cliente es el {@link HttpClient} del JDK y no el de Spring, por el mismo
 * criterio que rige el adaptador del servicio de reconstrucción: su API es estable
 * desde Java 11 y no depende de la autoconfiguración de Boot, que reubicó las clases
 * de test entre versiones mayores. El puerto se lee de {@code local.server.port}, que
 * el contexto de test publica siempre, en vez de una anotación que también se movió.
 *
 * <p>El resto de la suite sigue usando MockMvc, que es más rápido. Acá se levanta un
 * servidor sólo para lo que depende del comportamiento del contenedor: los códigos que
 * ve un cliente sin sesión. Los datos se preparan con MockMvc, que comparte contexto y
 * base con ese servidor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class PublicShareHttpIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${local.server.port}") private int port;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ShareLinkRepository shareLinkRepository;

    private String token;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        shareLinkRepository.deleteAll();

        String email = "http-sharing-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario HTTP"));
        token = login(email);

        UUID organizationId = UUID.fromString(JSON.readTree(
                perform(post("/api/organizations"), new CreateOrganizationRequest("Constructora HTTP")))
                .get("id").asString());

        UUID developmentId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments"),
                        new CreateDevelopmentRequest(organizationId, "Torre HTTP", null)))
                .get("id").asString());

        unitId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments/" + developmentId + "/units"),
                        new CreateUnitRequest("1A", "Un dormitorio")))
                .get("id").asString());
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
                        .content(JSON.writeValueAsString(new CreateShareLinkRequest("familia", null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("token").asString();
    }

    /** Pedido anónimo, exactamente como lo haría un navegador con el enlace. */
    private HttpResponse<String> fetchPublic(String shareToken)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/public/share/" + shareToken))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("un enlace vigente responde 200 a un cliente sin sesión")
    void validLinkReturnsOk() throws Exception {
        HttpResponse<String> response = fetchPublic(issueLink());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("1A").contains("Torre HTTP");
    }

    @Test
    @DisplayName("un enlace revocado responde 404 y no 401")
    void revokedLinkReturnsNotFoundNotUnauthorized() throws Exception {
        String shareToken = issueLink();
        UUID linkId = shareLinkRepository.findByTokenHash(ShareToken.hash(shareToken))
                .orElseThrow().getId();

        mockMvc.perform(post("/api/share-links/" + linkId + "/revoke")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        HttpResponse<String> response = fetchPublic(shareToken);

        // 401 sería el síntoma de que el reenvío a /error quedó bloqueado por Security.
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("el error llega con su mensaje en el cuerpo, como lo ve un navegador")
    void errorBodyCarriesTheMessage() throws Exception {
        HttpResponse<String> response = fetchPublic(ShareToken.generate());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("no está disponible");
    }
}