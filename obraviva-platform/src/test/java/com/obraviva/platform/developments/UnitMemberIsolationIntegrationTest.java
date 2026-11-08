package com.obraviva.platform.developments;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.UnitMemberDtos.AddUnitMemberRequest;
import com.obraviva.platform.organizations.CreateOrganizationRequest;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aislamiento del segundo camino de acceso: el vínculo comprador-unidad.
 *
 * <p>Lo que se prueba acá no es que el comprador vea su unidad, sino que no vea nada
 * más: ni el desarrollo completo, ni las otras unidades, ni la administración de
 * miembros.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class UnitMemberIsolationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UnitMemberRepository memberRepository;

    private String developerToken;
    private String buyerToken;
    private String buyerEmail;
    private UUID developmentId;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        memberRepository.deleteAll();

        String developerEmail = "dev-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(
                new User(developerEmail, passwordEncoder.encode(PASSWORD), "Desarrollista"));
        developerToken = login(developerEmail);

        buyerEmail = "comprador-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(buyerEmail, passwordEncoder.encode(PASSWORD), "Compradora"));
        buyerToken = login(buyerEmail);

        UUID organizationId = UUID.fromString(JSON.readTree(
                perform(post("/api/organizations"), new CreateOrganizationRequest("Constructora Units")))
                .get("id").asString());

        developmentId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments"),
                        new CreateDevelopmentRequest(organizationId, "Torre Units", null)))
                .get("id").asString());

        unitId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments/" + developmentId + "/units"),
                        new CreateUnitRequest("4D", "Cuatro ambientes")))
                .get("id").asString());
    }

    private String perform(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return mockMvc.perform(builder
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
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

    private String linkBuyer() throws Exception {
        return mockMvc.perform(post("/api/units/" + unitId + "/members")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new AddUnitMemberRequest(buyerEmail))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("el desarrollista vincula al comprador por su correo")
    void developerLinksBuyerByEmail() throws Exception {
        mockMvc.perform(post("/api/units/" + unitId + "/members")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new AddUnitMemberRequest(buyerEmail))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(buyerEmail))
                .andExpect(jsonPath("$.fullName").value("Compradora"));

        assertThat(memberRepository.findByUserId(
                userRepository.findByEmail(buyerEmail).orElseThrow().getId())).hasSize(1);
    }

    @Test
    @DisplayName("un correo sin cuenta no se puede vincular")
    void unknownEmailCannotBeLinked() throws Exception {
        mockMvc.perform(post("/api/units/" + unitId + "/members")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new AddUnitMemberRequest("nadie@obraviva.test"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un correo sin cuenta, con nombre, crea al comprador y puede ver su unidad")
    void unknownEmailWithNameCreatesBuyer() throws Exception {
        String newBuyerEmail = "nueva-" + UUID.randomUUID() + "@obraviva.test";

        String response = mockMvc.perform(post("/api/units/" + unitId + "/members")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","fullName":"Marta Compradora"}""".formatted(newBuyerEmail)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(newBuyerEmail))
                .andExpect(jsonPath("$.fullName").value("Marta Compradora"))
                .andExpect(jsonPath("$.accountCreated").value(true))
                .andExpect(jsonPath("$.initialPassword").isString())
                .andReturn().getResponse().getContentAsString();

        String initialPassword = JSON.readTree(response).get("initialPassword").asString();
        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(newBuyerEmail, initialPassword)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newBuyerToken = JSON.readTree(loginResponse).get("accessToken").asString();

        mockMvc.perform(get("/api/my-units").header("Authorization", "Bearer " + newBuyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].unitCode").value("4D"));
    }

    @Test
    @DisplayName("vincular una cuenta existente no devuelve contraseña")
    void existingBuyerGetsNoPassword() throws Exception {
        mockMvc.perform(post("/api/units/" + unitId + "/members")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new AddUnitMemberRequest(buyerEmail))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountCreated").value(false))
                .andExpect(jsonPath("$.initialPassword").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("no se vincula dos veces a la misma persona")
    void buyerIsNotLinkedTwice() throws Exception {
        linkBuyer();

        mockMvc.perform(post("/api/units/" + unitId + "/members")
                        .header("Authorization", "Bearer " + developerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new AddUnitMemberRequest(buyerEmail))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("el comprador no ve el desarrollo al que pertenece su unidad")
    void buyerDoesNotSeeTheDevelopment() throws Exception {
        linkBuyer();

        // El comprador no pertenece a la organización: los endpoints del desarrollo
        // filtran por organización y no deben devolverle nada.
        mockMvc.perform(get("/api/developments")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/developments/" + developmentId + "/units")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el comprador no administra los miembros de su propia unidad")
    void buyerCannotManageMembers() throws Exception {
        String created = linkBuyer();
        UUID memberId = UUID.fromString(JSON.readTree(created).get("id").asString());

        mockMvc.perform(get("/api/units/" + unitId + "/members")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/units/" + unitId + "/members")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(
                                new AddUnitMemberRequest("otro@obraviva.test"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/unit-members/" + memberId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("nadie de otra organización vincula compradores a esta unidad")
    void outsiderCannotLinkBuyers() throws Exception {
        String outsiderEmail = "ajeno-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(outsiderEmail, passwordEncoder.encode(PASSWORD), "Ajeno"));
        String outsiderToken = login(outsiderEmail);

        mockMvc.perform(post("/api/units/" + unitId + "/members")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new AddUnitMemberRequest(buyerEmail))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("desvincular al comprador le quita el acceso")
    void removingMemberRemovesAccess() throws Exception {
        String created = linkBuyer();
        UUID memberId = UUID.fromString(JSON.readTree(created).get("id").asString());

        mockMvc.perform(delete("/api/unit-members/" + memberId)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isNoContent());

        assertThat(memberRepository.existsByUnitIdAndUserId(
                unitId, userRepository.findByEmail(buyerEmail).orElseThrow().getId())).isFalse();
    }
}