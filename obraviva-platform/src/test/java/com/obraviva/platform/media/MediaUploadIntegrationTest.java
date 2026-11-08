package com.obraviva.platform.media;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
import com.obraviva.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc // resolver import con quick-fix
@Testcontainers
@TestPropertySource(properties = "obraviva.storage.init-bucket=false")
class MediaUploadIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @MockitoBean
    private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String token;
    private UUID developmentId;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString()))
                .thenReturn("http://localhost:9000/fake-upload-url");
        when(storageService.presignDownload(anyString()))
                .thenReturn("http://localhost:9000/fake-download-url");

        String email = "media-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Media"));
        token = login(email);

        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateOrganizationRequest("Constructora Media"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Obra con fotos", null))))
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

    private UUID requestUploadUrl(String filename, String contentType) throws Exception {
        String response = mockMvc.perform(
                        post("/api/developments/" + developmentId + "/media/upload-url")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(
                                        new RequestUploadRequest(filename, contentType, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(response).get("mediaId").asString());
    }

    @Test
    void rejectsUnsupportedContentType() throws Exception {
        mockMvc.perform(post("/api/developments/" + developmentId + "/media/upload-url")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new RequestUploadRequest("planilla.xlsx",
                                        "application/vnd.ms-excel", null))))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void pendingMediaIsNotListed() throws Exception {
        requestUploadUrl("frente.jpg", "image/jpeg");

        mockMvc.perform(get("/api/developments/" + developmentId + "/media")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void confirmFailsWhenObjectIsMissingInStorage() throws Exception {
        UUID mediaId = requestUploadUrl("frente.jpg", "image/jpeg");
        when(storageService.exists(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/media/" + mediaId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void confirmedMediaBecomesAvailableAndListed() throws Exception {
        UUID mediaId = requestUploadUrl("frente.jpg", "image/jpeg");
        when(storageService.exists(anyString())).thenReturn(true);

        mockMvc.perform(post("/api/media/" + mediaId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.kind").value("PHOTO"))
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty());

        mockMvc.perform(get("/api/developments/" + developmentId + "/media")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].originalFilename").value("frente.jpg"));
    }
}