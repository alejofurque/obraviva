package com.obraviva.platform.sharing;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.developments.CreateUnitRequest;
import com.obraviva.platform.developments.UnitMemberDtos.AddUnitMemberRequest;
import com.obraviva.platform.developments.UnitMemberRepository;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Mi propiedad" y el control de enlaces por parte del comprador.
 *
 * <p>El punto de la tarea es que el comprador comparta su unidad sin pertenecer a la
 * organización, y que no pueda tocar nada más.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class MyPropertyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UnitMemberRepository memberRepository;
    @Autowired private ShareLinkRepository shareLinkRepository;

    private String developerToken;
    private String buyerToken;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        shareLinkRepository.deleteAll();
        memberRepository.deleteAll();

        String developerEmail = "dev-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(
                new User(developerEmail, passwordEncoder.encode(PASSWORD), "Desarrollista"));
        developerToken = login(developerEmail);

        String buyerEmail = "comprador-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(buyerEmail, passwordEncoder.encode(PASSWORD), "Compradora"));
        buyerToken = login(buyerEmail);

        UUID organizationId = UUID.fromString(JSON.readTree(
                perform(post("/api/organizations"), new CreateOrganizationRequest("Constructora Mia")))
                .get("id").asString());

        UUID developmentId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments"),
                        new CreateDevelopmentRequest(organizationId, "Torre Mia", "Calle Falsa 123")))
                .get("id").asString());

        unitId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments/" + developmentId + "/units"),
                        new CreateUnitRequest("5E", "Cinco ambientes")))
                .get("id").asString());

        perform(post("/api/units/" + unitId + "/members"), new AddUnitMemberRequest(buyerEmail));
    }

    private String perform(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return mockMvc.perform(builder
                        .header("Authorization", "Bearer " + developerToken)
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

    @Test
    @DisplayName("el comprador ve su unidad en mis unidades")
    void buyerSeesOwnedUnit() throws Exception {
        mockMvc.perform(get("/api/my-units")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].unitCode").value("5E"))
                .andExpect(jsonPath("$[0].developmentName").value("Torre Mia"));
    }

    @Test
    @DisplayName("el comprador abre su propiedad con la obra completa")
    void buyerOpensProperty() throws Exception {
        mockMvc.perform(get("/api/my-units/" + unitId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitId").value(unitId.toString()))
                .andExpect(jsonPath("$.property.unitCode").value("5E"))
                .andExpect(jsonPath("$.property.developmentAddress").value("Calle Falsa 123"));
    }

    @Test
    @DisplayName("el comprador emite y revoca el enlace de su propia unidad")
    void buyerSharesOwnUnit() throws Exception {
        String created = mockMvc.perform(post("/api/units/" + unitId + "/share-links")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateShareLinkRequest("mi familia", null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String shareToken = JSON.readTree(created).get("token").asString();
        UUID linkId = UUID.fromString(JSON.readTree(created).get("id").asString());

        mockMvc.perform(get("/api/public/share/" + shareToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/share-links/" + linkId + "/revoke")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/public/share/" + shareToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el comprador ve los enlaces de su unidad: sabe cuáles creó él, sin nombres del equipo")
    void buyerListsLinksOfOwnUnit() throws Exception {
        mockMvc.perform(post("/api/units/" + unitId + "/share-links")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateShareLinkRequest(null, null))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/units/" + unitId + "/share-links")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].createdByYou").value(true))
                .andExpect(jsonPath("$[0].createdByName").doesNotExist());
    }

    @Test
    @DisplayName("quien no compró nada no tiene propiedades")
    void strangerHasNoUnits() throws Exception {
        String strangerEmail = "ajeno-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(strangerEmail, passwordEncoder.encode(PASSWORD), "Ajeno"));
        String strangerToken = login(strangerEmail);

        mockMvc.perform(get("/api/my-units")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/my-units/" + unitId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el desarrollista también abre la unidad por su camino")
    void developerAlsoReachesTheUnit() throws Exception {
        mockMvc.perform(get("/api/my-units/" + unitId)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.property.unitCode").value("5E"));
    }
}