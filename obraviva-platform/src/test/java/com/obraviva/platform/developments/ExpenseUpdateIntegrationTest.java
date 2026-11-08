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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Corrección de un gasto con {@code PATCH /api/expenses/{id}}: mismas reglas que el alta.
 * Lo hace el dueño; desde otra organización el gasto no existe; el encargado y el
 * comprador no pueden; y la etapa tiene que ser de la misma casa del gasto.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class ExpenseUpdateIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StageRepository stageRepository;
    @Autowired private ExpenseRepository expenseRepository;

    private Organization organization;
    private String ownerToken;
    private UUID developmentId;
    private UUID houseOne;
    private UUID houseTwo;
    private UUID commonStage;

    @BeforeEach
    void setUp() throws Exception {
        organization = organizationRepository.save(new Organization("Constructora Gastos"));
        ownerToken = token(login(userIn("duenio", organization, OrganizationRole.OWNER), PASSWORD));
        developmentId = id(ok(send(post("/api/developments"), ownerToken, """
                {"organizationId":"%s","name":"Barrio Gastos"}""".formatted(organization.getId()))));
        JsonNode houses = JSON.readTree(ok(send(post("/api/developments/" + developmentId + "/units/batch"), ownerToken, """
                {"prefix":"Casa","firstNumber":1,"count":2,"withStandardStages":true}""")));
        houseOne = UUID.fromString(houses.get(0).get("id").asString());
        houseTwo = UUID.fromString(houses.get(1).get("id").asString());
        commonStage = id(ok(send(post("/api/developments/" + developmentId + "/stages"), ownerToken, """
                {"name":"Calles","orderIndex":0}""")));
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

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, password)));
    }

    private static String token(ResultActions login) throws Exception {
        return JSON.readTree(ok(login)).get("accessToken").asString();
    }

    private ResultActions send(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String token, String body) throws Exception {
        return mockMvc.perform(builder
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String ok(ResultActions actions) throws Exception {
        return actions.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static UUID id(String json) {
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    private UUID stageOf(UUID unitId, int order) {
        List<Stage> stages = stageRepository.findAll().stream()
                .filter(stage -> unitId.equals(stage.getUnitId()))
                .sorted(java.util.Comparator.comparingInt(Stage::getOrderIndex))
                .toList();
        return stages.get(order).getId();
    }

    private UUID houseExpense(UUID unitId, UUID stageId) throws Exception {
        return id(ok(send(post("/api/developments/" + developmentId + "/expenses"), ownerToken, """
                {"stageId":"%s","incurredOn":"2026-09-10","amount":150000,"description":"Hormigón","unitId":"%s"}"""
                .formatted(stageId, unitId))));
    }

    private ResultActions edit(String token, UUID expenseId, String body) throws Exception {
        return send(patch("/api/expenses/" + expenseId), token, body);
    }

    // --- Casos ---

    @Test
    @DisplayName("el dueño corrige su gasto: etapa, fecha, monto y concepto")
    void ownerEditsExpense() throws Exception {
        UUID expense = houseExpense(houseOne, stageOf(houseOne, 0));
        UUID otherStage = stageOf(houseOne, 2);

        JsonNode updated = JSON.readTree(ok(edit(ownerToken, expense, """
                {"stageId":"%s","incurredOn":"2026-09-12","amount":1250000.50,"description":"Hierro del 8"}"""
                .formatted(otherStage))));

        assertThat(updated.get("stageId").asString()).isEqualTo(otherStage.toString());
        assertThat(updated.get("incurredOn").asString()).isEqualTo("2026-09-12");
        assertThat(updated.get("description").asString()).isEqualTo("Hierro del 8");
        assertThat(updated.get("unitId").asString()).isEqualTo(houseOne.toString());
        Expense stored = expenseRepository.findById(expense).orElseThrow();
        assertThat(stored.getAmount()).isEqualByComparingTo(new BigDecimal("1250000.50"));
    }

    @Test
    @DisplayName("sin etapa queda como gasto general, y sin concepto el detalle queda vacío")
    void editToGeneralWithoutConcept() throws Exception {
        UUID expense = houseExpense(houseOne, stageOf(houseOne, 0));

        JsonNode updated = JSON.readTree(ok(edit(ownerToken, expense, """
                {"stageId":null,"incurredOn":"2026-09-10","amount":150000,"description":"  "}""")));

        assertThat(updated.get("stageId").isNull()).isTrue();
        assertThat(updated.get("description").isNull()).isTrue();
    }

    @Test
    @DisplayName("cambiar la etapa por una de otra casa, o por una común en un gasto de casa, se rechaza")
    void stageOfAnotherHouseIsRejected() throws Exception {
        UUID expense = houseExpense(houseOne, stageOf(houseOne, 0));

        edit(ownerToken, expense, """
                {"stageId":"%s","incurredOn":"2026-09-10","amount":150000}""".formatted(stageOf(houseTwo, 0)))
                .andExpect(status().isBadRequest());
        edit(ownerToken, expense, """
                {"stageId":"%s","incurredOn":"2026-09-10","amount":150000}""".formatted(commonStage))
                .andExpect(status().isBadRequest());
        assertThat(expenseRepository.findById(expense).orElseThrow().getStageId()).isEqualTo(stageOf(houseOne, 0));
    }

    @Test
    @DisplayName("un gasto de obras comunes acepta una etapa común")
    void commonExpenseTakesCommonStage() throws Exception {
        UUID expense = id(ok(send(post("/api/developments/" + developmentId + "/expenses"), ownerToken, """
                {"stageId":null,"incurredOn":"2026-09-10","amount":90000,"description":"Cordón cuneta"}""")));

        JsonNode updated = JSON.readTree(ok(edit(ownerToken, expense, """
                {"stageId":"%s","incurredOn":"2026-09-10","amount":90000,"description":"Cordón cuneta"}"""
                .formatted(commonStage))));

        assertThat(updated.get("stageId").asString()).isEqualTo(commonStage.toString());
        assertThat(updated.get("unitId").isNull()).isTrue();
    }

    @Test
    @DisplayName("el monto tiene que ser mayor que cero")
    void amountMustBePositive() throws Exception {
        UUID expense = houseExpense(houseOne, stageOf(houseOne, 0));

        edit(ownerToken, expense, """
                {"stageId":null,"incurredOn":"2026-09-10","amount":0}""").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("desde otra organización el gasto no existe")
    void foreignExpenseIsNotFound() throws Exception {
        UUID expense = houseExpense(houseOne, stageOf(houseOne, 0));
        Organization other = organizationRepository.save(new Organization("Constructora Intrusa"));
        String intruderToken = token(login(userIn("intruso", other, OrganizationRole.OWNER), PASSWORD));

        edit(intruderToken, expense, """
                {"stageId":null,"incurredOn":"2026-09-10","amount":1}""").andExpect(status().isNotFound());
        assertThat(expenseRepository.findById(expense).orElseThrow().getAmount()).isEqualByComparingTo("150000");
    }

    @Test
    @DisplayName("el encargado y el comprador no pueden editar gastos")
    void memberAndBuyerCannotEdit() throws Exception {
        UUID expense = houseExpense(houseOne, stageOf(houseOne, 0));
        String memberToken = token(login(userIn("encargado", organization, OrganizationRole.MEMBER), PASSWORD));
        String buyerEmail = userIn("comprador", null, null);
        ok(send(post("/api/units/" + houseOne + "/members"), ownerToken, """
                {"email":"%s"}""".formatted(buyerEmail)));
        String buyerToken = token(login(buyerEmail, PASSWORD));

        edit(memberToken, expense, """
                {"stageId":null,"incurredOn":"2026-09-10","amount":1}""").andExpect(status().isForbidden());
        edit(buyerToken, expense, """
                {"stageId":null,"incurredOn":"2026-09-10","amount":1}""").andExpect(status().isNotFound());
        assertThat(expenseRepository.findById(expense).orElseThrow().getAmount()).isEqualByComparingTo("150000");
    }
}
