package com.obraviva.platform.media.variants;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.common.process.ProcessRunner;
import com.obraviva.platform.media.MediaAssetRepository;
import com.obraviva.platform.media.MediaVariantsStatus;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Las versiones livianas de las imágenes que no son medios de obra, de punta a punta: la
 * portada y el render del frente de una casa, el render de una comparación y la portada del
 * desarrollo entran a la cola al confirmarse, se ven con el original hasta que el worker deja
 * sus versiones, y cada reemplazo o borrado descarta las versiones de la imagen que se va
 * (el original anterior se conserva, como siempre).
 *
 * <p>El almacenamiento es un doble; el ffmpeg es el real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class ImageVariantsIntegrationTest {

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
    @Autowired private ImageVariantRepository imageRepository;
    @Autowired private ImageVariants imageVariants;
    @Autowired private MediaVariantWorker worker;

    @TempDir
    Path dir;

    private String ownerToken;
    private UUID organizationId;
    private UUID developmentId;
    private UUID casa;

    @BeforeEach
    void setUp() throws Exception {
        mediaRepository.deleteAll();
        imageRepository.deleteAll();
        Path image = dir.resolve("imagen.png");
        ProcessRunner runner = new ProcessRunner();
        assertThat(runner.run(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
                "-i", "testsrc=size=2400x1600", "-frames:v", "1", image.toString()), Duration.ofMinutes(1)).failed()).isFalse();

        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenAnswer(call -> signed(call.getArgument(0)));
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenAnswer(call -> signed(call.getArgument(0)));
        when(storageService.exists(anyString())).thenReturn(true);
        doAnswer(call -> Files.copy(image, (Path) call.getArgument(1), StandardCopyOption.REPLACE_EXISTING))
                .when(storageService).download(anyString(), any(Path.class));

        Organization organization = organizationRepository.save(new Organization("Portadas Livianas"));
        organizationId = organization.getId();
        ownerToken = login(createUser(organization));
        developmentId = idOf(ok(send(post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio de Portadas"}""".formatted(organizationId))));
        casa = UUID.fromString(json(ok(send(post("/api/developments/" + developmentId + "/units/batch"), """
                {"prefix":"Casa","firstNumber":1,"count":1,"withStandardStages":true}"""))).get(0).get("id").asString());
    }

    @Test
    @DisplayName("la portada de la casa entra a la cola; el comprador ve el original hasta que el worker deja las versiones")
    void unitCoverGetsItsVariants() throws Exception {
        String cover = upload("/api/units/" + casa + "/cover", "portada.jpg", "image/jpeg");
        assertThat(variant(cover).getStatus()).isEqualTo(MediaVariantsStatus.PENDING);
        JsonNode before = publicView().get("details");
        assertThat(before.get("coverUrl").asString()).isEqualTo(signed(cover));
        assertThat(before.get("coverThumbUrl").isNull()).isTrue();
        assertThat(before.get("coverScreenUrl").isNull()).isTrue();

        assertThat(worker.processPending()).isEqualTo(1);

        ImageVariant ready = variant(cover);
        assertThat(ready.getStatus()).isEqualTo(MediaVariantsStatus.READY);
        assertThat(ready.getThumbStorageKey()).isEqualTo(cover.replace(".jpg", "-thumb.jpg"));
        assertThat(ready.getScreenStorageKey()).isEqualTo(cover.replace(".jpg", "-screen.jpg"));
        verify(storageService).upload(any(Path.class), eq(ready.getThumbStorageKey()), eq("image/jpeg"));
        JsonNode after = publicView().get("details");
        assertThat(after.get("coverThumbUrl").asString()).isEqualTo(signed(ready.getThumbStorageKey()));
        assertThat(after.get("coverScreenUrl").asString()).isEqualTo(signed(ready.getScreenStorageKey()));
        assertThat(after.get("coverUrl").asString()).isEqualTo(signed(cover));
        // El listado de casas del dueño también las trae.
        JsonNode listed = json(ok(send(get("/api/developments/" + developmentId + "/units")))).get(0);
        assertThat(listed.get("coverThumbUrl").asString()).isEqualTo(signed(ready.getThumbStorageKey()));
    }

    @Test
    @DisplayName("reemplazar la portada de la casa descarta las versiones de la anterior y conserva su original")
    void replacingUnitCoverDiscardsOldVariants() throws Exception {
        String first = upload("/api/units/" + casa + "/cover", "portada.jpg", "image/jpeg");
        worker.processPending();
        ImageVariant old = variant(first);

        String second = upload("/api/units/" + casa + "/cover", "otra.png", "image/png");

        assertDiscarded(old);
        assertThat(variant(second).getStatus()).isEqualTo(MediaVariantsStatus.PENDING);
    }

    @Test
    @DisplayName("el render del frente: versiones en la vista del comprador, y reemplazarlo descarta las del anterior")
    void frontRenderVariantsAndReplacement() throws Exception {
        String first = upload("/api/units/" + casa + "/front-render", "render.png", "image/png");
        assertThat(publicView().get("front").get("renderScreenUrl").isNull()).isTrue();
        worker.processPending();
        ImageVariant old = variant(first);
        JsonNode front = publicView().get("front");
        assertThat(front.get("renderUrl").asString()).isEqualTo(signed(first));
        assertThat(front.get("renderThumbUrl").asString()).isEqualTo(signed(old.getThumbStorageKey()));
        assertThat(front.get("renderScreenUrl").asString()).isEqualTo(signed(old.getScreenStorageKey()));

        String second = upload("/api/units/" + casa + "/front-render", "render-2.png", "image/png");

        assertDiscarded(old);
        assertThat(variant(second).getStatus()).isEqualTo(MediaVariantsStatus.PENDING);
    }

    @Test
    @DisplayName("el render de una comparación: reemplazarlo y borrar la comparación descartan sus versiones")
    void comparisonRenderReplacementAndDeletion() throws Exception {
        UUID comparison = idOf(ok(send(post("/api/developments/" + developmentId + "/comparisons"), """
                {"title":"Frente","unitId":"%s"}""".formatted(casa))));
        String first = upload("/api/comparisons/" + comparison + "/render", "render.png", "image/png");
        worker.processPending();
        ImageVariant old = variant(first);
        JsonNode listed = json(ok(send(get("/api/developments/" + developmentId + "/comparisons")))).get(0);
        assertThat(listed.get("renderThumbUrl").asString()).isEqualTo(signed(old.getThumbStorageKey()));
        assertThat(listed.get("renderScreenUrl").asString()).isEqualTo(signed(old.getScreenStorageKey()));

        String second = upload("/api/comparisons/" + comparison + "/render", "render-2.png", "image/png");
        assertDiscarded(old);
        worker.processPending();
        ImageVariant current = variant(second);

        send(delete("/api/comparisons/" + comparison)).andExpect(status().is2xxSuccessful());

        assertDiscarded(current);
    }

    @Test
    @DisplayName("la portada del desarrollo: versiones para el dueño y el comprador, y reemplazarla descarta las de la anterior")
    void developmentCoverVariantsAndReplacement() throws Exception {
        String first = upload("/api/developments/" + developmentId + "/cover", "barrio.jpg", "image/jpeg");
        worker.processPending();
        ImageVariant old = variant(first);
        JsonNode detail = json(ok(send(get("/api/developments/" + developmentId))));
        assertThat(detail.get("coverUrl").asString()).isEqualTo(signed(first));
        assertThat(detail.get("coverThumbUrl").asString()).isEqualTo(signed(old.getThumbStorageKey()));
        JsonNode listed = json(ok(send(get("/api/developments")))).get(0);
        assertThat(listed.get("coverScreenUrl").asString()).isEqualTo(signed(old.getScreenStorageKey()));
        assertThat(publicView().get("development").get("coverScreenUrl").asString()).isEqualTo(signed(old.getScreenStorageKey()));

        String second = upload("/api/developments/" + developmentId + "/cover", "barrio-2.webp", "image/webp");

        assertDiscarded(old);
        assertThat(variant(second).getStatus()).isEqualTo(MediaVariantsStatus.PENDING);
    }

    @Test
    @DisplayName("el worker toma primero las fotos y videos, y después las portadas y los renders")
    void workerTakesMediaFirst() throws Exception {
        String cover = upload("/api/units/" + casa + "/cover", "portada.jpg", "image/jpeg");
        UUID photo = idOf(ok(send(post("/api/developments/" + developmentId + "/media/upload-url"), """
                {"filename":"foto","contentType":"image/jpeg","unitId":"%s"}""".formatted(casa))), "mediaId");
        ok(send(post("/api/media/" + photo + "/confirm"), "{}"));
        String photoKey = mediaRepository.findById(photo).orElseThrow().getStorageKey();

        assertThat(worker.processPending()).isEqualTo(2);

        InOrder order = inOrder(storageService);
        order.verify(storageService).download(eq(photoKey), any(Path.class));
        order.verify(storageService).download(eq(cover), any(Path.class));
    }

    @Test
    @DisplayName("un SVG no se achica: queda salteado, el worker ni lo baja y se ve el original")
    void svgIsSkipped() throws Exception {
        String cover = upload("/api/units/" + casa + "/cover", "portada.svg", "image/svg+xml");

        assertThat(variant(cover).getStatus()).isEqualTo(MediaVariantsStatus.SKIPPED);
        assertThat(worker.processPending()).isZero();
        verify(storageService, never()).download(eq(cover), any(Path.class));
        assertThat(publicView().get("details").get("coverThumbUrl").isNull()).isTrue();
    }

    @Test
    @DisplayName("si la generación falla, se sigue viendo el original; al tercer intento queda FAILED y sale de la cola")
    void failureFallsBackToTheOriginal() throws Exception {
        String cover = upload("/api/units/" + casa + "/cover", "portada.jpg", "image/jpeg");
        doThrow(new RuntimeException("almacenamiento caído")).when(storageService).download(eq(cover), any(Path.class));

        worker.processPending();
        assertThat(variant(cover).getStatus()).isEqualTo(MediaVariantsStatus.PENDING);
        assertThat(variant(cover).getAttempts()).isEqualTo(1);
        worker.processPending();
        worker.processPending();

        assertThat(variant(cover).getStatus()).isEqualTo(MediaVariantsStatus.FAILED);
        assertThat(worker.processPending()).isZero();
        JsonNode details = publicView().get("details");
        assertThat(details.get("coverUrl").asString()).isEqualTo(signed(cover));
        assertThat(details.get("coverThumbUrl").isNull()).isTrue();
    }

    @Test
    @DisplayName("descartar o buscar con otra organización no toca ni devuelve nada")
    void scopedByOrganization() throws Exception {
        String cover = upload("/api/units/" + casa + "/cover", "portada.jpg", "image/jpeg");
        worker.processPending();
        UUID other = organizationRepository.save(new Organization("Otra")).getId();

        assertThat(imageVariants.lookup(List.of(other), List.of(cover)).sign(cover).thumbUrl()).isNull();
        imageVariants.discard(other, cover);

        assertThat(variant(cover).getStatus()).isEqualTo(MediaVariantsStatus.READY);
        assertThat(imageVariants.lookup(List.of(organizationId), List.of(cover)).sign(cover).thumbUrl()).isNotNull();
    }

    // --- Armado ---

    /** Las versiones de la imagen que se fue no están y se borraron del almacenamiento; el original queda. */
    private void assertDiscarded(ImageVariant old) {
        assertThat(imageRepository.findById(old.getStorageKey())).isEmpty();
        verify(storageService).delete(old.getThumbStorageKey());
        verify(storageService).delete(old.getScreenStorageKey());
        verify(storageService, never()).delete(old.getStorageKey());
    }

    /** Pide la subida, "sube" y confirma; devuelve la clave de la imagen que quedó vigente. */
    private String upload(String base, String filename, String contentType) throws Exception {
        List<String> before = imageRepository.findAll().stream().map(ImageVariant::getStorageKey).toList();
        ok(send(post(base + "/upload-url"), """
                {"filename":"%s","contentType":"%s"}""".formatted(filename, contentType)));
        ok(send(post(base + "/confirm")));
        return imageRepository.findAll().stream().map(ImageVariant::getStorageKey)
                .filter(key -> !before.contains(key)).findFirst().orElseThrow();
    }

    private ImageVariant variant(String storageKey) {
        return imageRepository.findById(storageKey).orElseThrow();
    }

    private JsonNode publicView() throws Exception {
        String token = json(ok(send(post("/api/units/" + casa + "/share-links"), """
                {"label":"Familia"}"""))).get("token").asString();
        return json(mockMvc.perform(get("/api/public/share/" + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String signed(String key) {
        return "https://almacenamiento.test/firmada/" + Integer.toHexString(key.hashCode());
    }

    private String createUser(Organization organization) {
        String email = "duenio-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Dueño"));
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

    private ResultActions send(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + ownerToken));
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder
                .header("Authorization", "Bearer " + ownerToken)
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
