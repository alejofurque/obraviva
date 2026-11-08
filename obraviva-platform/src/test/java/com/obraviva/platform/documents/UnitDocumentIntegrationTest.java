package com.obraviva.platform.documents;

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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documentos autorizados: privados de la unidad. Los ve su comprador (los de su unidad y
 * los de la obra), nunca el comprador de otra unidad, ni el encargado, ni el enlace público.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class UnitDocumentIntegrationTest {

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
    private String buyerToken;
    private String developmentId;
    private String unitA;
    private String unitB;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/larga");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/publica");
        when(storageService.presignDownload(anyString(), eq(UnitDocumentService.DOWNLOAD_TTL))).thenReturn("https://almacenamiento.test/documento");
        when(storageService.exists(anyString())).thenReturn(true);

        Organization organization = organizationRepository.save(new Organization("Constructora Papeles"));
        ownerToken = login(user("duenio", organization, OrganizationRole.OWNER));
        memberToken = login(user("encargado", organization, OrganizationRole.MEMBER));
        String buyerEmail = user("comprador", null, null);
        buyerToken = login(buyerEmail);

        developmentId = idOf(ok(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Edificio Ceibo","kind":"MULTI_UNIT"}""".formatted(organization.getId())));
        SupervisorAssignments.assignAllSupervisors(memberRepository, assignmentRepository, organization.getId(), UUID.fromString(developmentId));
        unitA = idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/units"), """
                {"code":"1A"}"""));
        unitB = idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/units"), """
                {"code":"1B"}"""));
        ok(ownerToken, post("/api/units/" + unitA + "/members"), """
                {"email":"%s"}""".formatted(buyerEmail));
    }

    @Test
    @DisplayName("El comprador ve los documentos de su unidad y los de la obra; no los de otra unidad ni los pendientes")
    void buyerSeesOwnAndCommonDocuments() throws Exception {
        publish(null, "Reglamento de copropiedad", "REGLAMENTO");
        publish(unitA, "Boleto 1A", "CONTRATO");
        publish(unitB, "Boleto 1B", "CONTRATO");
        upload(unitA, "Plano sin confirmar", "PLANO");

        JsonNode documents = JSON.readTree(ok(buyerToken, get("/api/my-units/" + unitA + "/documents"), ""));

        assertThat(titles(documents)).containsExactlyInAnyOrder("Reglamento de copropiedad", "Boleto 1A");
        assertThat(documents.get(0).get("downloadUrl").asString()).isEqualTo("https://almacenamiento.test/documento");
        send(buyerToken, get("/api/my-units/" + unitB + "/documents"), "").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("El enlace público no trae documentos, aunque la unidad los tenga")
    void publicLinkNeverIncludesDocuments() throws Exception {
        publish(unitA, "Boleto 1A", "CONTRATO");
        String token = JSON.readTree(ok(buyerToken, post("/api/units/" + unitA + "/share-links"), "{}")).get("token").asString();

        String publicView = mockMvc.perform(get("/api/public/share/" + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(publicView).doesNotContain("Boleto 1A").doesNotContain("documento");
        assertThat(JSON.readTree(publicView).has("documents")).isFalse();
    }

    @Test
    @DisplayName("El encargado no ve ni sube documentos (403); el comprador no llega a la lista del dueño (404)")
    void rolesAreRespected() throws Exception {
        publish(unitA, "Boleto 1A", "CONTRATO");

        send(memberToken, get("/api/my-units/" + unitA + "/documents"), "").andExpect(status().isForbidden());
        send(memberToken, get("/api/developments/" + developmentId + "/documents"), "").andExpect(status().isForbidden());
        send(memberToken, post("/api/developments/" + developmentId + "/documents/upload-url"), body(unitA, "Otro", "OTRO"))
                .andExpect(status().isForbidden());
        send(buyerToken, get("/api/developments/" + developmentId + "/documents"), "").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("El dueño ve todo, incluso lo pendiente; los formatos y la unidad se validan")
    void ownerListAndValidation() throws Exception {
        publish(unitA, "Boleto 1A", "CONTRATO");
        upload(null, "Memoria sin confirmar", "MEMORIA");

        JsonNode documents = JSON.readTree(ok(ownerToken, get("/api/developments/" + developmentId + "/documents"), ""));
        assertThat(titles(documents)).containsExactlyInAnyOrder("Boleto 1A", "Memoria sin confirmar");

        send(ownerToken, post("/api/developments/" + developmentId + "/documents/upload-url"), """
                {"title":"Planilla","category":"OTRO","filename":"x.xlsx","contentType":"application/vnd.ms-excel"}""")
                .andExpect(status().isUnsupportedMediaType());
        send(ownerToken, post("/api/developments/" + developmentId + "/documents/upload-url"), """
                {"title":"Boleto","category":"CUOTAS","filename":"b.pdf","contentType":"application/pdf"}""")
                .andExpect(status().isBadRequest());
    }

    // --- Armado ---

    private void publish(String unitId, String title, String category) throws Exception {
        ok(ownerToken, post("/api/documents/" + upload(unitId, title, category) + "/confirm"), "");
    }

    private String upload(String unitId, String title, String category) throws Exception {
        return JSON.readTree(ok(ownerToken, post("/api/developments/" + developmentId + "/documents/upload-url"),
                body(unitId, title, category))).get("documentId").asString();
    }

    private static String body(String unitId, String title, String category) {
        return """
                {"title":"%s","category":"%s","unitId":%s,"filename":"documento.pdf","contentType":"application/pdf"}"""
                .formatted(title, category, unitId == null ? "null" : "\"" + unitId + "\"");
    }

    private static List<String> titles(JsonNode documents) {
        List<String> titles = new ArrayList<>();
        documents.forEach(document -> titles.add(document.get("title").asString()));
        return titles;
    }

    private String user(String prefix, Organization organization, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User saved = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        if (organization != null) {
            memberRepository.save(new OrganizationMember(organization, saved.getId(), role));
        }
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
