package com.obraviva.platform.developments;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.ExpenseDtos.CreateExpenseRequest;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class DashboardIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ExpenseRepository expenseRepository;

    private String token;
    private UUID developmentId;
    private UUID excavationId;
    private UUID structureId;

    @BeforeEach
    void setUp() throws Exception {
        expenseRepository.deleteAll();

        String email = "panel-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Desarrollista"));
        token = login(email);

        UUID organizationId = UUID.fromString(JSON.readTree(
                perform(post("/api/organizations"), new CreateOrganizationRequest("Constructora Panel")))
                .get("id").asString());

        developmentId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments"),
                        new CreateDevelopmentRequest(organizationId, "Torre Panel", null)))
                .get("id").asString());

        excavationId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments/" + developmentId + "/stages"),
                        new CreateStageRequest("Excavacion", 0))).get("id").asString());

        structureId = UUID.fromString(JSON.readTree(
                perform(post("/api/developments/" + developmentId + "/stages"),
                        new CreateStageRequest("Estructura", 1))).get("id").asString());
    }

    private String perform(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return mockMvc.perform(builder
                        .header("Authorization", "Bearer " + token)
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

    private void plan(UUID stageId, int progress, int weight, String budget) throws Exception {
        mockMvc.perform(patch("/api/stages/" + stageId + "/plan")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new UpdateStagePlanRequest(
                                progress, null, weight, new BigDecimal(budget), false))))
                .andExpect(status().isOk());
    }

    private void spend(UUID stageId, String amount, LocalDate on) throws Exception {
        perform(post("/api/developments/" + developmentId + "/expenses"),
                new CreateExpenseRequest(stageId, on, new BigDecimal(amount), "Gasto de prueba"));
    }

    @Test
    @DisplayName("el avance global pondera por el peso de cada etapa")
    void overallProgressIsWeighted() throws Exception {
        plan(excavationId, 100, 2, "8000000.00");
        plan(structureId, 25, 8, "32000000.00");

        // (100*2 + 25*8) / 10 = 40
        mockMvc.perform(get("/api/developments/" + developmentId + "/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallProgressPercent").value(40.0))
                .andExpect(jsonPath("$.totalBudget").value(40000000.00));
    }

    @Test
    @DisplayName("gastar al ritmo del avance mantiene el semáforo en verde")
    void spendingInLineWithProgressIsGreen() throws Exception {
        plan(structureId, 50, 1, "10000000.00");
        spend(structureId, "5000000.00", LocalDate.of(2026, 8, 10));

        mockMvc.perform(get("/api/developments/" + developmentId + "/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[1].budgetUsedPercent").value(50.0))
                .andExpect(jsonPath("$.stages[1].trafficLight").value("GREEN"));
    }

    @Test
    @DisplayName("gastar mucho más de lo avanzado enciende el rojo")
    void spendingAheadOfProgressIsRed() throws Exception {
        plan(structureId, 20, 1, "10000000.00");
        spend(structureId, "8000000.00", LocalDate.of(2026, 8, 10));

        // 80% consumido contra 20% avanzado: 60 puntos de desvío.
        mockMvc.perform(get("/api/developments/" + developmentId + "/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[1].trafficLight").value("RED"));
    }

    @Test
    @DisplayName("una etapa sin presupuesto no tiene semáforo, no es verde")
    void stageWithoutBudgetHasNoTrafficLight() throws Exception {
        spend(excavationId, "1000000.00", LocalDate.of(2026, 8, 10));

        mockMvc.perform(get("/api/developments/" + developmentId + "/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[0].trafficLight").value("NO_BUDGET"))
                .andExpect(jsonPath("$.stages[0].budgetUsedPercent").doesNotExist())
                .andExpect(jsonPath("$.stages[0].spentAmount").value(1000000.00));
    }

    @Test
    @DisplayName("los gastos sin etapa entran en el total pero no en ningún semáforo")
    void unassignedExpensesCountInTheTotal() throws Exception {
        plan(structureId, 50, 1, "10000000.00");
        spend(structureId, "5000000.00", LocalDate.of(2026, 8, 10));
        spend(null, "2000000.00", LocalDate.of(2026, 8, 15));

        mockMvc.perform(get("/api/developments/" + developmentId + "/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpent").value(7000000.00))
                .andExpect(jsonPath("$.unassignedSpent").value(2000000.00))
                // El semáforo de la etapa no se ve afectado por el gasto general.
                .andExpect(jsonPath("$.stages[1].spentAmount").value(5000000.00));
    }

    @Test
    @DisplayName("la curva prevista sale del presupuesto y las fechas de cada etapa; la que no tiene fechas se cuenta aparte")
    void plannedCurveFromBudgetAndDates() throws Exception {
        plan(excavationId, 0, 1, "3100.00");
        mockMvc.perform(patch("/api/stages/" + excavationId + "/plan")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"plannedStart":"2026-01-15","plannedEnd":"2026-02-14"}"""))
                .andExpect(status().isOk());
        plan(structureId, 0, 1, "500.00");

        mockMvc.perform(get("/api/developments/" + developmentId + "/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedCurve.length()").value(2))
                .andExpect(jsonPath("$.plannedCurve[0].monthlyAmount").value(1700.00))
                .andExpect(jsonPath("$.plannedCurve[1].accumulated").value(3100.00))
                .andExpect(jsonPath("$.undatedStages").value(1))
                .andExpect(jsonPath("$.undatedBudget").value(500.00));
    }

    @Test
    @DisplayName("la curva de gasto acumula por mes")
    void spendingCurveAccumulatesByMonth() throws Exception {
        spend(null, "1000000.00", LocalDate.of(2026, 7, 5));
        spend(null, "500000.00", LocalDate.of(2026, 7, 20));
        spend(null, "2000000.00", LocalDate.of(2026, 8, 3));

        mockMvc.perform(get("/api/developments/" + developmentId + "/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spendingCurve.length()").value(2))
                .andExpect(jsonPath("$.spendingCurve[0].month").value("2026-07"))
                .andExpect(jsonPath("$.spendingCurve[0].accumulated").value(1500000.00))
                .andExpect(jsonPath("$.spendingCurve[1].accumulated").value(3500000.00))
                .andExpect(jsonPath("$.lastExpenseOn").value("2026-08-03"));
    }

    @Test
    @DisplayName("no se imputa un gasto a la etapa de otra obra")
    void expenseCannotUseForeignStage() throws Exception {
        UUID otherDevelopment = UUID.fromString(JSON.readTree(
                perform(post("/api/developments"),
                        new CreateDevelopmentRequest(
                                UUID.fromString(JSON.readTree(
                                        perform(post("/api/organizations"),
                                                new CreateOrganizationRequest("Otra")))
                                        .get("id").asString()),
                                "Otra Torre", null)))
                .get("id").asString());

        mockMvc.perform(post("/api/developments/" + otherDevelopment + "/expenses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new CreateExpenseRequest(
                                structureId, LocalDate.of(2026, 8, 10),
                                new BigDecimal("1000.00"), "Cruzado"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("una casa en rojo cuenta en las alertas del desarrollo junto con las comunes, y su fila dice qué etapa")
    void houseInRedCountsInDevelopmentAlerts() throws Exception {
        String houses = mockMvc.perform(post("/api/developments/" + developmentId + "/units/batch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prefix":"Casa","firstNumber":1,"count":2,"withStandardStages":true}"""))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        UUID casa1 = UUID.fromString(JSON.readTree(houses).get(0).get("id").asString());
        UUID casaEstructura = stageNamed(casa1, "Estructura");
        UUID casaPreliminares = stageNamed(casa1, "Trabajos preliminares");

        // Casa 1: Estructura gastó el 80% y avanzó el 20% (rojo); Trabajos preliminares, en línea.
        plan(casaEstructura, 20, 15, "10000000.00");
        perform(post("/api/developments/" + developmentId + "/expenses"), new CreateExpenseRequest(
                casaEstructura, LocalDate.of(2026, 8, 10), new BigDecimal("8000000.00"), "Hormigón", casa1));
        plan(casaPreliminares, 100, 3, "1000000.00");
        perform(post("/api/developments/" + developmentId + "/expenses"), new CreateExpenseRequest(
                casaPreliminares, LocalDate.of(2026, 7, 10), new BigDecimal("1000000.00"), "Obrador", casa1));
        // Obras comunes: 60% gastado contra 40% avanzado, 20 puntos de desvío (amarillo).
        plan(structureId, 40, 1, "1000000.00");
        spend(structureId, "600000.00", LocalDate.of(2026, 8, 1));
        // Un gasto general del desarrollo: ni de una casa ni de las obras comunes.
        spend(null, "250.00", LocalDate.of(2026, 8, 2));

        mockMvc.perform(get("/api/developments/" + developmentId + "/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(2))
                .andExpect(jsonPath("$.alerts[0].name").value("Estructura"))
                .andExpect(jsonPath("$.alerts[0].unitCode").value("Casa 1"))
                .andExpect(jsonPath("$.alerts[0].trafficLight").value("RED"))
                // Para "Gastó 80% del presupuesto con 20% de avance".
                .andExpect(jsonPath("$.alerts[0].budgetUsedPercent").value(80))
                .andExpect(jsonPath("$.alerts[0].progressPercent").value(20))
                .andExpect(jsonPath("$.alerts[1].unitCode").doesNotExist())
                .andExpect(jsonPath("$.alerts[1].trafficLight").value("YELLOW"))
                .andExpect(jsonPath("$.units[0].code").value("Casa 1"))
                .andExpect(jsonPath("$.units[0].worstStageLight").value("RED"))
                .andExpect(jsonPath("$.units[0].worstStageName").value("Estructura"))
                .andExpect(jsonPath("$.units[1].worstStageLight").value("NO_BUDGET"))
                .andExpect(jsonPath("$.units[1].worstStageName").doesNotExist())
                .andExpect(jsonPath("$.units[0].stagesWithoutBudget").value(10))
                .andExpect(jsonPath("$.units[0].stageCount").value(12))
                // Las obras comunes como una fila más, sin el gasto general.
                .andExpect(jsonPath("$.commonWorks.unitId").doesNotExist())
                .andExpect(jsonPath("$.commonWorks.spentAmount").value(600000.00))
                .andExpect(jsonPath("$.commonWorks.progressPercent").value(20))
                .andExpect(jsonPath("$.commonWorks.stagesWithoutBudget").value(1))
                .andExpect(jsonPath("$.commonWorks.stageCount").value(2))
                .andExpect(jsonPath("$.generalSpent").value(250.00))
                // Las etapas para elegir: las de cada casa en orden y las comunes al final.
                .andExpect(jsonPath("$.stageOptions.length()").value(26))
                .andExpect(jsonPath("$.stageOptions[0].unitId").value(casa1.toString()))
                .andExpect(jsonPath("$.stageOptions[0].name").value("Trabajos preliminares"))
                .andExpect(jsonPath("$.stageOptions[25].unitId").doesNotExist());

        mockMvc.perform(get("/api/developments/" + developmentId + "/dashboard")
                        .param("unitId", casa1.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].trafficLight").value("RED"))
                .andExpect(jsonPath("$.commonWorks").doesNotExist())
                .andExpect(jsonPath("$.generalSpent").value(0))
                .andExpect(jsonPath("$.stageOptions.length()").value(12));
    }

    private UUID stageNamed(UUID unitId, String name) throws Exception {
        String stages = mockMvc.perform(get("/api/units/" + unitId + "/stages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (var stage : JSON.readTree(stages)) {
            if (stage.get("name").asString().equals(name)) {
                return UUID.fromString(stage.get("id").asString());
            }
        }
        throw new AssertionError("La casa no tiene la etapa " + name);
    }

    @Test
    @DisplayName("el panel de otra organización no existe")
    void dashboardOfForeignDevelopmentIsNotFound() throws Exception {
        String intruderEmail = "ajeno-" + UUID.randomUUID() + "@obraviva.test";
        userRepository.save(new User(intruderEmail, passwordEncoder.encode(PASSWORD), "Ajeno"));

        mockMvc.perform(get("/api/developments/" + developmentId + "/dashboard")
                        .header("Authorization", "Bearer " + login(intruderEmail)))
                .andExpect(status().isNotFound());
    }
}