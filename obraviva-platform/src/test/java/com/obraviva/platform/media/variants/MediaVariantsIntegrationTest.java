package com.obraviva.platform.media.variants;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.media.MediaAsset;
import com.obraviva.platform.media.MediaAssetRepository;
import com.obraviva.platform.media.MediaVariantsStatus;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.storage.StorageService;
import com.obraviva.platform.video.ffmpeg.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Las versiones livianas de punta a punta: la foto se publica al confirmar y se ve con el
 * original hasta que el worker deja listas la miniatura y la de pantalla; si falla, se sigue
 * viendo el original; y lo público nunca trae claves internas, sólo URLs firmadas.
 *
 * <p>El almacenamiento es un doble que guarda en disco; el ffmpeg es el real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class MediaVariantsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MediaAssetRepository mediaRepository;
    @Autowired private MediaVariantWorker worker;
    @Autowired private MediaVariantQueue queue;

    @TempDir
    Path dir;

    private final Set<String> uploaded = ConcurrentHashMap.newKeySet();
    private String ownerToken;
    private UUID developmentId;
    private UUID casa;

    @BeforeEach
    void setUp() throws Exception {
        mediaRepository.deleteAll();
        Path photo = dir.resolve("foto.png");
        ProcessRunner runner = new ProcessRunner();
        assertThat(runner.run(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
                "-i", "testsrc=size=2000x1500", "-frames:v", "1", photo.toString()), Duration.ofMinutes(1)).failed()).isFalse();

        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        // Una firma opaca, como la real: si una clave interna se colara en la respuesta, se vería.
        when(storageService.presignDownload(anyString())).thenAnswer(call -> signed(call.getArgument(0)));
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenAnswer(call -> signed(call.getArgument(0)));
        when(storageService.exists(anyString())).thenReturn(true);
        doAnswer(call -> Files.copy(photo, (Path) call.getArgument(1), StandardCopyOption.REPLACE_EXISTING))
                .when(storageService).download(anyString(), any(Path.class));
        doAnswer(call -> uploaded.add(call.getArgument(1))).when(storageService).upload(any(Path.class), anyString(), anyString());

        Organization organization = organizationRepository.save(new Organization("Casas Livianas"));
        ownerToken = login(createUser("duenio", organization));
        developmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio Liviano"}""".formatted(organization.getId()))));
        casa = UUID.fromString(json(ok(send(ownerToken, post("/api/developments/" + developmentId + "/units/batch"), """
                {"prefix":"Casa","firstNumber":1,"count":1,"withStandardStages":true}"""))).get(0).get("id").asString());
    }

    @Test
    @DisplayName("al confirmar se publica con el original; el worker deja listas la miniatura y la de pantalla")
    void confirmedPhotoGetsItsVariants() throws Exception {
        UUID photo = galleryPhoto("image/jpeg");
        assertThat(asset(photo).getVariantsStatus()).isEqualTo(MediaVariantsStatus.PENDING);
        // Una foto sin versiones todavía: la URL del original está y las otras no, y se usa esa.
        JsonNode before = publicShowcase().get(0);
        assertThat(before.get("url").isNull()).isFalse();
        assertThat(before.get("thumbUrl").isNull()).isTrue();
        assertThat(before.get("screenUrl").isNull()).isTrue();

        assertThat(worker.processPending()).isEqualTo(1);

        MediaAsset ready = asset(photo);
        assertThat(ready.getVariantsStatus()).isEqualTo(MediaVariantsStatus.READY);
        assertThat(ready.getThumbStorageKey()).endsWith(photo + "-thumb.jpg");
        assertThat(ready.getScreenStorageKey()).endsWith(photo + "-screen.jpg");
        assertThat(uploaded).contains(ready.getThumbStorageKey(), ready.getScreenStorageKey());
        assertThat(ready.getStorageKey()).doesNotContain("-thumb");

        JsonNode after = publicShowcase().get(0);
        assertThat(after.get("thumbUrl").asString()).isEqualTo(signed(ready.getThumbStorageKey()));
        assertThat(after.get("screenUrl").asString()).isEqualTo(signed(ready.getScreenStorageKey()));
        assertThat(after.get("url").asString()).isEqualTo(signed(ready.getStorageKey()));
        JsonNode owner = json(ok(send(ownerToken, get("/api/developments/" + developmentId + "/media")))).get(0);
        assertThat(owner.get("thumbUrl").asString()).isEqualTo(signed(ready.getThumbStorageKey()));
    }

    @Test
    @DisplayName("la vista pública trae URLs firmadas y nunca claves internas ni ids")
    void publicViewDoesNotExposeKeys() throws Exception {
        UUID photo = galleryPhoto("image/jpeg");
        worker.processPending();
        MediaAsset ready = asset(photo);

        String body = publicViewBody();

        assertThat(body)
                .doesNotContain(ready.getStorageKey())
                .doesNotContain(ready.getThumbStorageKey())
                .doesNotContain(ready.getScreenStorageKey())
                .doesNotContain("developments/")
                .doesNotContain("StorageKey")
                .doesNotContain(photo.toString())
                .doesNotContain("variants");
    }

    @Test
    @DisplayName("si la generación falla, la foto se sigue viendo con el original; al tercer intento queda FAILED")
    void failureFallsBackToTheOriginal() throws Exception {
        UUID photo = galleryPhoto("image/jpeg");
        doThrow(new RuntimeException("almacenamiento caído")).when(storageService).download(eq(asset(photo).getStorageKey()), any(Path.class));

        worker.processPending();
        assertThat(asset(photo).getVariantsStatus()).isEqualTo(MediaVariantsStatus.PENDING);
        assertThat(asset(photo).getVariantsAttempts()).isEqualTo(1);
        worker.processPending();
        worker.processPending();

        assertThat(asset(photo).getVariantsStatus()).isEqualTo(MediaVariantsStatus.FAILED);
        assertThat(asset(photo).getVariantsAttempts()).isEqualTo(3);
        // Fuera de la cola: no se reintenta para siempre.
        assertThat(worker.processPending()).isZero();
        JsonNode shown = publicShowcase().get(0);
        assertThat(shown.get("url").asString()).isEqualTo(signed(asset(photo).getStorageKey()));
        assertThat(shown.get("thumbUrl").isNull()).isTrue();
    }

    @Test
    @DisplayName("se puede cortar y retomar: un intento cortado queda contado y la foto sigue en la cola")
    void interruptedAttemptIsResumed() throws Exception {
        UUID photo = galleryPhoto("image/jpeg");
        // El proceso tomó la foto y se cortó antes de terminar.
        assertThat(queue.start(photo)).isPresent();
        assertThat(asset(photo).getVariantsAttempts()).isEqualTo(1);
        assertThat(asset(photo).getVariantsStatus()).isEqualTo(MediaVariantsStatus.PENDING);

        assertThat(worker.processPending()).isEqualTo(1);
        assertThat(asset(photo).getVariantsStatus()).isEqualTo(MediaVariantsStatus.READY);
        assertThat(asset(photo).getVariantsAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("un SVG no se achica: queda salteado y el worker ni lo baja")
    void svgIsSkipped() throws Exception {
        UUID render = galleryPhoto("image/svg+xml");

        assertThat(asset(render).getVariantsStatus()).isEqualTo(MediaVariantsStatus.SKIPPED);
        assertThat(worker.processPending()).isZero();
        verify(storageService, never()).download(eq(asset(render).getStorageKey()), any(Path.class));
    }

    @Test
    @DisplayName("borrar una foto borra también sus versiones")
    void deletingRemovesTheVariants() throws Exception {
        UUID photo = galleryPhoto("image/jpeg");
        worker.processPending();
        MediaAsset ready = asset(photo);

        send(ownerToken, delete("/api/media/" + photo)).andExpect(status().is2xxSuccessful());

        verify(storageService).delete(ready.getStorageKey());
        verify(storageService).delete(ready.getThumbStorageKey());
        verify(storageService).delete(ready.getScreenStorageKey());
    }

    // --- Armado ---

    private static String signed(String key) {
        return "https://almacenamiento.test/firmada/" + Integer.toHexString(key.hashCode());
    }

    private MediaAsset asset(UUID id) {
        return mediaRepository.findById(id).orElseThrow();
    }

    private UUID galleryPhoto(String contentType) throws Exception {
        UUID mediaId = idOf(ok(send(ownerToken, post("/api/developments/" + developmentId + "/media/upload-url"), """
                {"filename":"foto","contentType":"%s","unitId":"%s"}""".formatted(contentType, casa))), "mediaId");
        ok(send(ownerToken, post("/api/media/" + mediaId + "/confirm"), "{}"));
        return mediaId;
    }

    private JsonNode publicShowcase() throws Exception {
        return json(publicViewBody()).get("showcase");
    }

    private String publicViewBody() throws Exception {
        String token = json(ok(send(ownerToken, post("/api/units/" + casa + "/share-links"), """
                {"label":"Familia"}"""))).get("token").asString();
        return mockMvc.perform(get("/api/public/share/" + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String createUser(String prefix, Organization organization) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        memberRepository.save(new OrganizationMember(organization, user.getId(), OrganizationRole.OWNER));
        return email;
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

    private ResultActions send(String token, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    private ResultActions send(String token, MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String ok(ResultActions result) throws Exception {
        return result.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    private static UUID idOf(String body) {
        return idOf(body, "id");
    }

    private static UUID idOf(String body, String field) {
        return UUID.fromString(JSON.readTree(body).get(field).asString());
    }
}
