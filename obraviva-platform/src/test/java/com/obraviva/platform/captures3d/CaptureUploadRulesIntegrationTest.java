package com.obraviva.platform.captures3d;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.storage.StorageService;
import com.obraviva.platform.support.SpzFixtures;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Subir un recorrido desde la obra: un SPZ de Scaniverse o un video, sector por sector.
 *
 * <p>Lo que se prueba es lo que el encargado no puede romper: un archivo que no es SPZ o
 * que pasa los 50 MB no deja captura, un sector no tiene dos recorridos el mismo día, la
 * alineación se hereda del recorrido anterior del sector, y sólo se opera sobre las casas
 * asignadas, también al confirmar o descartar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class CaptureUploadRulesIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";
    private static final String DAY = "2026-10-01";

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private Capture3dRepository captureRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private String supervisorToken;
    private String outsiderToken;
    private UUID developmentId;
    private UUID casa1;
    private UUID casa2;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.size(anyString())).thenReturn(SpzFixtures.TYPICAL_SIZE);
        when(storageService.readPrefix(anyString(), anyInt())).thenReturn(SpzFixtures.valid());

        Organization organization = organizationRepository.save(new Organization("Casas del Sur"));
        ownerToken = login(createUser("duenio", organization, OrganizationRole.OWNER));
        String supervisorEmail = createUser("carlos", organization, OrganizationRole.MEMBER);
        UUID supervisorId = userRepository.findByEmail(supervisorEmail).orElseThrow().getId();
        supervisorToken = login(supervisorEmail);
        outsiderToken = login(createUser("ajeno", organizationRepository.save(new Organization("Otra")), OrganizationRole.OWNER));

        developmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio del Sur"}""".formatted(organization.getId()))));
        JsonNode houses = json(ok(send(ownerToken, post("/api/developments/" + developmentId + "/units/batch"), """
                {"prefix":"Casa","firstNumber":1,"count":2,"withStandardStages":true}""")));
        casa1 = UUID.fromString(houses.get(0).get("id").asString());
        casa2 = UUID.fromString(houses.get(1).get("id").asString());
        ok(send(ownerToken, post("/api/developments/" + developmentId + "/team"), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(supervisorId, casa1)));
    }

    // --- El archivo ---

    @Test
    @DisplayName("un SPZ válido del encargado queda disponible al confirmar")
    void validSpzBecomesAvailable() throws Exception {
        UUID captureId = createSpz(supervisorToken, casa1, "Frente", DAY);

        JsonNode capture = json(ok(send(supervisorToken, post("/api/captures/" + captureId + "/confirm"), "{}")));

        assertThat(capture.get("status").asString()).isEqualTo("AVAILABLE");
        assertThat(capture.get("zone").asString()).isEqualTo("Frente");
        assertThat(captureRepository.findById(captureId).orElseThrow().getStatus()).isEqualTo(CaptureStatus.AVAILABLE);
    }

    @Test
    @DisplayName("un archivo que no es SPZ se rechaza al confirmar con un mensaje claro, y no deja captura ni archivo")
    void invalidSpzIsRemoved() throws Exception {
        UUID captureId = createSpz(supervisorToken, casa1, "Frente", DAY);
        String key = captureRepository.findById(captureId).orElseThrow().getStorageKey();
        when(storageService.readPrefix(eq(key), anyInt()))
                .thenReturn("ply\nformat binary_little_endian 1.0\n".getBytes(StandardCharsets.US_ASCII));

        String message = send(supervisorToken, post("/api/captures/" + captureId + "/confirm"), "{}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getErrorMessage();

        assertThat(message).contains("no es un SPZ válido");
        assertThat(captureRepository.findById(captureId)).isEmpty();
        verify(storageService).delete(key);
    }

    @Test
    @DisplayName("más de 50 MB: se rechaza en el alta sin crear nada, y al confirmar si el archivo real pesa más")
    void tooLargeIsRejected() throws Exception {
        long before = captureRepository.count();
        String message = send(supervisorToken, post(capturesUrl()), """
                {"captureDate":"%s","unitId":"%s","zone":"Frente","filename":"frente.spz","fileSize":%d}"""
                .formatted(DAY, casa1, 60L * 1024 * 1024))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getErrorMessage();
        assertThat(message).contains("máximo es 50 MB");
        send(supervisorToken, post(capturesUrl()), """
                {"captureDate":"%s","unitId":"%s","zone":"Frente","filename":"frente.ply","fileSize":1000}"""
                .formatted(DAY, casa1)).andExpect(status().isBadRequest());
        assertThat(captureRepository.count()).isEqualTo(before);

        // El cliente declaró poco y subió de más: lo que manda es lo que llegó al almacenamiento.
        UUID captureId = createSpz(supervisorToken, casa1, "Frente", DAY);
        String key = captureRepository.findById(captureId).orElseThrow().getStorageKey();
        when(storageService.size(key)).thenReturn(60L * 1024 * 1024);
        send(supervisorToken, post("/api/captures/" + captureId + "/confirm"), "{}").andExpect(status().isBadRequest());
        assertThat(captureRepository.findById(captureId)).isEmpty();
    }

    // --- Un recorrido por sector y día ---

    @Test
    @DisplayName("409 si el sector ya tiene un recorrido disponible ese día; otro sector u otro día, sí")
    void sameSectorSameDayConflicts() throws Exception {
        confirmSpz(supervisorToken, casa1, "Frente", DAY);

        String message = send(supervisorToken, post(capturesUrl()), spzBody(casa1, "Frente", DAY))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getErrorMessage();
        assertThat(message).isEqualTo("Frente ya tiene un recorrido del 01/10/2026. Elegí otra fecha.");
        send(supervisorToken, post(videoUrl()), videoBody(casa1, "Frente", DAY)).andExpect(status().isConflict());

        createSpz(supervisorToken, casa1, "Living y cocina", DAY);
        createSpz(supervisorToken, casa1, "Frente", "2026-10-02");
        createSpz(ownerToken, casa2, "Frente", DAY);
    }

    @Test
    @DisplayName("un video reconstruyéndose bloquea el sector ese día; uno sin confirmar o un SPZ sin confirmar, no")
    void inProgressVideoBlocksButAbandonedUploadsDoNot() throws Exception {
        UUID abandonedSpz = createSpz(supervisorToken, casa1, "Frente", DAY);
        UUID unconfirmedVideo = UUID.fromString(json(ok(send(supervisorToken, post(videoUrl()), videoBody(casa1, "Frente", DAY))))
                .get("captureId").asString());

        UUID reconstructing = UUID.fromString(json(ok(send(supervisorToken, post(videoUrl()),
                videoBody(casa1, "Living y cocina", DAY)))).get("captureId").asString());
        ok(send(supervisorToken, post("/api/captures/" + reconstructing + "/confirm-video"), "{}"));
        send(supervisorToken, post(capturesUrl()), spzBody(casa1, "Living y cocina", DAY))
                .andExpect(status().isConflict());

        // El SPZ que llega primero gana; la confirmación del video que quedó atrás se rechaza y lo descarta.
        ok(send(supervisorToken, post("/api/captures/" + abandonedSpz + "/confirm"), "{}"));
        send(supervisorToken, post("/api/captures/" + unconfirmedVideo + "/confirm-video"), "{}")
                .andExpect(status().isConflict());
        assertThat(captureRepository.findById(unconfirmedVideo)).isEmpty();
    }

    // --- Alineación ---

    @Test
    @DisplayName("una captura nueva hereda la alineación de la última disponible de su sector; sin anteriores, la identidad")
    void newCaptureInheritsSectorAlignment() throws Exception {
        UUID first = confirmSpz(ownerToken, casa1, "Frente", "2026-09-01");
        UUID second = confirmSpz(ownerToken, casa1, "Frente", "2026-09-15");
        ok(send(ownerToken, patch("/api/captures/" + first + "/transform"), transformBody(9, 9, 9, 0, 45, 0, 2)));
        ok(send(ownerToken, patch("/api/captures/" + second + "/transform"), transformBody(1.5, 0, -2, 0, 90, 0, 1.25)));

        UUID next = createSpz(supervisorToken, casa1, "Frente", DAY);
        UUID fromVideo = UUID.fromString(json(ok(send(supervisorToken, post(videoUrl()), videoBody(casa1, "Frente", "2026-10-02"))))
                .get("captureId").asString());
        UUID otherSector = createSpz(supervisorToken, casa1, "Baño", DAY);

        for (UUID inherited : new UUID[] {next, fromVideo}) {
            SpatialTransform transform = captureRepository.findById(inherited).orElseThrow().getTransform();
            assertThat(transform.getPositionX()).isEqualTo(1.5);
            assertThat(transform.getPositionZ()).isEqualTo(-2);
            assertThat(transform.getRotationY()).isEqualTo(90);
            assertThat(transform.getScale()).isEqualTo(1.25);
        }
        SpatialTransform identity = captureRepository.findById(otherSector).orElseThrow().getTransform();
        assertThat(identity.getPositionX()).isZero();
        assertThat(identity.getRotationY()).isZero();
        assertThat(identity.getScale()).isEqualTo(1);
    }

    // --- Datos del alta ---

    @Test
    @DisplayName("fecha futura o un sector que la casa no tiene: 400")
    void futureDateAndUnknownSector() throws Exception {
        send(supervisorToken, post(capturesUrl()), spzBody(casa1, "Frente", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isBadRequest());
        send(supervisorToken, post(videoUrl()), videoBody(casa1, "Frente", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isBadRequest());
        String message = send(supervisorToken, post(capturesUrl()), spzBody(casa1, "Quincho", DAY))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getErrorMessage();
        assertThat(message).contains("\"Quincho\" no es un sector de esta casa");
    }

    // --- Permisos ---

    @Test
    @DisplayName("casa no asignada: 403 como hoy; otra organización: 404")
    void foreignHouseAndOrganization() throws Exception {
        send(supervisorToken, post(capturesUrl()), spzBody(casa2, "Frente", DAY)).andExpect(status().isForbidden());
        send(supervisorToken, post(videoUrl()), videoBody(casa2, "Frente", DAY)).andExpect(status().isForbidden());
        send(outsiderToken, post(capturesUrl()), spzBody(casa1, "Frente", DAY)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el encargado de la casa 1 no confirma, ni confirma el video, ni descarta una captura de la casa 2")
    void supervisorCannotTouchCapturesOfAnotherHouse() throws Exception {
        UUID spz = createSpz(ownerToken, casa2, "Frente", DAY);
        UUID video = UUID.fromString(json(ok(send(ownerToken, post(videoUrl()), videoBody(casa2, "Baño", DAY))))
                .get("captureId").asString());

        send(supervisorToken, post("/api/captures/" + spz + "/confirm"), "{}").andExpect(status().isForbidden());
        send(supervisorToken, post("/api/captures/" + video + "/confirm-video"), "{}").andExpect(status().isForbidden());
        send(supervisorToken, delete("/api/captures/" + spz)).andExpect(status().isForbidden());
        send(outsiderToken, post("/api/captures/" + spz + "/confirm"), "{}").andExpect(status().isNotFound());

        assertThat(captureRepository.findById(spz).orElseThrow().getStatus()).isEqualTo(CaptureStatus.PENDING);
        assertThat(captureRepository.findById(video)).isPresent();
    }

    // --- Armado ---

    private String capturesUrl() {
        return "/api/developments/" + developmentId + "/captures";
    }

    private String videoUrl() {
        return "/api/developments/" + developmentId + "/captures/from-video";
    }

    private static String spzBody(UUID unitId, String zone, String date) {
        return """
                {"captureDate":"%s","unitId":"%s","zone":"%s","filename":"sector.spz","fileSize":%d}"""
                .formatted(date, unitId, zone, SpzFixtures.TYPICAL_SIZE);
    }

    private static String videoBody(UUID unitId, String zone, String date) {
        return """
                {"captureDate":"%s","unitId":"%s","zone":"%s","videoFilename":"sector.mp4","videoContentType":"video/mp4"}"""
                .formatted(date, unitId, zone);
    }

    private static String transformBody(double x, double y, double z, double rx, double ry, double rz, double scale) {
        return """
                {"transform":{"position":{"x":%s,"y":%s,"z":%s},"rotationDeg":{"x":%s,"y":%s,"z":%s},"scale":%s}}"""
                .formatted(x, y, z, rx, ry, rz, scale);
    }

    private UUID createSpz(String token, UUID unitId, String zone, String date) throws Exception {
        return UUID.fromString(json(ok(send(token, post(capturesUrl()), spzBody(unitId, zone, date))))
                .get("captureId").asString());
    }

    private UUID confirmSpz(String token, UUID unitId, String zone, String date) throws Exception {
        UUID captureId = createSpz(token, unitId, zone, date);
        ok(send(token, post("/api/captures/" + captureId + "/confirm"), "{}"));
        return captureId;
    }

    private String createUser(String prefix, Organization organization, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        memberRepository.save(new OrganizationMember(organization, user.getId(), role));
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

    private static String ok(ResultActions actions) throws Exception {
        return actions.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static UUID idOf(String body) {
        return UUID.fromString(JSON.readTree(body).get("id").asString());
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }
}
