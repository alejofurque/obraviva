package com.obraviva.platform.developments;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reinicio de contraseña por el dueño.
 *
 * <p>Los casos que importan son los negativos: una cuenta que también existe fuera de
 * la organización no se puede reiniciar desde ella, porque sería tomar el acceso de
 * una persona a una empresa ajena.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class AccountResetIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";
    private static final String INITIAL_PASSWORD =
            "^[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}$";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Organization organization;
    private String ownerToken;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        organization = organizationRepository.save(new Organization("Constructora Reinicio"));
        ownerToken = token(login(userIn("duenio", organization, OrganizationRole.OWNER), PASSWORD));

        String development = ok(send(ownerToken, "/api/developments", """
                {"organizationId":"%s","name":"Torre Reinicio"}""".formatted(organization.getId())));
        String unit = ok(send(ownerToken,
                "/api/developments/" + JSON.readTree(development).get("id").asString() + "/units", """
                {"code":"7A","description":"Dos ambientes"}"""));
        unitId = UUID.fromString(JSON.readTree(unit).get("id").asString());
    }

    // --- Armado ---

    private String userIn(String prefix, Organization org, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        if (org != null) {
            memberRepository.save(new OrganizationMember(org, user.getId(), role));
        }
        return email;
    }

    private UUID orgMemberIdOf(String email, Organization org) {
        UUID userId = userRepository.findByEmail(email).orElseThrow().getId();
        return memberRepository.findByOrganizationIdOrderByCreatedAtAsc(org.getId()).stream()
                .filter(member -> member.getUserId().equals(userId))
                .findFirst().orElseThrow().getId();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, password)));
    }

    private static String token(ResultActions login) throws Exception {
        return JSON.readTree(ok(login)).get("accessToken").asString();
    }

    private ResultActions send(String token, String url, String body) throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String ok(ResultActions actions) throws Exception {
        return actions.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private UUID linkBuyer(String email) throws Exception {
        return UUID.fromString(JSON.readTree(ok(send(ownerToken, "/api/units/" + unitId + "/members", """
                {"email":"%s"}""".formatted(email)))).get("id").asString());
    }

    private String resetMemberUrl(UUID memberId) {
        return "/api/organizations/" + organization.getId() + "/members/" + memberId + "/reset-password";
    }

    // --- Reinicios permitidos ---

    @Test
    @DisplayName("el dueño reinicia a su encargado: la nueva sirve, la vieja no, y sus sesiones se cortan")
    void ownerResetsMember() throws Exception {
        String email = userIn("encargado", organization, OrganizationRole.MEMBER);
        JsonNode memberSession = JSON.readTree(ok(login(email, PASSWORD)));

        String response = ok(send(ownerToken, resetMemberUrl(orgMemberIdOf(email, organization)), "{}"));
        String initialPassword = JSON.readTree(response).get("initialPassword").asString();

        org.assertj.core.api.Assertions.assertThat(initialPassword).matches(INITIAL_PASSWORD);
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        login(email, initialPassword).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(memberSession.get("refreshToken").asString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("el dueño reinicia a un comprador de su obra")
    void ownerResetsBuyer() throws Exception {
        String email = userIn("comprador", null, null);
        UUID linkId = linkBuyer(email);

        send(ownerToken, "/api/unit-members/" + linkId + "/reset-password", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialPassword").value(matchesPattern(INITIAL_PASSWORD)));
        login(email, PASSWORD).andExpect(status().isUnauthorized());
    }

    // --- Reinicios bloqueados ---

    @Test
    @DisplayName("una cuenta que es dueña de otra organización no se reinicia desde esta")
    void accountOwningAnotherOrganizationIsProtected() throws Exception {
        Organization other = organizationRepository.save(new Organization("Constructora Ajena"));
        String email = userIn("doble", other, OrganizationRole.OWNER);
        memberRepository.save(new OrganizationMember(
                organization, userRepository.findByEmail(email).orElseThrow().getId(), OrganizationRole.MEMBER));
        UUID linkId = linkBuyer(email);

        send(ownerToken, resetMemberUrl(orgMemberIdOf(email, organization)), "{}")
                .andExpect(status().isConflict());
        send(ownerToken, "/api/unit-members/" + linkId + "/reset-password", "{}")
                .andExpect(status().isConflict());
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("un comprador que también tiene unidades en otra organización no se reinicia")
    void buyerWithUnitsElsewhereIsProtected() throws Exception {
        Organization other = organizationRepository.save(new Organization("Otra Constructora"));
        String otherOwnerToken = token(login(userIn("otro-duenio", other, OrganizationRole.OWNER), PASSWORD));
        String otherDevelopment = ok(send(otherOwnerToken, "/api/developments", """
                {"organizationId":"%s","name":"Torre Ajena"}""".formatted(other.getId())));
        String otherUnit = ok(send(otherOwnerToken,
                "/api/developments/" + JSON.readTree(otherDevelopment).get("id").asString() + "/units", """
                {"code":"1B","description":"Monoambiente"}"""));

        String email = userIn("comprador-doble", null, null);
        UUID linkId = linkBuyer(email);
        ok(send(otherOwnerToken, "/api/units/" + JSON.readTree(otherUnit).get("id").asString() + "/members", """
                {"email":"%s"}""".formatted(email)));

        send(ownerToken, "/api/unit-members/" + linkId + "/reset-password", "{}")
                .andExpect(status().isConflict());
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("el dueño no se reinicia a sí mismo ni a otro dueño")
    void ownersAreNotReset() throws Exception {
        UUID ownerMemberId = memberRepository.findByOrganizationIdOrderByCreatedAtAsc(organization.getId())
                .getFirst().getId();

        send(ownerToken, resetMemberUrl(ownerMemberId), "{}").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("un encargado no reinicia a nadie")
    void memberCannotReset() throws Exception {
        String memberEmail = userIn("encargado", organization, OrganizationRole.MEMBER);
        String memberToken = token(login(memberEmail, PASSWORD));
        String otherEmail = userIn("otro-encargado", organization, OrganizationRole.MEMBER);
        UUID linkId = linkBuyer(userIn("comprador", null, null));

        send(memberToken, resetMemberUrl(orgMemberIdOf(otherEmail, organization)), "{}")
                .andExpect(status().isForbidden());
        send(memberToken, "/api/unit-members/" + linkId + "/reset-password", "{}")
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("desde otra organización, los miembros y vínculos no existen")
    void foreignResetsAreNotFound() throws Exception {
        String email = userIn("encargado", organization, OrganizationRole.MEMBER);
        UUID linkId = linkBuyer(userIn("comprador", null, null));
        Organization other = organizationRepository.save(new Organization("Constructora Intrusa"));
        String intruderToken = token(login(userIn("intruso", other, OrganizationRole.OWNER), PASSWORD));

        send(intruderToken, resetMemberUrl(orgMemberIdOf(email, organization)), "{}")
                .andExpect(status().isNotFound());
        send(intruderToken, "/api/unit-members/" + linkId + "/reset-password", "{}")
                .andExpect(status().isNotFound());
    }
}
