package com.obraviva.platform.developments;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.storage.StorageService;
import com.obraviva.platform.support.SupervisorAssignments;
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
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cada casa es una obra propia: sus etapas, su historia, sus gastos y su ficha de venta,
 * dentro de un desarrollo que además tiene obras comunes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class HouseModelIntegrationTest {

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
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private String memberToken;
    private UUID developmentId;
    private UUID otherDevelopmentId;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.exists(anyString())).thenReturn(true);

        Organization organization = organizationRepository.save(new Organization("Desarrollos Casas"));
        ownerToken = login(createUser("duenio", organization, OrganizationRole.OWNER));
        memberToken = login(createUser("encargado", organization, OrganizationRole.MEMBER));
        developmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio Los Aromos"}""".formatted(organization.getId()))));
        otherDevelopmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio El Nogal"}""".formatted(organization.getId()))));
        SupervisorAssignments.assignAllSupervisors(
                memberRepository, assignmentRepository, organization.getId(), developmentId, otherDevelopmentId);
    }

    @Test
    @DisplayName("un desarrollo de diez casas se carga en un paso, cada una con las doce etapas estándar")
    void batchCreatesHousesWithStandardStages() throws Exception {
        JsonNode houses = json(ok(send(ownerToken, post(unitsUrl() + "/batch"), """
                {"prefix":"Casa","firstNumber":1,"count":10,"withStandardStages":true}""")));

        assertThat(houses).hasSize(10);
        assertThat(houses.get(0).get("code").asString()).isEqualTo("Casa 1");
        assertThat(houses.get(9).get("code").asString()).isEqualTo("Casa 10");
        assertThat(houses.get(0).get("stageCount").asString()).isEqualTo("12");

        String casa1 = houses.get(0).get("id").asString();
        JsonNode stages = json(ok(send(ownerToken, get("/api/units/" + casa1 + "/stages"))));
        assertThat(stages).hasSize(12);
        assertThat(stages.get(0).get("name").asString()).isEqualTo("Trabajos preliminares");
        assertThat(stages.get(0).get("unitId").asString()).isEqualTo(casa1);
        assertThat(stages.get(0).get("description").asString()).isNotBlank();
        int weights = 0;
        for (JsonNode stage : stages) {
            weights += Integer.parseInt(stage.get("weight").asString());
        }
        assertThat(weights).isEqualTo(100);

        // La plantilla no se aplica encima de etapas existentes, y los nombres no se repiten.
        send(ownerToken, post("/api/units/" + casa1 + "/stages/standard")).andExpect(status().isConflict());
        send(ownerToken, post(unitsUrl() + "/batch"), """
                {"prefix":"Casa","firstNumber":10,"count":2,"withStandardStages":false}""").andExpect(status().isConflict());
        assertThat(json(ok(send(ownerToken, get(unitsUrl()))))).hasSize(10);

        // Las etapas de las casas no se mezclan con las obras comunes del desarrollo.
        assertThat(json(ok(send(ownerToken, get("/api/developments/" + developmentId + "/stages"))))).isEmpty();
        send(memberToken, post(unitsUrl() + "/batch"), """
                {"prefix":"Lote","firstNumber":1,"count":1}""").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("una etapa tiene descripción, fechas previstas y las reales de inicio y fin")
    void stageCarriesDetailsAndDates() throws Exception {
        UUID stage = firstStageOf(house("Casa 1"));

        JsonNode planned = json(ok(send(ownerToken, patch("/api/stages/" + stage + "/plan"), """
                {"description":"Cerco y obrador","plannedStart":"2026-03-01","plannedEnd":"2026-03-10"}""")));
        assertThat(planned.get("description").asString()).isEqualTo("Cerco y obrador");
        assertThat(planned.get("plannedEnd").asString()).isEqualTo("2026-03-10");

        send(ownerToken, patch("/api/stages/" + stage + "/plan"), """
                {"plannedStart":"2026-03-10","plannedEnd":"2026-03-01"}""").andExpect(status().isBadRequest());
        send(memberToken, patch("/api/stages/" + stage + "/plan"), """
                {"plannedEnd":"2026-04-01"}""").andExpect(status().isForbidden());

        // El encargado declara avance y estado, con la fecha en que pasó.
        ok(send(memberToken, patch("/api/stages/" + stage + "/plan"), """
                {"progressPercent":60}"""));
        JsonNode started = json(ok(send(memberToken, patch("/api/developments/stages/" + stage), """
                {"status":"IN_PROGRESS","effectiveOn":"2026-03-02"}""")));
        assertThat(started.get("startedOn").asString()).isEqualTo("2026-03-02");
        assertThat(started.get("completedOn").isNull()).isTrue();

        JsonNode completed = json(ok(send(memberToken, patch("/api/developments/stages/" + stage), """
                {"status":"COMPLETED","effectiveOn":"2026-03-12"}""")));
        assertThat(completed.get("completedOn").asString()).isEqualTo("2026-03-12");
        assertThat(completed.get("startedOn").asString()).isEqualTo("2026-03-02");
        assertThat(completed.get("progressPercent").asString()).isEqualTo("100");

        send(memberToken, patch("/api/developments/stages/" + stage), """
                {"status":"COMPLETED","effectiveOn":"%s"}""".formatted(LocalDate.now().plusDays(3)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("un avance es de una casa y sus etapas; ni la etapa de otra casa ni una casa de otro desarrollo")
    void progressIsScopedToItsHouse() throws Exception {
        UUID casa1 = house("Casa 1");
        UUID casa2 = house("Casa 2");
        UUID foreign = UUID.fromString(json(ok(send(ownerToken, post("/api/developments/" + otherDevelopmentId + "/units/batch"), """
                {"prefix":"Casa","firstNumber":1,"count":1,"withStandardStages":true}"""))).get(0).get("id").asString());
        UUID commonStage = idOf(ok(send(ownerToken, post("/api/developments/" + developmentId + "/stages"), """
                {"name":"Calles y cordón cuneta","orderIndex":1}""")));

        JsonNode entry = json(ok(send(memberToken, post(progressUrl()), """
                {"stageId":"%s","entryDate":"2026-03-05","description":"Obrador instalado","unitId":"%s"}"""
                .formatted(firstStageOf(casa1), casa1))));
        assertThat(entry.get("unitId").asString()).isEqualTo(casa1.toString());

        send(memberToken, post(progressUrl()), """
                {"stageId":"%s","entryDate":"2026-03-05","description":"Etapa ajena","unitId":"%s"}"""
                .formatted(firstStageOf(casa2), casa1)).andExpect(status().isBadRequest());
        send(memberToken, post(progressUrl()), """
                {"stageId":"%s","entryDate":"2026-03-05","description":"Común en una casa","unitId":"%s"}"""
                .formatted(commonStage, casa1)).andExpect(status().isBadRequest());
        send(memberToken, post(progressUrl()), """
                {"entryDate":"2026-03-05","description":"Casa de otro barrio","unitId":"%s"}"""
                .formatted(foreign)).andExpect(status().isBadRequest());
        ok(send(memberToken, post(progressUrl()), """
                {"stageId":"%s","entryDate":"2026-03-06","description":"Cordón cuneta terminado"}""".formatted(commonStage)));
        ok(send(memberToken, post(progressUrl()), """
                {"entryDate":"2026-03-07","description":"Avance de la otra casa","unitId":"%s"}""".formatted(casa2)));

        // La galería de una casa no acepta una casa de otro desarrollo.
        send(ownerToken, post("/api/developments/" + developmentId + "/media/upload-url"), """
                {"filename":"fachada.jpg","contentType":"image/jpeg","unitId":"%s"}""".formatted(foreign))
                .andExpect(status().isBadRequest());

        // El comprador de la Casa 1 ve lo suyo y lo común, nunca la otra casa.
        JsonNode view = json(ok(send(ownerToken, get("/api/my-units/" + casa1)))).get("property");
        assertThat(view.get("stages")).hasSize(12);
        assertThat(view.get("timeline")).hasSize(2);
        int common = 0;
        for (JsonNode item : view.get("timeline")) {
            assertThat(item.get("description").asString()).isNotEqualTo("Avance de la otra casa");
            common += item.get("common").asString().equals("true") ? 1 : 0;
        }
        assertThat(common).isEqualTo(1);
    }

    @Test
    @DisplayName("el gasto va con su casa, y el panel muestra cada casa y el total del desarrollo")
    void expensesAndDashboardPerHouse() throws Exception {
        UUID casa1 = house("Casa 1");
        UUID casa2 = house("Casa 2");
        UUID stage = firstStageOf(casa1);
        ok(send(ownerToken, patch("/api/stages/" + stage + "/plan"), """
                {"budgetAmount":1000000}"""));

        ok(send(ownerToken, post(expensesUrl()), """
                {"stageId":"%s","incurredOn":"2026-03-05","amount":400000,"description":"Cerco","unitId":"%s"}"""
                .formatted(stage, casa1)));
        send(ownerToken, post(expensesUrl()), """
                {"stageId":"%s","incurredOn":"2026-03-05","amount":1000,"description":"Mal cargado","unitId":"%s"}"""
                .formatted(stage, casa2)).andExpect(status().isBadRequest());
        ok(send(ownerToken, post(expensesUrl()), """
                {"incurredOn":"2026-03-06","amount":250000,"description":"Seguro de obra"}"""));

        JsonNode house = json(ok(send(ownerToken, get("/api/developments/" + developmentId + "/dashboard?unitId=" + casa1))));
        assertThat(house.get("unitId").asString()).isEqualTo(casa1.toString());
        assertThat(new java.math.BigDecimal(house.get("totalSpent").asString())).isEqualByComparingTo("400000");
        assertThat(house.get("stages")).hasSize(12);

        JsonNode barrio = json(ok(send(ownerToken, get("/api/developments/" + developmentId + "/dashboard"))));
        assertThat(new java.math.BigDecimal(barrio.get("totalSpent").asString())).isEqualByComparingTo("650000");
        assertThat(barrio.get("units")).hasSize(2);

        send(ownerToken, get("/api/developments/" + otherDevelopmentId + "/dashboard?unitId=" + casa1))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("la ficha de venta tiene todo para mostrar la casa; el precio queda interno")
    void salesSheetKeepsPriceInternal() throws Exception {
        UUID casa1 = house("Casa 1");
        JsonNode sheet = json(ok(send(ownerToken, put("/api/units/" + casa1 + "/sales-sheet"), """
                {"modelName":"Modelo Aromo","lotAreaM2":600,"coveredAreaM2":186,"semiCoveredAreaM2":32,
                 "rooms":5,"toilettes":1,"floors":2,"parkingSpaces":2,"orientation":"Norte",
                 "longDescription":"Casa de dos plantas con galería.","finishes":"Porcelanato y DVH",
                 "listPrice":189000,"priceCurrency":"USD"}""")));
        assertThat(new java.math.BigDecimal(sheet.get("listPrice").asString())).isEqualByComparingTo("189000");

        send(ownerToken, put("/api/units/" + casa1 + "/sales-sheet"), """
                {"priceCurrency":"USD"}""").andExpect(status().isBadRequest());
        send(ownerToken, put("/api/units/" + casa1 + "/sales-sheet"), """
                {"listPrice":1,"priceCurrency":"EUR"}""").andExpect(status().isBadRequest());
        send(memberToken, put("/api/units/" + casa1 + "/sales-sheet"), """
                {"modelName":"Otro"}""").andExpect(status().isForbidden());

        JsonNode memberView = unitOf(json(ok(send(memberToken, get(unitsUrl())))), casa1);
        assertThat(memberView.get("modelName").asString()).isEqualTo("Modelo Aromo");
        assertThat(memberView.get("listPrice").isNull()).isTrue();

        String buyerView = ok(send(ownerToken, get("/api/my-units/" + casa1)));
        JsonNode details = JSON.readTree(buyerView).get("property").get("details");
        assertThat(details.get("modelName").asString()).isEqualTo("Modelo Aromo");
        assertThat(new java.math.BigDecimal(details.get("semiCoveredAreaM2").asString())).isEqualByComparingTo("32");
        assertThat(buyerView).doesNotContain("189000").doesNotContain("listPrice");

        JsonNode renamed = json(ok(send(ownerToken, put("/api/units/" + casa1), """
                {"code":"Lote 14","description":"Frente al espacio verde"}""")));
        assertThat(renamed.get("code").asString()).isEqualTo("Lote 14");
    }

    @Test
    @DisplayName("el desarrollo guarda sus datos y su portada; el encargado no los edita")
    void developmentDetailsAndCover() throws Exception {
        JsonNode updated = json(ok(send(ownerToken, put("/api/developments/" + developmentId), """
                {"name":"Barrio Los Aromos","address":"Camino a La Calera km 4,5","description":"Barrio cerrado",
                 "locality":"Villa Allende","province":"Córdoba","developmentType":"BARRIO_CERRADO",
                 "landAreaM2":42000,"startedOn":"2026-01-12","estimatedDelivery":"2027-06",
                 "amenities":["Pileta"," pileta ","SUM",""],"services":["Gas natural"],
                 "architect":"Estudio","builder":"Constructora","permitNumber":"EXP-1"}""")));
        assertThat(updated.get("developmentType").asString()).isEqualTo("BARRIO_CERRADO");
        assertThat(updated.get("estimatedDelivery").asString()).isEqualTo("2027-06");
        assertThat(updated.get("amenities")).hasSize(2);

        send(memberToken, put("/api/developments/" + developmentId), """
                {"name":"Otro nombre"}""").andExpect(status().isForbidden());
        send(ownerToken, put("/api/developments/" + developmentId), """
                {"developmentType":"CASTILLO","name":"X"}""").andExpect(status().isBadRequest());

        send(ownerToken, post("/api/developments/" + developmentId + "/cover/upload-url"), """
                {"filename":"portada.gif","contentType":"image/gif"}""").andExpect(status().isUnsupportedMediaType());
        ok(send(ownerToken, post("/api/developments/" + developmentId + "/cover/upload-url"), """
                {"filename":"portada.jpg","contentType":"image/jpeg"}"""));
        JsonNode withCover = json(ok(send(ownerToken, post("/api/developments/" + developmentId + "/cover/confirm"))));
        assertThat(withCover.get("coverUrl").asString()).startsWith("https://almacenamiento.test");

        String casa1 = house("Casa 1").toString();
        JsonNode view = json(ok(send(ownerToken, get("/api/my-units/" + casa1)))).get("property").get("development");
        assertThat(view.get("locality").asString()).isEqualTo("Villa Allende");
        assertThat(view.get("coverUrl").asString()).startsWith("https://almacenamiento.test");
    }

    // --- Armado ---

    /** Crea las casas la primera vez que se piden, con plantilla. */
    private UUID house(String code) throws Exception {
        JsonNode units = json(ok(send(ownerToken, get(unitsUrl()))));
        if (units.isEmpty()) {
            units = json(ok(send(ownerToken, post(unitsUrl() + "/batch"), """
                    {"prefix":"Casa","firstNumber":1,"count":2,"withStandardStages":true}""")));
        }
        for (JsonNode unit : units) {
            if (unit.get("code").asString().equals(code)) {
                return UUID.fromString(unit.get("id").asString());
            }
        }
        throw new AssertionError("No está la casa " + code);
    }

    private UUID firstStageOf(UUID unitId) throws Exception {
        return UUID.fromString(json(ok(send(ownerToken, get("/api/units/" + unitId + "/stages")))).get(0).get("id").asString());
    }

    private static JsonNode unitOf(JsonNode units, UUID unitId) {
        for (JsonNode unit : units) {
            if (unit.get("id").asString().equals(unitId.toString())) {
                return unit;
            }
        }
        throw new AssertionError("No está la casa " + unitId);
    }

    private String unitsUrl() {
        return "/api/developments/" + developmentId + "/units";
    }

    private String progressUrl() {
        return "/api/developments/" + developmentId + "/progress";
    }

    private String expensesUrl() {
        return "/api/developments/" + developmentId + "/expenses";
    }

    private String createUser(String prefix, Organization organization, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        memberRepository.save(new OrganizationMember(organization, user.getId(), role));
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

    private static UUID idOf(String body) {
        return UUID.fromString(JSON.readTree(body).get("id").asString());
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }
}
