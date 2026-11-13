package com.obraviva.platform.organizations;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.support.TestMinio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Alta de encargados en una organización.
 *
 * <p>ObraViva no tiene registro público: las cuentas las crea el dueño. Si el correo
 * no tiene cuenta, se crea con una contraseña inicial que se muestra una sola vez, y
 * el dueño se la comunica a la persona. Si ya la tiene, sólo se vincula y su
 * contraseña no se toca.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
class OrganizationMemberIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        TestMinio.register(registry);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    /** Doce caracteres en tres grupos, sin los que se confunden al dictarlos. */
    private static final String INITIAL_PASSWORD_PATTERN =
            "^[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}$";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private UUID organizationId;

    @BeforeEach
    void setUp() throws Exception {
        String ownerEmail = createUser("duenio");
        ownerToken = login(ownerEmail, PASSWORD);

        String response = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Constructora Equipo"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        organizationId = UUID.fromString(JSON.readTree(response).get("id").asString());

        mockMvc.perform(post("/api/developments")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationId":"%s","name":"Torre Equipo"}""".formatted(organizationId)))
                .andExpect(status().isCreated());
    }

    // --- Armado ---

    private String createUser(String prefix) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        return email;
    }

    private String login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("accessToken").asString();
    }

    private ResultActions addMember(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/organizations/" + organizationId + "/members")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String membersUrl() {
        return "/api/organizations/" + organizationId + "/members";
    }

    // --- Alta ---

    @Test
    @DisplayName("un correo sin cuenta crea al encargado con contraseña inicial, y puede entrar")
    void newEmailCreatesMemberWithInitialPassword() throws Exception {
        String email = "encargado-" + UUID.randomUUID() + "@obraviva.test";

        String response = addMember(ownerToken, """
                {"email":"%s","fullName":"Raúl Encargado"}""".formatted(email))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.fullName").value("Raúl Encargado"))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.accountCreated").value(true))
                .andExpect(jsonPath("$.initialPassword").value(matchesPattern(INITIAL_PASSWORD_PATTERN)))
                .andReturn().getResponse().getContentAsString();

        String initialPassword = JSON.readTree(response).get("initialPassword").asString();
        String memberUserId = JSON.readTree(response).get("userId").asString();
        String memberToken = login(email, initialPassword);

        // Sumarlo a la empresa no le da obras: las ve cuando el dueño lo asigna.
        mockMvc.perform(get("/api/developments").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String developmentId = JSON.readTree(mockMvc.perform(get("/api/developments")
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asString();
        mockMvc.perform(post("/api/developments/" + developmentId + "/team")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s"}""".formatted(memberUserId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/developments").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Torre Equipo"))
                .andExpect(jsonPath("$[0].role").value("MEMBER"));
    }

    @Test
    @DisplayName("un correo con cuenta se vincula sin crear ni tocar la contraseña")
    void existingAccountIsLinkedWithoutPassword() throws Exception {
        String email = createUser("existente");

        addMember(ownerToken, """
                {"email":"%s"}""".formatted(email))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountCreated").value(false))
                .andExpect(jsonPath("$.initialPassword").value(nullValue()))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        login(email, PASSWORD);
    }

    @Test
    @DisplayName("el correo se normaliza, y el login no distingue mayúsculas")
    void emailIsNormalized() throws Exception {
        String local = "Mixto-" + UUID.randomUUID();
        String typed = local + "@ObraViva.Test";

        String response = addMember(ownerToken, """
                {"email":"%s","fullName":"Persona Mixta"}""".formatted(typed))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(local.toLowerCase() + "@obraviva.test"))
                .andReturn().getResponse().getContentAsString();
        String initialPassword = JSON.readTree(response).get("initialPassword").asString();

        login(local + "@OBRAVIVA.test", initialPassword);
    }

    @Test
    @DisplayName("sin cuenta y sin nombre no se puede crear")
    void newEmailWithoutNameIsRejected() throws Exception {
        addMember(ownerToken, """
                {"email":"sin-nombre-%s@obraviva.test"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("no se suma dos veces a la misma persona, ni al propio dueño")
    void duplicatesAreRejected() throws Exception {
        String email = createUser("repetido");
        addMember(ownerToken, """
                {"email":"%s"}""".formatted(email)).andExpect(status().isCreated());

        addMember(ownerToken, """
                {"email":"%s"}""".formatted(email)).andExpect(status().isConflict());

        String ownerEmail = JSON.readTree(mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getContentAsString()).get("email").asString();
        addMember(ownerToken, """
                {"email":"%s"}""".formatted(ownerEmail)).andExpect(status().isConflict());
    }

    // --- Permisos ---

    @Test
    @DisplayName("un encargado no suma, lista ni quita miembros")
    void memberCannotManageMembers() throws Exception {
        String email = createUser("encargado");
        String memberId = JSON.readTree(addMember(ownerToken, """
                        {"email":"%s"}""".formatted(email))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        String memberToken = login(email, PASSWORD);

        addMember(memberToken, """
                {"email":"otro-%s@obraviva.test","fullName":"Otro"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(membersUrl()).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(membersUrl() + "/" + memberId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("una organización ajena responde 404")
    void foreignOrganizationIsNotFound() throws Exception {
        String outsiderToken = login(createUser("ajeno"), PASSWORD);

        mockMvc.perform(get(membersUrl()).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
        addMember(outsiderToken, """
                {"email":"x-%s@obraviva.test","fullName":"X"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // --- Listado y baja ---

    @Test
    @DisplayName("el listado muestra al dueño y a los encargados con nombre y rol")
    void listShowsOwnerAndMembers() throws Exception {
        addMember(ownerToken, """
                {"email":"%s"}""".formatted(createUser("encargado")))
                .andExpect(status().isCreated());

        mockMvc.perform(get(membersUrl()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[0].fullName").value("Usuario duenio"))
                .andExpect(jsonPath("$[1].role").value("MEMBER"))
                .andExpect(jsonPath("$[1].fullName").value("Usuario encargado"));
    }

    @Test
    @DisplayName("quitar a un encargado le corta el acceso a las obras")
    void removingMemberRevokesAccess() throws Exception {
        String email = createUser("saliente");
        String memberId = JSON.readTree(addMember(ownerToken, """
                        {"email":"%s"}""".formatted(email))
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        String memberToken = login(email, PASSWORD);

        mockMvc.perform(delete(membersUrl() + "/" + memberId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/developments").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("el dueño no se puede quitar")
    void ownerCannotBeRemoved() throws Exception {
        String ownerMemberId = JSON.readTree(mockMvc.perform(get(membersUrl())
                        .header("Authorization", "Bearer " + ownerToken))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asString();

        mockMvc.perform(delete(membersUrl() + "/" + ownerMemberId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict());
    }
}
