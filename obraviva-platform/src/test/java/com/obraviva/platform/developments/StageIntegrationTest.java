package com.obraviva.platform.developments;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc // resolver import con quick-fix
@Testcontainers
class StageIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String token;
    private UUID developmentId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "stages-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Etapas"));
        token = login(email);

        // Organización por endpoint: el creador queda OWNER automáticamente
        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new com.obraviva.platform.organizations.CreateOrganizationRequest(
                                        "Constructora Etapas"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Obra con etapas", null))))
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

    private String createStage(String name, int orderIndex) throws Exception {
        return mockMvc.perform(post("/api/developments/" + developmentId + "/stages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateStageRequest(name, orderIndex))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void createsStageWithPendingStatus() throws Exception {
        String response = createStage("Excavación", 0);
        String status = JSON.readTree(response).get("status").asString();
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void listsStagesOrderedByIndex() throws Exception {
        createStage("Terminaciones", 2);
        createStage("Excavación", 0);
        createStage("Estructura", 1);

        mockMvc.perform(get("/api/developments/" + developmentId + "/stages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Excavación"))
                .andExpect(jsonPath("$[1].name").value("Estructura"))
                .andExpect(jsonPath("$[2].name").value("Terminaciones"));
    }

    @Test
    void rejectsDuplicateOrderIndex() throws Exception {
        createStage("Excavación", 0);

        mockMvc.perform(post("/api/developments/" + developmentId + "/stages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateStageRequest("Otra", 0))))
                .andExpect(status().isConflict());
    }

    @Test
    void updatesStageStatusToDelayed() throws Exception {
        String response = createStage("Estructura", 0);
        UUID stageId = UUID.fromString(JSON.readTree(response).get("id").asString());

        mockMvc.perform(patch("/api/developments/stages/" + stageId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new UpdateStageStatusRequest(StageStatus.DELAYED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELAYED"));
    }
}