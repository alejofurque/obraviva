package com.obraviva.platform.developments;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Encargados por obra: el encargado sólo llega a las obras que tiene asignadas, y a
 * ningún recurso de las demás, aunque sean de su misma empresa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class DevelopmentTeamIntegrationTest {

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
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Organization organization;
    private String ownerToken;
    private String supervisorToken;
    private UUID supervisorId;
    private UUID ownerId;
    private String assignedWork;
    private String otherWork;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/firmada");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/firmada");
        when(storageService.exists(anyString())).thenReturn(true);

        organization = organizationRepository.save(new Organization("Constructora Equipos"));
        ownerId = user("duenio", OrganizationRole.OWNER);
        supervisorId = user("encargado", OrganizationRole.MEMBER);
        ownerToken = login(emailOf(ownerId));
        supervisorToken = login(emailOf(supervisorId));

        assignedWork = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Casa asignada","kind":"SINGLE"}""".formatted(organization.getId()))));
        otherWork = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Casa ajena","kind":"SINGLE"}""".formatted(organization.getId()))));

        ok(send(ownerToken, post("/api/developments/" + assignedWork + "/team"), """
                {"userId":"%s"}""".formatted(supervisorId)));
    }

    @Test
    @DisplayName("El encargado lista y abre sólo la obra asignada; la otra no existe para él")
    void supervisorSeesOnlyAssignedWork() throws Exception {
        send(supervisorToken, get("/api/developments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Casa asignada"));

        send(supervisorToken, get("/api/developments/" + assignedWork)).andExpect(status().isOk());
        send(supervisorToken, get("/api/developments/" + otherWork)).andExpect(status().isNotFound());
        send(supervisorToken, get("/api/developments/" + otherWork + "/progress")).andExpect(status().isNotFound());
        send(supervisorToken, get("/api/developments/" + otherWork + "/captures")).andExpect(status().isNotFound());
        send(supervisorToken, post("/api/developments/" + otherWork + "/progress"), """
                {"entryDate":"2026-09-01","description":"No debería poder"}""").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Los recursos de una obra no asignada tampoco se alcanzan por su identificador")
    void resourcesOfOtherWorkAreNotReachableById() throws Exception {
        String stage = idOf(ok(send(ownerToken, post("/api/developments/" + otherWork + "/stages"), """
                {"name":"Estructura","orderIndex":1}""")));
        String entry = idOf(ok(send(ownerToken, post("/api/developments/" + otherWork + "/progress"), """
                {"entryDate":"2026-09-01","description":"Losa hormigonada"}""")));
        String unit = JSON.readTree(ok(send(ownerToken, get("/api/developments/" + otherWork + "/units"))))
                .get(0).get("id").asString();

        send(supervisorToken, patch("/api/stages/" + stage + "/plan"), """
                {"progressPercent":50}""").andExpect(status().isNotFound());
        send(supervisorToken, patch("/api/developments/stages/" + stage), """
                {"status":"COMPLETED"}""").andExpect(status().isNotFound());
        send(supervisorToken, post("/api/progress/" + entry + "/corrections"), """
                {"entryDate":"2026-09-01","description":"Corregido"}""").andExpect(status().isNotFound());
        send(supervisorToken, get("/api/my-units/" + unit)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Al quitarlo de la obra deja de verla; al volver a asignarlo, la recupera")
    void unassignRemovesAccess() throws Exception {
        JsonNode team = JSON.readTree(ok(send(ownerToken, delete("/api/developments/" + assignedWork + "/team/" + supervisorId))));
        assertThat(team.get("assigned").size()).isZero();
        assertThat(team.get("available").get(0).get("userId").asString()).isEqualTo(supervisorId.toString());

        send(supervisorToken, get("/api/developments/" + assignedWork)).andExpect(status().isNotFound());

        ok(send(ownerToken, post("/api/developments/" + assignedWork + "/team"), """
                {"userId":"%s"}""".formatted(supervisorId)));
        send(supervisorToken, get("/api/developments/" + assignedWork)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("El dueño ve todas sin asignarse; sólo él administra el equipo de una obra")
    void onlyOwnerManagesTeam() throws Exception {
        send(ownerToken, get("/api/developments")).andExpect(jsonPath("$.length()").value(2));

        JsonNode team = JSON.readTree(ok(send(ownerToken, get("/api/developments/" + assignedWork + "/team"))));
        assertThat(team.get("assigned").get(0).get("email").asString()).isEqualTo(emailOf(supervisorId));
        assertThat(team.get("assigned").get(0).get("assignedAt").isNull()).isFalse();

        send(supervisorToken, get("/api/developments/" + assignedWork + "/team")).andExpect(status().isForbidden());
        send(supervisorToken, post("/api/developments/" + assignedWork + "/team"), """
                {"userId":"%s"}""".formatted(supervisorId)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sólo se asigna a un encargado de la empresa, y una sola vez")
    void assignmentRules() throws Exception {
        send(ownerToken, post("/api/developments/" + assignedWork + "/team"), """
                {"userId":"%s"}""".formatted(supervisorId)).andExpect(status().isConflict());
        send(ownerToken, post("/api/developments/" + assignedWork + "/team"), """
                {"userId":"%s"}""".formatted(ownerId)).andExpect(status().isBadRequest());
        send(ownerToken, post("/api/developments/" + assignedWork + "/team"), """
                {"userId":"%s"}""".formatted(UUID.randomUUID())).andExpect(status().isBadRequest());
        send(ownerToken, post("/api/developments/" + assignedWork + "/team"), """
                {}""").andExpect(status().isBadRequest());
        send(ownerToken, delete("/api/developments/" + otherWork + "/team/" + supervisorId)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Quien deja la empresa pierde también sus asignaciones de obra")
    void leavingOrganizationRemovesAssignments() throws Exception {
        UUID membership = memberRepository.findByOrganizationIdOrderByCreatedAtAsc(organization.getId()).stream()
                .filter(member -> member.getUserId().equals(supervisorId))
                .findFirst().orElseThrow().getId();

        send(ownerToken, delete("/api/organizations/" + organization.getId() + "/members/" + membership))
                .andExpect(status().is2xxSuccessful());

        assertThat(assignmentRepository.existsByDevelopmentIdAndUserId(UUID.fromString(assignedWork), supervisorId)).isFalse();
    }

    // --- Armado ---

    private UUID user(String prefix, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User saved = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        memberRepository.save(new OrganizationMember(organization, saved.getId(), role));
        return saved.getId();
    }

    private String emailOf(UUID userId) {
        return userRepository.findById(userId).orElseThrow().getEmail();
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

    private static String idOf(String json) {
        return JSON.readTree(json).get("id").asString();
    }
}
