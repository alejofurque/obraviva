package com.obraviva.platform.developments;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.support.TestMinio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aislamiento entre organizaciones: dos desarrollistas de empresas
 * distintas no pueden ver ni tocar los datos del otro. Requisito
 * obligatorio del documento maestro, no opcional.
 */
@SpringBootTest
@AutoConfigureMockMvc // resolver import con quick-fix (copiar del test de auth)
@Testcontainers
class OrganizationIsolationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        TestMinio.register(registry);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String tokenA;
    private String tokenB;
    private UUID organizationA;
    private UUID developmentOfA;

    @BeforeEach
    void setUp() throws Exception {
        String emailA = "a-" + UUID.randomUUID() + "@obraviva.test";
        String emailB = "b-" + UUID.randomUUID() + "@obraviva.test";

        organizationA = createUserWithOrganization(emailA, "Constructora A");
        createUserWithOrganization(emailB, "Constructora B");

        tokenA = login(emailA);
        tokenB = login(emailB);

        String response = mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationA, "Edificio Norte", "Av. Colón 1234"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        developmentOfA = UUID.fromString(JSON.readTree(response).get("id").asString());
    }

    private UUID createUserWithOrganization(String email, String organizationName) {
        User user = userRepository.save(
                new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + organizationName));
        Organization organization = organizationRepository.save(new Organization(organizationName));
        memberRepository.save(
                new OrganizationMember(organization, user.getId(), OrganizationRole.OWNER));
        return organization.getId();
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

    @Test
    void ownerSeesOwnDevelopment() throws Exception {
        mockMvc.perform(get("/api/developments")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Edificio Norte"));
    }

    @Test
    void otherOrganizationSeesNothing() throws Exception {
        mockMvc.perform(get("/api/developments")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void otherOrganizationCannotReadForeignDevelopmentUnits() throws Exception {
        mockMvc.perform(get("/api/developments/" + developmentOfA + "/units")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherOrganizationCannotCreateUnitInForeignDevelopment() throws Exception {
        mockMvc.perform(post("/api/developments/" + developmentOfA + "/units")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateUnitRequest("1A", "Intrusa"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotCreateDevelopmentInForeignOrganization() throws Exception {
        mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateDevelopmentRequest(
                                organizationA, "Intento intruso", null))))
                .andExpect(status().isNotFound());
    }
}