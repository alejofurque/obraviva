package com.obraviva.platform.developments;

import com.obraviva.platform.developments.DevelopmentMemberRepository;
import com.obraviva.platform.support.SupervisorAssignments;
import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * Verifica que cada desarrollo informe el rol del usuario en su organización.
 *
 * <p>El frontend decide con ese dato qué ofrece: el dueño ve el panel económico y el
 * miembro, que es el encargado de obra, no. El dato es orientativo para la interfaz;
 * la restricción real vive en el servidor.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
class DevelopmentRoleIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private DevelopmentMemberRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private String memberToken;
    private String outsiderToken;
    private UUID organizationId;
    private UUID developmentId;

    @BeforeEach
    void setUp() throws Exception {
        Organization organization = organizationRepository.save(new Organization("Constructora Roles"));
        organizationId = organization.getId();

        ownerToken = loginAs(createUser("duenio"), organization, OrganizationRole.OWNER);
        memberToken = loginAs(createUser("encargado"), organization, OrganizationRole.MEMBER);

        Organization other = organizationRepository.save(new Organization("Constructora Ajena"));
        outsiderToken = loginAs(createUser("ajeno"), other, OrganizationRole.OWNER);

        String response = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationId, "Torre Roles", "Bv. San Juan 800"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andReturn().getResponse().getContentAsString();
        developmentId = UUID.fromString(JSON.readTree(response).get("id").asString());
        SupervisorAssignments.assignAllSupervisors(memberRepository, assignmentRepository, organizationId, developmentId);
    }

    private String createUser(String prefix) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        return email;
    }

    private String loginAs(String email, Organization organization, OrganizationRole role)
            throws Exception {
        UUID userId = userRepository.findByEmail(email).orElseThrow().getId();
        memberRepository.save(new OrganizationMember(organization, userId, role));

        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD);
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("accessToken").asString();
    }

    @Test
    @DisplayName("el dueño ve su rol en el listado de desarrollos")
    void ownerSeesOwnerRoleInList() throws Exception {
        mockMvc.perform(get("/api/developments").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Torre Roles"))
                .andExpect(jsonPath("$[0].role").value("OWNER"));
    }

    @Test
    @DisplayName("el encargado ve el mismo desarrollo con su propio rol")
    void memberSeesMemberRoleInList() throws Exception {
        mockMvc.perform(get("/api/developments").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(developmentId.toString()))
                .andExpect(jsonPath("$[0].role").value("MEMBER"));
    }

    @Test
    @DisplayName("el detalle devuelve los datos del desarrollo y el rol de quien consulta")
    void detailIncludesDataAndRole() throws Exception {
        mockMvc.perform(get("/api/developments/" + developmentId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(developmentId.toString()))
                .andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
                .andExpect(jsonPath("$.name").value("Torre Roles"))
                .andExpect(jsonPath("$.address").value("Bv. San Juan 800"))
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    @Test
    @DisplayName("listado y detalle informan el avance ponderado y la etapa actual, iguales a los del panel")
    void listAndDetailIncludeProgress() throws Exception {
        mockMvc.perform(get("/api/developments/" + developmentId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.overallProgressPercent").value(0))
                .andExpect(jsonPath("$.currentStageName").doesNotExist());

        UUID first = stage("Fundaciones", 1);
        UUID second = stage("Estructura", 2);
        plan(first, 100, 3, "COMPLETED");
        plan(second, 40, 1, "IN_PROGRESS");

        // (100×3 + 40×1) / 4 = 85
        mockMvc.perform(get("/api/developments").header("Authorization", "Bearer " + memberToken))
                .andExpect(jsonPath("$[0].overallProgressPercent").value(85))
                .andExpect(jsonPath("$[0].currentStageName").value("Estructura"));
        mockMvc.perform(get("/api/developments/" + developmentId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.overallProgressPercent").value(85))
                .andExpect(jsonPath("$.currentStageName").value("Estructura"));
    }

    private UUID stage(String name, int order) throws Exception {
        String response = mockMvc.perform(post("/api/developments/" + developmentId + "/stages")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","orderIndex":%d}""".formatted(name, order)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(response).get("id").asString());
    }

    private void plan(UUID stageId, int progress, int weight, String status) throws Exception {
        mockMvc.perform(patch("/api/stages/" + stageId + "/plan")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"progressPercent":%d,"weight":%d}""".formatted(progress, weight)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/developments/stages/" + stageId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"%s"}""".formatted(status)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("el detalle de un desarrollo ajeno responde 404")
    void foreignDetailIsNotFound() throws Exception {
        mockMvc.perform(get("/api/developments/" + developmentId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el detalle de un desarrollo inexistente responde 404")
    void missingDetailIsNotFound() throws Exception {
        mockMvc.perform(get("/api/developments/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }
}
