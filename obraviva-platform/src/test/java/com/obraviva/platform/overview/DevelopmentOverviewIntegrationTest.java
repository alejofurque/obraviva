package com.obraviva.platform.overview;

import com.obraviva.platform.installation.DemoDataSeeder;
import com.obraviva.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Resumen del desarrollo y de cada casa, y estado comercial, sobre los barrios de
 * demostración y desde cada rol.
 *
 * <p>Los tests que cambian el estado comercial usan casas de Barrio El Nogal: el contexto
 * y la base se comparten entre métodos y los conteos se afirman sobre Los Aromos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false",
        "obraviva.dev-seed.enabled=true",
        "obraviva.demo-seed.enabled=true"
})
class DevelopmentOverviewIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DEMO_PASSWORD = "demo1234";

    @DynamicPropertySource
    static void splat(DynamicPropertyRegistry registry) throws IOException {
        Path file = Files.createTempFile("overview-splat-", ".spz");
        file.toFile().deleteOnExit();
        registry.add("obraviva.demo-seed.splat-path", file::toString);
    }

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void signedUrls() {
        // Con el almacenamiento simulado las URL firmadas serían nulas, y la portada del
        // desarrollo se reconoce por su URL.
        when(storageService.presignDownload(anyString())).thenReturn("http://storage.test/objeto");
        when(storageService.presignDownload(anyString(), any())).thenReturn("http://storage.test/objeto");
    }

    @Test
    @DisplayName("El dueño ve qué tiene el barrio, cómo va el gasto y la venta, y la puesta en marcha completa")
    void ownerSeesEverything() throws Exception {
        String owner = login("admin@obraviva.local", "admin123");
        JsonNode overview = json(ok(send(owner, get("/api/developments/" + aromos(owner) + "/overview"))));

        JsonNode content = overview.get("content");
        assertThat(content.get("stages").asString()).isEqualTo("75");
        assertThat(content.get("progressEntries").asString()).isEqualTo("22");
        assertThat(content.get("galleryPhotos").asString()).isEqualTo("12");
        assertThat(content.get("capturesAvailable").asString()).isEqualTo("9");
        assertThat(content.get("lastEntryDate").asString()).isEqualTo(DemoDataSeeder.demoDate("2026-09-08").toString());

        assertThat(overview.get("economic").get("stagesWithBudget").asString()).isEqualTo("3");
        assertThat(overview.get("commercial").get("units").asString()).isEqualTo("6");
        assertThat(overview.get("commercial").get("sold").asString()).isEqualTo("2");
        assertThat(overview.get("commercial").get("withPrice").asString()).isEqualTo("6");

        JsonNode setup = overview.get("setup");
        assertThat(setup.size()).isEqualTo(8);
        for (JsonNode step : setup) {
            assertThat(step.get("done").asString()).as(step.get("key").asString()).isEqualTo("true");
        }
    }

    @Test
    @DisplayName("El resumen de una casa cuenta sólo lo suyo, con su gasto y lo que le falta para mostrarse")
    void ownerSeesOneHouse() throws Exception {
        String owner = login("admin@obraviva.local", "admin123");
        JsonNode units = json(ok(send(owner, get("/api/developments/" + aromos(owner) + "/units"))));
        String casa1 = unit(units, "Casa 1").get("id").asString();

        JsonNode overview = json(ok(send(owner, get("/api/units/" + casa1 + "/overview"))));
        assertThat(overview.get("unit").get("code").asString()).isEqualTo("Casa 1");
        assertThat(overview.get("unit").get("listPrice").isNull()).isFalse();
        assertThat(overview.get("content").get("stages").asString()).isEqualTo("12");
        assertThat(overview.get("content").get("progressEntries").asString()).isEqualTo("11");
        assertThat(overview.get("content").get("galleryPhotos").asString()).isEqualTo("2");
        assertThat(overview.get("content").get("capturesAvailable").asString()).isEqualTo("7");
        assertThat(overview.get("economic").get("stagesWithBudget").asString()).isEqualTo("12");
        for (JsonNode step : overview.get("setup")) {
            assertThat(step.get("done").asString()).as(step.get("key").asString()).isEqualTo("true");
        }

        String casa6 = unit(units, "Casa 6").get("id").asString();
        JsonNode notStarted = json(ok(send(owner, get("/api/units/" + casa6 + "/overview"))));
        assertThat(step(notStarted.get("setup"), "FIRST_ENTRY").get("done").asString()).isEqualTo("false");
        assertThat(step(notStarted.get("setup"), "SHEET").get("done").asString()).isEqualTo("true");
    }

    @Test
    @DisplayName("El encargado ve el contenido, pero no gasto, venta, precio ni puesta en marcha")
    void supervisorSeesOnlyContent() throws Exception {
        String owner = login("admin@obraviva.local", "admin123");
        String supervisor = login("encargado@obraviva.local", DEMO_PASSWORD);

        JsonNode overview = json(ok(send(supervisor, get("/api/developments/" + aromos(owner) + "/overview"))));
        assertThat(overview.get("content").get("progressEntries").asString()).isEqualTo("22");
        assertThat(overview.get("economic").isNull()).isTrue();
        assertThat(overview.get("commercial").isNull()).isTrue();
        assertThat(overview.get("setup").isNull()).isTrue();

        String casa1 = unit(json(ok(send(owner, get("/api/developments/" + aromos(owner) + "/units")))), "Casa 1")
                .get("id").asString();
        JsonNode house = json(ok(send(supervisor, get("/api/units/" + casa1 + "/overview"))));
        assertThat(house.get("unit").get("listPrice").isNull()).isTrue();
        assertThat(house.get("unit").get("buyerCount").isNull()).isTrue();
        assertThat(house.get("economic").isNull()).isTrue();
        assertThat(house.get("setup").isNull()).isTrue();
    }

    @Test
    @DisplayName("Cada casa trae su tarjeta: etapa en curso, inicio previsto y lo último; alerta, encargado y sin ver, sólo el dueño")
    void houseCards() throws Exception {
        String owner = login("admin@obraviva.local", "admin123");
        String supervisor = login("encargado@obraviva.local", DEMO_PASSWORD);
        String id = aromos(owner);
        JsonNode units = json(ok(send(owner, get("/api/developments/" + id + "/units"))));

        JsonNode houses = json(ok(send(owner, get("/api/developments/" + id + "/overview")))).get("houses");
        assertThat(houses).hasSize(6);
        JsonNode casa1 = card(houses, unit(units, "Casa 1").get("id").asString());
        assertThat(casa1.get("currentStageName").isNull()).isFalse();
        assertThat(casa1.get("lastActivity").isNull()).isFalse();
        assertThat(casa1.get("hasSupervisor").asString()).isEqualTo("true");
        // La casa que no arrancó dice cuándo arranca y no tiene nada publicado.
        JsonNode casa6 = card(houses, unit(units, "Casa 6").get("id").asString());
        assertThat(casa6.get("plannedStart").isNull()).isFalse();
        assertThat(casa6.get("lastActivity").isNull()).isTrue();

        for (JsonNode card : json(ok(send(supervisor, get("/api/developments/" + id + "/overview")))).get("houses")) {
            assertThat(card.get("unseen").isNull()).isTrue();
            assertThat(card.get("hasSupervisor").isNull()).isTrue();
            assertThat(card.get("alertLight").isNull()).isTrue();
        }
    }

    private static JsonNode card(JsonNode houses, String unitId) {
        for (JsonNode card : houses) {
            if (card.get("unitId").asString().equals(unitId)) {
                return card;
            }
        }
        throw new AssertionError("Sin tarjeta para " + unitId);
    }

    @Test
    @DisplayName("Un comprador no llega al resumen del desarrollo ni al de su casa: 404")
    void buyerCannotReadOverview() throws Exception {
        String owner = login("admin@obraviva.local", "admin123");
        String buyer = login("familia.aromos@obraviva.local", DEMO_PASSWORD);
        String casa1 = unit(json(ok(send(owner, get("/api/developments/" + aromos(owner) + "/units")))), "Casa 1")
                .get("id").asString();

        send(buyer, get("/api/developments/" + aromos(owner) + "/overview")).andExpect(status().isNotFound());
        send(buyer, get("/api/units/" + casa1 + "/overview")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Las casas traen su estado comercial; compradores y precio, sólo para el dueño")
    void unitsCarrySalesStatus() throws Exception {
        String owner = login("admin@obraviva.local", "admin123");
        String supervisor = login("encargado@obraviva.local", DEMO_PASSWORD);
        String barrio = aromos(owner);

        JsonNode ownerUnits = json(ok(send(owner, get("/api/developments/" + barrio + "/units"))));
        assertThat(unit(ownerUnits, "Casa 1").get("salesStatus").asString()).isEqualTo("SOLD");
        assertThat(unit(ownerUnits, "Casa 1").get("buyerCount").asString()).isEqualTo("1");
        assertThat(unit(ownerUnits, "Casa 3").get("salesStatus").asString()).isEqualTo("RESERVED");

        JsonNode supervisorUnits = json(ok(send(supervisor, get("/api/developments/" + barrio + "/units"))));
        assertThat(unit(supervisorUnits, "Casa 1").get("buyerCount").isNull()).isTrue();
        assertThat(unit(supervisorUnits, "Casa 1").get("listPrice").isNull()).isTrue();
    }

    @Test
    @DisplayName("El dueño cambia el estado comercial con su día; el encargado recibe 403 y un estado inválido, 400")
    void ownerChangesSalesStatus() throws Exception {
        String owner = login("admin@obraviva.local", "admin123");
        String supervisor = login("encargado@obraviva.local", DEMO_PASSWORD);
        String unitId = unit(json(ok(send(owner, get("/api/developments/" + nogal(owner) + "/units")))), "Casa 3")
                .get("id").asString();

        JsonNode changed = json(ok(send(owner, put("/api/units/" + unitId + "/sales-status"), """
                {"status":"RESERVED"}""")));
        assertThat(changed.get("salesStatus").asString()).isEqualTo("RESERVED");
        // Sin fecha, la del día; con fecha, esa; nunca futura; disponible no tiene fecha.
        assertThat(changed.get("salesStatusOn").asString()).isEqualTo(java.time.LocalDate.now().toString());
        changed = json(ok(send(owner, put("/api/units/" + unitId + "/sales-status"), """
                {"status":"SOLD","on":"2026-03-12"}""")));
        assertThat(changed.get("salesStatusOn").asString()).isEqualTo("2026-03-12");
        send(owner, put("/api/units/" + unitId + "/sales-status"), """
                {"status":"SOLD","on":"2999-01-01"}""").andExpect(status().isBadRequest());
        changed = json(ok(send(owner, put("/api/units/" + unitId + "/sales-status"), """
                {"status":"AVAILABLE","on":"2026-03-12"}""")));
        assertThat(changed.get("salesStatusOn").isNull()).isTrue();

        send(supervisor, put("/api/units/" + unitId + "/sales-status"), """
                {"status":"SOLD"}""").andExpect(status().isForbidden());
        send(owner, put("/api/units/" + unitId + "/sales-status"), """
                {"status":"REGALADA"}""").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Vincular un comprador a una casa disponible la vende; a una reservada, la deja reservada")
    void linkingBuyerMarksAvailableUnitSold() throws Exception {
        String owner = login("admin@obraviva.local", "admin123");
        JsonNode units = json(ok(send(owner, get("/api/developments/" + nogal(owner) + "/units"))));
        String available = unit(units, "Casa 4").get("id").asString();
        String reserved = unit(units, "Casa 2").get("id").asString();

        ok(send(owner, post("/api/units/" + available + "/members"), """
                {"email":"nuevo-c4-%s@obraviva.test","fullName":"Comprador Casa 4"}""".formatted(UUID.randomUUID())));
        ok(send(owner, post("/api/units/" + reserved + "/members"), """
                {"email":"nuevo-c2-%s@obraviva.test","fullName":"Comprador Casa 2"}""".formatted(UUID.randomUUID())));

        JsonNode after = json(ok(send(owner, get("/api/developments/" + nogal(owner) + "/units"))));
        assertThat(unit(after, "Casa 4").get("salesStatus").asString()).isEqualTo("SOLD");
        assertThat(unit(after, "Casa 2").get("salesStatus").asString()).isEqualTo("RESERVED");
    }

    // --- Armado ---

    private String aromos(String token) throws Exception {
        return developmentId(token, "Barrio Los Aromos");
    }

    private String nogal(String token) throws Exception {
        return developmentId(token, "Barrio El Nogal");
    }

    private String developmentId(String token, String name) throws Exception {
        for (JsonNode development : json(ok(send(token, get("/api/developments"))))) {
            if (development.get("name").asString().equals(name)) {
                return development.get("id").asString();
            }
        }
        throw new AssertionError("No está el desarrollo " + name);
    }

    private static JsonNode unit(JsonNode units, String code) {
        for (JsonNode unit : units) {
            if (unit.get("code").asString().equals(code)) {
                return unit;
            }
        }
        throw new AssertionError("No está la casa " + code);
    }

    private static JsonNode step(JsonNode steps, String key) {
        for (JsonNode step : steps) {
            if (step.get("key").asString().equals(key)) {
                return step;
            }
        }
        throw new AssertionError("No está el paso " + key);
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

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }
}
