package com.obraviva.platform.plans;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.DevelopmentMemberRepository;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.storage.StorageService;
import com.obraviva.platform.support.SupervisorAssignments;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Imagen del plano: el archivo que entrega el estudio (PDF o imagen) o una foto del plano
 * impreso. Los archivos de prueba se generan acá mismo: un PDF A4 apaisado con PDFBox y
 * un PNG con ImageIO, así lo que se verifica es la conversión real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class FloorPlanImageIntegrationTest {

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
    @Autowired private DevelopmentMemberRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private String memberToken;
    private String planId;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/plano");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/plano");

        Organization organization = organizationRepository.save(new Organization("Constructora Planos"));
        ownerToken = login(user("duenio", organization, OrganizationRole.OWNER));
        memberToken = login(user("encargado", organization, OrganizationRole.MEMBER));

        UUID developmentId = UUID.fromString(idOf(ok(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Los Aromos"}""".formatted(organization.getId()))));
        SupervisorAssignments.assignAllSupervisors(memberRepository, assignmentRepository, organization.getId(), developmentId);
        String unitId = idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/units"), """
                {"code":"Casa 12"}"""));
        planId = idOf(ok(ownerToken, post("/api/units/" + unitId + "/plans"), """
                {"name":"Planta baja","widthM":12,"depthM":8.5}"""));
    }

    @Test
    @DisplayName("Un PDF se convierte a PNG en su primera página, a no más de 3000 px por lado")
    void pdfIsRenderedToPng() throws Exception {
        storedFileIs(file -> writePdf(file, 2));
        requestUpload("application/pdf", "planta-baja.pdf").andExpect(status().isOk());

        JsonNode plan = JSON.readTree(ok(ownerToken, post("/api/plans/" + planId + "/image/confirm"), ""));

        int width = plan.get("imageWidthPx").asInt();
        int height = plan.get("imageHeightPx").asInt();
        // PDFBox redondea hacia abajo: lo que importa es el tope, no el píxel exacto.
        assertThat(width).isBetween(2990, 3000);
        assertThat(height).isBetween(2100, 2130);
        assertThat(plan.get("imageUrl").asString()).isEqualTo("https://almacenamiento.test/plano");
        verify(storageService).upload(any(Path.class), argThat(key -> key.endsWith(".png")), eq("image/png"));
        verify(storageService).delete(argThat(key -> key.endsWith(".pdf")));
    }

    @Test
    @DisplayName("Una imagen se publica tal cual, con sus dimensiones leídas del archivo")
    void imageIsPublishedAsIs() throws Exception {
        storedFileIs(file -> ImageIO.write(new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB), "png", file.toFile()));
        requestUpload("image/png", "foto-del-plano.png").andExpect(status().isOk());

        JsonNode plan = JSON.readTree(ok(ownerToken, post("/api/plans/" + planId + "/image/confirm"), ""));

        assertThat(plan.get("imageWidthPx").asInt()).isEqualTo(1600);
        assertThat(plan.get("imageHeightPx").asInt()).isEqualTo(900);
        verify(storageService, never()).upload(any(Path.class), anyString(), anyString());
    }

    @Test
    @DisplayName("Un archivo que no es lo que dice se rechaza con un motivo y no queda pendiente")
    void brokenFileIsRejected() throws Exception {
        storedFileIs(file -> Files.writeString(file, "esto no es un PDF"));
        requestUpload("application/pdf", "roto.pdf").andExpect(status().isOk());

        String message = send(ownerToken, post("/api/plans/" + planId + "/image/confirm"), "")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getErrorMessage();
        assertThat(message).contains("No se pudo leer el PDF");

        send(ownerToken, post("/api/plans/" + planId + "/image/confirm"), "").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("DWG y otros formatos se rechazan explicando cómo exportar")
    void unsupportedFormatsExplainHowToExport() throws Exception {
        String message = requestUpload("image/vnd.dwg", "planta.dwg")
                .andExpect(status().isUnsupportedMediaType())
                .andReturn().getResponse().getErrorMessage();
        assertThat(message).contains("exportalo a PDF");
    }

    @Test
    @DisplayName("Sólo el dueño sube, confirma o quita la imagen; el encargado recibe 403")
    void onlyOwnerManagesImage() throws Exception {
        send(memberToken, post("/api/plans/" + planId + "/image/upload-url"), """
                {"filename":"p.png","contentType":"image/png"}""").andExpect(status().isForbidden());
        send(memberToken, post("/api/plans/" + planId + "/image/confirm"), "").andExpect(status().isForbidden());
        send(memberToken, delete("/api/plans/" + planId + "/image"), "").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Quitar la imagen deja la planta con sus ambientes y sin URL")
    void removingImageKeepsPlan() throws Exception {
        storedFileIs(file -> ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", file.toFile()));
        requestUpload("image/png", "p.png");
        ok(ownerToken, post("/api/plans/" + planId + "/image/confirm"), "");

        JsonNode plan = JSON.readTree(ok(ownerToken, delete("/api/plans/" + planId + "/image"), ""));
        assertThat(plan.get("imageUrl").isNull()).isTrue();
        assertThat(plan.get("imageWidthPx").isNull()).isTrue();
        assertThat(plan.get("name").asString()).isEqualTo("Planta baja");
    }

    // --- Armado ---

    private interface FileWriter {
        void write(Path file) throws Exception;
    }

    /** Lo que el mock del almacenamiento "descarga" es el archivo que escribe este bloque. */
    private void storedFileIs(FileWriter writer) {
        doAnswer(invocation -> {
            writer.write(invocation.getArgument(1));
            return null;
        }).when(storageService).download(anyString(), any(Path.class));
    }

    private static void writePdf(Path file, int pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDRectangle landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
                document.addPage(new PDPage(landscape));
            }
            document.save(file.toFile());
        }
    }

    private ResultActions requestUpload(String contentType, String filename) throws Exception {
        return send(ownerToken, post("/api/plans/" + planId + "/image/upload-url"), """
                {"filename":"%s","contentType":"%s"}""".formatted(filename, contentType));
    }

    private String user(String prefix, Organization org, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User saved = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        memberRepository.save(new OrganizationMember(org, saved.getId(), role));
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

    private ResultActions send(String token, MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String ok(String token, MockHttpServletRequestBuilder builder, String body) throws Exception {
        return send(token, builder, body).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private static String idOf(String json) {
        return JSON.readTree(json).get("id").asString();
    }
}
