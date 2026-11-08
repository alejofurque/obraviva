package com.obraviva.platform.developments;

import com.obraviva.platform.developments.DevelopmentMemberRepository;
import com.obraviva.platform.support.SupervisorAssignments;
import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.jobs.ProcessingJobRepository;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica lo que el encargado de obra no puede hacer.
 *
 * <p>En la organización, el dueño es el desarrollista y el miembro es el encargado.
 * Según el documento maestro, el encargado publica avances, audios y capturas, pero
 * no ve información económica ni de compradores, ni administra la estructura del
 * desarrollo. Cada restricción responde 403: el encargado sabe que el desarrollo
 * existe, así que un 404 le mentiría sin proteger nada.
 *
 * <p>Los controles positivos importan tanto como las negativas: sin ellos, una
 * restricción mal aplicada que bloqueara todo pasaría todos los tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class MemberRoleRestrictionsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";
    private static final ResultMatcher FORBIDDEN = status().isForbidden();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${local.server.port}") private int port;

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private DevelopmentMemberRepository assignmentRepository;
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private String memberToken;
    private String buyerToken;
    private String buyerEmail;
    private UUID organizationId;
    private UUID developmentId;
    private UUID stageId;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();

        when(storageService.presignUpload(anyString(), anyString()))
                .thenReturn("http://localhost:9000/fake-upload");
        when(storageService.presignDownload(anyString()))
                .thenReturn("http://localhost:9000/fake-download");
        when(storageService.exists(anyString())).thenReturn(true);

        Organization organization = organizationRepository.save(new Organization("Constructora Roles"));
        organizationId = organization.getId();
        ownerToken = login(createUser("duenio", organization, OrganizationRole.OWNER));
        memberToken = login(createUser("encargado", organization, OrganizationRole.MEMBER));
        buyerEmail = createUser("comprador", null, null);
        buyerToken = login(buyerEmail);

        developmentId = idOf(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Torre Roles"}""".formatted(organizationId)));
        SupervisorAssignments.assignAllSupervisors(memberRepository, assignmentRepository, organizationId, developmentId);
        stageId = idOf(send(ownerToken, post("/api/developments/" + developmentId + "/stages"), """
                {"name":"Estructura","orderIndex":1}"""));
        unitId = idOf(send(ownerToken, post("/api/developments/" + developmentId + "/units"), """
                {"code":"3B","description":"Dos dormitorios"}"""));

        mockMvc.perform(patch("/api/stages/" + stageId + "/plan")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weight":3,"budgetAmount":1500000.00}"""))
                .andExpect(status().isOk());
    }

    // --- Armado ---

    private String createUser(String prefix, Organization organization, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(
                new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        if (organization != null) {
            memberRepository.save(new OrganizationMember(organization, user.getId(), role));
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

    /** Petición autenticada con cuerpo JSON que se espera exitosa. Devuelve el cuerpo. */
    private String send(String token, MockHttpServletRequestBuilder builder, String body)
            throws Exception {
        return mockMvc.perform(builder
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private static UUID idOf(String json) {
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    private void expectForMember(MockHttpServletRequestBuilder builder, ResultMatcher expected)
            throws Exception {
        mockMvc.perform(builder.header("Authorization", "Bearer " + memberToken))
                .andExpect(expected);
    }

    private void expectForMember(
            MockHttpServletRequestBuilder builder, String body, ResultMatcher expected)
            throws Exception {
        mockMvc.perform(builder
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(expected);
    }

    // --- Estructura del desarrollo ---

    @Test
    @DisplayName("el encargado no crea desarrollos, unidades ni etapas")
    void memberCannotAdministerStructure() throws Exception {
        expectForMember(post("/api/developments"), """
                {"organizationId":"%s","name":"Torre Nueva"}""".formatted(organizationId), FORBIDDEN);
        expectForMember(post("/api/developments/" + developmentId + "/units"), """
                {"code":"4A","description":"Monoambiente"}""", FORBIDDEN);
        expectForMember(post("/api/developments/" + developmentId + "/stages"), """
                {"name":"Terminaciones","orderIndex":2}""", FORBIDDEN);
    }

    // --- Información económica ---

    @Test
    @DisplayName("el encargado no accede al panel ni a los gastos")
    void memberCannotSeeDashboardOrExpenses() throws Exception {
        UUID expenseId = idOf(send(ownerToken,
                post("/api/developments/" + developmentId + "/expenses"), """
                        {"incurredOn":"2026-09-01","amount":25000.00,"description":"Hierro"}"""));

        expectForMember(get("/api/developments/" + developmentId + "/dashboard"), FORBIDDEN);
        expectForMember(get("/api/developments/" + developmentId + "/expenses"), FORBIDDEN);
        expectForMember(post("/api/developments/" + developmentId + "/expenses"), """
                {"incurredOn":"2026-09-02","amount":100.00}""", FORBIDDEN);
        expectForMember(delete("/api/expenses/" + expenseId), FORBIDDEN);

        // Control positivo: el dueño sí.
        mockMvc.perform(get("/api/developments/" + developmentId + "/dashboard")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("el encargado ve las etapas sin presupuesto, y el dueño con presupuesto")
    void stageBudgetIsHiddenFromMember() throws Exception {
        expectForMember(get("/api/developments/" + developmentId + "/stages"), status().isOk());
        mockMvc.perform(get("/api/developments/" + developmentId + "/stages")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(jsonPath("$[0].name").value("Estructura"))
                .andExpect(jsonPath("$[0].budgetAmount").value(nullValue()));

        mockMvc.perform(get("/api/developments/" + developmentId + "/stages")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].budgetAmount").value(1500000.00));
    }

    @Test
    @DisplayName("el encargado declara avance y cambia estados, pero no toca peso ni presupuesto")
    void memberDeclaresProgressButNotPlan() throws Exception {
        expectForMember(patch("/api/stages/" + stageId + "/plan"), """
                {"budgetAmount":1.00}""", FORBIDDEN);
        expectForMember(patch("/api/stages/" + stageId + "/plan"), """
                {"weight":9}""", FORBIDDEN);
        expectForMember(patch("/api/stages/" + stageId + "/plan"), """
                {"clearBudget":true}""", FORBIDDEN);

        mockMvc.perform(patch("/api/stages/" + stageId + "/plan")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"progressPercent":40}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercent").value(40))
                .andExpect(jsonPath("$.weight").value(3))
                .andExpect(jsonPath("$.budgetAmount").value(nullValue()));

        mockMvc.perform(patch("/api/developments/stages/" + stageId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.budgetAmount").value(nullValue()));
    }

    // --- Reportes ---

    @Test
    @DisplayName("el encargado no genera, lista, consulta ni resuelve reportes")
    void memberCannotAccessReports() throws Exception {
        send(ownerToken, post("/api/developments/" + developmentId + "/progress"), """
                {"entryDate":"2026-08-12","description":"Se hormigonó la losa."}""");
        UUID reportId = idOf(send(ownerToken,
                post("/api/developments/" + developmentId + "/reports"), """
                        {"periodStart":"2026-08-01","periodEnd":"2026-08-31"}"""));

        expectForMember(post("/api/developments/" + developmentId + "/reports"), """
                {"periodStart":"2026-08-01","periodEnd":"2026-08-31"}""", FORBIDDEN);
        expectForMember(get("/api/developments/" + developmentId + "/reports"), FORBIDDEN);
        expectForMember(get("/api/reports/" + reportId), FORBIDDEN);
        expectForMember(post("/api/reports/" + reportId + "/approve"), """
                {"narrative":"Texto"}""", FORBIDDEN);
        expectForMember(post("/api/reports/" + reportId + "/reject"), "{}", FORBIDDEN);

        // La bandeja general no es de un desarrollo: para el encargado, simplemente vacía.
        mockMvc.perform(get("/api/reports/pending-review")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // --- Compradores ---

    @Test
    @DisplayName("el encargado no ve ni administra a los compradores de una unidad")
    void memberCannotSeeOrManageBuyers() throws Exception {
        UUID memberId = idOf(send(ownerToken, post("/api/units/" + unitId + "/members"), """
                {"email":"%s"}""".formatted(buyerEmail)));

        expectForMember(get("/api/units/" + unitId + "/members"), FORBIDDEN);
        expectForMember(post("/api/units/" + unitId + "/members"), """
                {"email":"%s"}""".formatted(buyerEmail), FORBIDDEN);
        expectForMember(delete("/api/unit-members/" + memberId), FORBIDDEN);
    }

    @Test
    @DisplayName("los enlaces que genera un comprador no se muestran al encargado")
    void memberDoesNotSeeBuyerShareLinks() throws Exception {
        send(ownerToken, post("/api/units/" + unitId + "/members"), """
                {"email":"%s"}""".formatted(buyerEmail));
        String link = send(buyerToken, post("/api/units/" + unitId + "/share-links"), """
                {"label":"Para la familia"}""");
        UUID linkId = UUID.fromString(JSON.readTree(link).get("id").asString());

        mockMvc.perform(get("/api/units/" + unitId + "/share-links")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        expectForMember(post("/api/share-links/" + linkId + "/revoke"), status().isNotFound());
        expectForMember(post("/api/units/" + unitId + "/share-links"), """
                {"label":"Publicado por el encargado"}""", FORBIDDEN);

        // Control positivo: el dueño los sigue viendo, con quién los creó.
        mockMvc.perform(get("/api/units/" + unitId + "/share-links")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].createdByName").isString())
                .andExpect(jsonPath("$[0].createdByYou").value(false));
    }

    // --- Capturas ---

    @Test
    @DisplayName("el encargado sube capturas pero no las alinea")
    void memberUploadsCapturesButCannotAlign() throws Exception {
        UUID captureId = UUID.fromString(JSON.readTree(send(memberToken,
                post("/api/developments/" + developmentId + "/captures"), """
                        {"captureDate":"2026-09-01","description":"Recorrido"}"""))
                .get("captureId").asString());

        expectForMember(patch("/api/captures/" + captureId + "/transform"), """
                {"transform":{"position":{"x":0,"y":0,"z":0},"rotationDeg":{"x":0,"y":20,"z":0},"scale":1}}""",
                FORBIDDEN);
    }

    // --- Lo que el encargado sí hace ---

    @Test
    @DisplayName("el encargado ve la obra y publica avances")
    void memberSeesDevelopmentAndPublishesProgress() throws Exception {
        expectForMember(get("/api/developments/" + developmentId), status().isOk());
        expectForMember(get("/api/developments/" + developmentId + "/units"), status().isOk());
        expectForMember(get("/api/developments/" + developmentId + "/progress"), status().isOk());
        expectForMember(post("/api/developments/" + developmentId + "/progress"), """
                {"entryDate":"2026-09-03","description":"Se terminó el encofrado."}""",
                status().isCreated());
    }

    // --- Servidor real ---

    @Test
    @DisplayName("un cliente HTTP real recibe el 403 con su mensaje, no un 401")
    void realClientReceivesForbiddenWithMessage() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/api/developments/" + developmentId + "/dashboard"))
                        .header("Authorization", "Bearer " + memberToken)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(response.body()).get("message").asString())
                .contains("responsable del desarrollo");
    }
}
