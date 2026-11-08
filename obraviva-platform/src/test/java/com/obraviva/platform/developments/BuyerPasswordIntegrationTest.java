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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contraseña elegida por el dueño para un comprador, al crear su cuenta o después con
 * "Nueva contraseña". La elegida no vuelve nunca en una respuesta; sin elegir, se genera
 * una y se devuelve una sola vez, como siempre.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class BuyerPasswordIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";
    private static final String CHOSEN = "Obra-Nogal-26";
    private static final String GENERATED =
            "^[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}$";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        Organization organization = organizationRepository.save(new Organization("Constructora Contraseñas"));
        ownerToken = token(login(userIn("duenio", organization, OrganizationRole.OWNER), PASSWORD));
        unitId = unitOf(ownerToken, organization);
    }

    // --- Armado ---

    private String userIn(String prefix, Organization org, OrganizationRole role) {
        String email = newEmail(prefix);
        User user = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        if (org != null) {
            memberRepository.save(new OrganizationMember(org, user.getId(), role));
        }
        return email;
    }

    private static String newEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@obraviva.test";
    }

    private UUID unitOf(String token, Organization org) throws Exception {
        String development = ok(send(token, "/api/developments", """
                {"organizationId":"%s","name":"Barrio Contraseñas"}""".formatted(org.getId())));
        String unit = ok(send(token,
                "/api/developments/" + JSON.readTree(development).get("id").asString() + "/units", """
                {"code":"Casa 1","description":"Tres dormitorios"}"""));
        return UUID.fromString(JSON.readTree(unit).get("id").asString());
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

    private ResultActions addBuyer(String token, UUID unit, String email, String password) throws Exception {
        String passwordField = password == null ? "" : ",\"password\":\"" + password + "\"";
        return send(token, "/api/units/" + unit + "/members", """
                {"email":"%s","fullName":"Marta Compradora"%s}""".formatted(email, passwordField));
    }

    // --- Alta ---

    @Test
    @DisplayName("con contraseña elegida, la cuenta se crea con esa y la respuesta no la devuelve")
    void createWithChosenPassword() throws Exception {
        String email = newEmail("compradora");

        JsonNode added = JSON.readTree(ok(addBuyer(ownerToken, unitId, email, CHOSEN)));

        assertThat(added.get("accountCreated").asBoolean()).isTrue();
        assertThat(added.get("initialPassword").isNull()).isTrue();
        assertThat(added.toString()).doesNotContain(CHOSEN);
        login(email, CHOSEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("sin contraseña, o vacía, se genera una y se devuelve una sola vez")
    void createWithoutPasswordGeneratesOne() throws Exception {
        String email = newEmail("compradora");

        String generated = JSON.readTree(ok(addBuyer(ownerToken, unitId, email, ""))).get("initialPassword").asString();

        assertThat(generated).matches(GENERATED);
        login(email, generated).andExpect(status().isOk());
    }

    @Test
    @DisplayName("una contraseña de menos de 8 caracteres se rechaza con un mensaje claro y no crea la cuenta")
    void shortPasswordIsRejected() throws Exception {
        String email = newEmail("compradora");

        String message = addBuyer(ownerToken, unitId, email, "corta1")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getErrorMessage();

        assertThat(message).isEqualTo("La contraseña tiene que tener al menos 8 caracteres.");
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    @DisplayName("si el correo ya tiene cuenta, la contraseña del formulario se ignora y no cambia nada")
    void existingAccountKeepsItsPassword() throws Exception {
        String email = userIn("existente", null, null);

        JsonNode added = JSON.readTree(ok(addBuyer(ownerToken, unitId, email, CHOSEN)));

        assertThat(added.get("accountCreated").asBoolean()).isFalse();
        login(email, PASSWORD).andExpect(status().isOk());
        login(email, CHOSEN).andExpect(status().isUnauthorized());
    }

    // --- Nueva contraseña ---

    @Test
    @DisplayName("el dueño le pone una contraseña elegida a un comprador: no vuelve, la vieja deja de servir y sus sesiones se cortan")
    void ownerSetsChosenPassword() throws Exception {
        String email = newEmail("compradora");
        UUID linkId = UUID.fromString(JSON.readTree(ok(addBuyer(ownerToken, unitId, email, CHOSEN))).get("id").asString());
        JsonNode session = JSON.readTree(ok(login(email, CHOSEN)));

        String response = ok(send(ownerToken, "/api/unit-members/" + linkId + "/reset-password", """
                {"password":"Otra-Clave-2026"}"""));

        assertThat(JSON.readTree(response).get("initialPassword").isNull()).isTrue();
        assertThat(response).doesNotContain("Otra-Clave-2026");
        login(email, "Otra-Clave-2026").andExpect(status().isOk());
        login(email, CHOSEN).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(session.get("refreshToken").asString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("una contraseña nueva corta se rechaza y la anterior sigue sirviendo")
    void shortNewPasswordIsRejected() throws Exception {
        String email = newEmail("compradora");
        UUID linkId = UUID.fromString(JSON.readTree(ok(addBuyer(ownerToken, unitId, email, CHOSEN))).get("id").asString());

        send(ownerToken, "/api/unit-members/" + linkId + "/reset-password", """
                {"password":"1234567"}""").andExpect(status().isBadRequest());
        login(email, CHOSEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("un comprador no puede cambiar contraseñas: para él la administración no existe")
    void buyerCannotChangePasswords() throws Exception {
        String email = newEmail("compradora");
        UUID linkId = UUID.fromString(JSON.readTree(ok(addBuyer(ownerToken, unitId, email, CHOSEN))).get("id").asString());
        String buyerToken = token(login(email, CHOSEN));

        send(buyerToken, "/api/unit-members/" + linkId + "/reset-password", """
                {"password":"Tomada-por-mi"}""").andExpect(status().isNotFound());
        login(email, CHOSEN).andExpect(status().isOk());
    }

    @Test
    @DisplayName("una unidad de otra organización da 404, al crear y al cambiar la contraseña")
    void foreignUnitIsNotFound() throws Exception {
        String email = newEmail("compradora");
        UUID linkId = UUID.fromString(JSON.readTree(ok(addBuyer(ownerToken, unitId, email, CHOSEN))).get("id").asString());
        Organization other = organizationRepository.save(new Organization("Constructora Intrusa"));
        String intruderToken = token(login(userIn("intruso", other, OrganizationRole.OWNER), PASSWORD));

        addBuyer(intruderToken, unitId, newEmail("colada"), CHOSEN).andExpect(status().isNotFound());
        send(intruderToken, "/api/unit-members/" + linkId + "/reset-password", """
                {"password":"Tomada-por-mi"}""").andExpect(status().isNotFound());
        login(email, CHOSEN).andExpect(status().isOk());
    }
}
