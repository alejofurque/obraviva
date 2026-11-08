package com.obraviva.platform.developments;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Códigos de unidad repetidos.
 *
 * <p>El caso venía devolviendo 500: la restricción de base explotaba sin que nadie la
 * atajara. Un tribunal que cargue dos unidades con el mismo código vería una pantalla
 * rota, y el frontend no puede hacer nada con un 500.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class UnitCodeUniquenessIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String token;
    private UUID organizationId;
    private UUID developmentId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "unidades-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Desarrollista"));
        token = login(email);

        organizationId = UUID.fromString(JSON.readTree(
                perform(post("/api/organizations"), new CreateOrganizationRequest("Constructora Unidades")))
                .get("id").asString());

        developmentId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments"),
                        new CreateDevelopmentRequest(organizationId, "Torre Unidades", null)))
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

    private MockHttpServletRequestBuilder createUnit(UUID development, String code) throws Exception {
        return post("/api/developments/" + development + "/units")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(new CreateUnitRequest(code, "Descripcion")));
    }

    @Test
    @DisplayName("un código repetido en la misma obra da 409, no 500")
    void duplicateCodeIsConflict() throws Exception {
        mockMvc.perform(createUnit(developmentId, "2B")).andExpect(status().isCreated());

        mockMvc.perform(createUnit(developmentId, "2B")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("el mismo código en otra obra es válido")
    void sameCodeInAnotherDevelopmentIsFine() throws Exception {
        mockMvc.perform(createUnit(developmentId, "2B")).andExpect(status().isCreated());

        UUID otherDevelopment = UUID.fromString(JSON.readTree(
                perform(post("/api/developments"),
                        new CreateDevelopmentRequest(organizationId, "Otra Torre", null)))
                .get("id").asString());

        // El 2B de una torre no tiene nada que ver con el 2B de la otra.
        mockMvc.perform(createUnit(otherDevelopment, "2B")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("los espacios alrededor del código no crean una unidad distinta")
    void codeIsTrimmed() throws Exception {
        mockMvc.perform(createUnit(developmentId, "2B")).andExpect(status().isCreated());

        mockMvc.perform(createUnit(developmentId, "  2B  ")).andExpect(status().isConflict());
    }
}