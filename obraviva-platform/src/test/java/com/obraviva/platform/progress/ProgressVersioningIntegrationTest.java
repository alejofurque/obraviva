package com.obraviva.platform.progress;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
import com.obraviva.platform.support.TestMinio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc // resolver import con quick-fix
@Testcontainers
class ProgressVersioningIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        TestMinio.register(registry);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String token;
    private UUID developmentId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "progress-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Avances"));
        token = login(email);

        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateOrganizationRequest("Constructora Avances"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Obra con avances", null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        developmentId = UUID.fromString(JSON.readTree(devResponse).get("id").asString());
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

    private String createEntry(String description) throws Exception {
        return mockMvc.perform(post("/api/developments/" + developmentId + "/progress")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateProgressEntryRequest(
                                null, LocalDate.of(2026, 8, 20), description))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String correct(UUID entryId, String description) throws Exception {
        return mockMvc.perform(post("/api/progress/" + entryId + "/corrections")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new UpdateProgressEntryRequest(
                                null, LocalDate.of(2026, 8, 20), description))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void createsEntryAsFirstVersion() throws Exception {
        String response = createEntry("Se hormigonó la losa del primer piso");
        mockMvc.perform(get("/api/developments/" + developmentId + "/progress")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].current").value(true));
        org.assertj.core.api.Assertions
                .assertThat(JSON.readTree(response).get("source").asString())
                .isEqualTo("MANUAL");
    }

    @Test
    void correctionCreatesNewVersionAndTimelineShowsOnlyCurrent() throws Exception {
        UUID entryId = UUID.fromString(
                JSON.readTree(createEntry("Loza del primer piso")).get("id").asString());

        correct(entryId, "Losa del primer piso (corregido)");

        // La línea de tiempo muestra una sola entrada: la vigente, versión 2
        mockMvc.perform(get("/api/developments/" + developmentId + "/progress")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].description").value("Losa del primer piso (corregido)"));
    }

    @Test
    void cannotCorrectSupersededVersion() throws Exception {
        UUID entryId = UUID.fromString(
                JSON.readTree(createEntry("Original")).get("id").asString());
        correct(entryId, "Corregido");

        // Intentar corregir la v1, ya superada
        mockMvc.perform(post("/api/progress/" + entryId + "/corrections")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new UpdateProgressEntryRequest(
                                null, LocalDate.of(2026, 8, 20), "Tarde"))))
                .andExpect(status().isConflict());
    }
}