package com.obraviva.platform.media;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Agregar fotos" a un avance que ya existe: sube de a tandas y no pasa por la publicación, que
 * ya pone el tope en el pedido. Acá el servidor cuenta las fotos de todo el avance (todas sus
 * versiones) al pedir la subida y otra vez al confirmarla.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "obraviva.storage.init-bucket=false")
class EntryPhotoLimitIntegrationTest {

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
    @Autowired private MediaAssetRepository mediaRepository;

    private String token;
    private UUID developmentId;
    private UUID entryId;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("http://localhost:9000/subida");
        when(storageService.presignDownload(anyString())).thenReturn("http://localhost:9000/objeto");
        when(storageService.exists(anyString())).thenReturn(true);

        String email = "fotos-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Fotos"));
        token = login(email);
        UUID organizationId = idOf(ok(send(post("/api/organizations"),
                JSON.writeValueAsString(new CreateOrganizationRequest("Constructora Fotos")))));
        developmentId = idOf(ok(send(post("/api/developments"),
                JSON.writeValueAsString(new CreateDevelopmentRequest(organizationId, "Obra con tope", null)))));
        entryId = idOf(ok(send(post("/api/developments/" + developmentId + "/progress"), """
                {"entryDate":"%s","description":"Hormigonado de la platea"}""".formatted(LocalDate.now()))));
    }

    @Test
    @DisplayName("con 9 fotos, la décima entra y queda publicada")
    void tenthPhotoFits() throws Exception {
        addPhotos(entryId, 9);

        UUID tenth = idOf(requestUpload(entryId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "mediaId");
        send(post("/api/media/" + tenth + "/confirm"), "{}").andExpect(status().isOk());

        assertThat(availablePhotos()).isEqualTo(10);
    }

    @Test
    @DisplayName("con 10 fotos, la undécima se rechaza con 400 y no se guarda nada, también desde una versión corregida")
    void eleventhPhotoIsRejected() throws Exception {
        addPhotos(entryId, 10);
        long rows = mediaRepository.count();

        String message = requestUpload(entryId).andExpect(status().isBadRequest())
                .andReturn().getResponse().getErrorMessage();

        assertThat(message).isEqualTo("El avance ya tiene 10 fotos, el máximo");
        assertThat(mediaRepository.count()).isEqualTo(rows);

        // La corrección es otra versión del mismo avance: las fotos de la anterior cuentan.
        UUID corrected = idOf(ok(send(post("/api/progress/" + entryId + "/corrections"), """
                {"entryDate":"%s","description":"Hormigonado de la platea y vigas"}""".formatted(LocalDate.now()))));
        requestUpload(corrected).andExpect(status().isBadRequest());
        assertThat(mediaRepository.count()).isEqualTo(rows);
    }

    @Test
    @DisplayName("dos subidas que arrancan con 9: la primera que confirma entra; la otra, 400 y no se publica")
    void confirmChecksAgain() throws Exception {
        addPhotos(entryId, 9);
        UUID first = idOf(requestUpload(entryId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "mediaId");
        UUID second = idOf(requestUpload(entryId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "mediaId");

        send(post("/api/media/" + first + "/confirm"), "{}").andExpect(status().isOk());
        send(post("/api/media/" + second + "/confirm"), "{}").andExpect(status().isBadRequest());

        assertThat(availablePhotos()).isEqualTo(10);
        assertThat(mediaRepository.findById(second).orElseThrow().getStatus()).isEqualTo(MediaStatus.PENDING);
    }

    // --- Armado ---

    private void addPhotos(UUID entry, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            UUID mediaId = idOf(requestUpload(entry).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "mediaId");
            send(post("/api/media/" + mediaId + "/confirm"), "{}").andExpect(status().isOk());
        }
    }

    private ResultActions requestUpload(UUID entry) throws Exception {
        return send(post("/api/developments/" + developmentId + "/media/upload-url"), """
                {"filename":"foto.jpg","contentType":"image/jpeg","progressEntryId":"%s"}""".formatted(entry));
    }

    private long availablePhotos() {
        return mediaRepository.findAll().stream()
                .filter(asset -> developmentId.equals(asset.getDevelopmentId()) && asset.getStatus() == MediaStatus.AVAILABLE)
                .count();
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

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String body)
            throws Exception {
        return mockMvc.perform(builder
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String ok(ResultActions result) throws Exception {
        return result.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static UUID idOf(String body) {
        return idOf(body, "id");
    }

    private static UUID idOf(String body, String field) {
        return UUID.fromString(JSON.readTree(body).get(field).asString());
    }
}
