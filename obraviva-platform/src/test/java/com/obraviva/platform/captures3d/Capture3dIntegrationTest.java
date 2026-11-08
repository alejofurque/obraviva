package com.obraviva.platform.captures3d;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureRequest;
import com.obraviva.platform.captures3d.CaptureDtos.TransformDto;
import com.obraviva.platform.captures3d.CaptureDtos.UpdateTransformRequest;
import com.obraviva.platform.captures3d.CaptureDtos.Vector3;
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

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = "obraviva.storage.init-bucket=false")
class Capture3dIntegrationTest {

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
                .thenReturn("http://localhost:9000/fake-upload");
        when(storageService.presignDownload(anyString()))
                .thenReturn("http://localhost:9000/fake-download");

        String email = "captures-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Capturas"));
        token = login(email);

        String orgResponse = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateOrganizationRequest("Constructora Capturas"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JSON.readTree(orgResponse).get("id").asString());

        String devResponse = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Obra con capturas", null))))
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

    private UUID createCapture(LocalDate date, String description) throws Exception {
        String response = mockMvc.perform(post("/api/developments/" + developmentId + "/captures")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new CreateCaptureRequest(date, description, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(response).get("captureId").asString());
    }

    private void confirm(UUID captureId) throws Exception {
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.size(anyString())).thenReturn(com.obraviva.platform.support.SpzFixtures.TYPICAL_SIZE);
        when(storageService.readPrefix(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(com.obraviva.platform.support.SpzFixtures.valid());
        mockMvc.perform(post("/api/captures/" + captureId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void newCaptureStartsWithIdentityTransform() throws Exception {
        UUID captureId = createCapture(LocalDate.of(2026, 8, 1), "Estado inicial");
        confirm(captureId);

        mockMvc.perform(get("/api/developments/" + developmentId + "/captures")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transform.scale").value(1.0))
                .andExpect(jsonPath("$[0].transform.rotationDeg.y").value(0.0))
                .andExpect(jsonPath("$[0].splatUrl").isNotEmpty());
    }

    @Test
    void confirmFailsWhenFileIsMissing() throws Exception {
        UUID captureId = createCapture(LocalDate.of(2026, 8, 1), "Sin archivo");
        when(storageService.exists(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/captures/" + captureId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void capturesAreListedChronologically() throws Exception {
        UUID second = createCapture(LocalDate.of(2026, 8, 20), "Segunda");
        confirm(second);
        UUID first = createCapture(LocalDate.of(2026, 8, 1), "Primera");
        confirm(first);

        mockMvc.perform(get("/api/developments/" + developmentId + "/captures")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].description").value("Primera"))
                .andExpect(jsonPath("$[1].description").value("Segunda"));
    }

    @Test
    void transformIsPersistedForAlignment() throws Exception {
        UUID captureId = createCapture(LocalDate.of(2026, 8, 20), "Para alinear");
        confirm(captureId);

        UpdateTransformRequest request = new UpdateTransformRequest(new TransformDto(
                new Vector3(-0.45, -0.45, 4.75),
                new Vector3(0, 20, 0),
                1.0));

        mockMvc.perform(patch("/api/captures/" + captureId + "/transform")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transform.rotationDeg.y").value(20.0));

        // Persistió: se recupera en el listado
        mockMvc.perform(get("/api/developments/" + developmentId + "/captures")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transform.position.z").value(4.75));
    }

    @Test
    void pendingCaptureIsNotListed() throws Exception {
        createCapture(LocalDate.of(2026, 8, 1), "Nunca subida");

        mockMvc.perform(get("/api/developments/" + developmentId + "/captures")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}