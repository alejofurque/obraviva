package com.obraviva.platform.developments;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
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
import java.util.ArrayList;
import java.util.List;
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
 * Encargados por casa y sectores del recorrido 3D. Un encargado asignado a casas
 * puntuales llega al desarrollo, pero ve y carga sólo lo de sus casas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class HouseSupervisorIntegrationTest {

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
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private String supervisorToken;
    private UUID supervisorId;
    private UUID developmentId;
    private UUID casa1;
    private UUID casa2;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/objeto");

        Organization organization = organizationRepository.save(new Organization("Casas del Sur"));
        ownerToken = login(createUser("duenio", organization, OrganizationRole.OWNER));
        String supervisorEmail = createUser("carlos", organization, OrganizationRole.MEMBER);
        supervisorId = userRepository.findByEmail(supervisorEmail).orElseThrow().getId();
        supervisorToken = login(supervisorEmail);

        developmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio del Sur"}""".formatted(organization.getId()))));
        JsonNode houses = json(ok(send(ownerToken, post("/api/developments/" + developmentId + "/units/batch"), """
                {"prefix":"Casa","firstNumber":1,"count":2,"withStandardStages":true}""")));
        casa1 = UUID.fromString(houses.get(0).get("id").asString());
        casa2 = UUID.fromString(houses.get(1).get("id").asString());
    }

    @Test
    @DisplayName("asignado a una casa, el encargado ve el desarrollo pero sólo su casa, y carga sólo ahí")
    void supervisorWorksOnlyOnAssignedHouse() throws Exception {
        JsonNode team = json(ok(send(ownerToken, post(teamUrl()), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(supervisorId, casa1))));
        assertThat(team.get("assigned").get(0).get("allHouses").asString()).isEqualTo("false");
        assertThat(team.get("assigned").get(0).get("unitIds").get(0).asString()).isEqualTo(casa1.toString());

        // El desarrollo le llega con sus casas y nada más: no carga en las obras comunes.
        JsonNode seen = json(ok(send(supervisorToken, get("/api/developments/" + developmentId))));
        assertThat(seen.get("restrictedToUnitIds")).hasSize(1);
        assertThat(seen.get("restrictedToUnitIds").get(0).asString()).isEqualTo(casa1.toString());
        assertThat(seen.get("unitCount").asString()).isEqualTo("1");
        assertThat(json(ok(send(ownerToken, get("/api/developments/" + developmentId)))).get("restrictedToUnitIds").isNull()).isTrue();
        JsonNode units = json(ok(send(supervisorToken, get("/api/developments/" + developmentId + "/units"))));
        assertThat(units).hasSize(1);
        assertThat(units.get(0).get("id").asString()).isEqualTo(casa1.toString());

        ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-09-10","description":"Obrador instalado","unitId":"%s"}""".formatted(casa1)));
        send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-09-10","description":"Otra casa","unitId":"%s"}""".formatted(casa2))
                .andExpect(status().isForbidden());
        send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-09-10","description":"Obras comunes"}""").andExpect(status().isForbidden());

        UUID ownStage = firstStage(ownerToken, casa1);
        UUID otherStage = firstStage(ownerToken, casa2);
        ok(send(supervisorToken, patch("/api/stages/" + ownStage + "/plan"), """
                {"progressPercent":30}"""));
        send(supervisorToken, patch("/api/stages/" + otherStage + "/plan"), """
                {"progressPercent":30}""").andExpect(status().isForbidden());
        send(supervisorToken, patch("/api/developments/stages/" + otherStage), """
                {"status":"IN_PROGRESS"}""").andExpect(status().isForbidden());
        send(supervisorToken, get("/api/units/" + casa2 + "/stages")).andExpect(status().isNotFound());
        send(supervisorToken, post("/api/developments/" + developmentId + "/captures/from-video"), """
                {"captureDate":"2026-09-10","unitId":"%s","zone":"Frente","videoFilename":"frente.mp4","videoContentType":"video/mp4"}"""
                .formatted(casa2)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("el dueño cambia sus casas: de una casa a todo el desarrollo, y lo quita")
    void ownerChangesScope() throws Exception {
        ok(send(ownerToken, post(teamUrl()), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(supervisorId, casa1)));
        send(ownerToken, post(teamUrl()), """
                {"userId":"%s"}""".formatted(supervisorId)).andExpect(status().isConflict());

        JsonNode whole = json(ok(send(ownerToken, put(teamUrl() + "/" + supervisorId), """
                {"unitIds":[]}""")));
        assertThat(whole.get("assigned").get(0).get("allHouses").asString()).isEqualTo("true");
        assertThat(json(ok(send(supervisorToken, get("/api/developments/" + developmentId + "/units"))))).hasSize(2);
        ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-09-10","description":"Cordón cuneta"}"""));

        ok(send(ownerToken, put(teamUrl() + "/" + supervisorId), """
                {"unitIds":["%s"]}""".formatted(casa2)));
        JsonNode onlyCasa2 = json(ok(send(supervisorToken, get("/api/developments/" + developmentId + "/units"))));
        assertThat(onlyCasa2).hasSize(1);
        assertThat(onlyCasa2.get(0).get("id").asString()).isEqualTo(casa2.toString());

        ok(send(ownerToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(teamUrl() + "/" + supervisorId)));
        send(supervisorToken, get("/api/developments/" + developmentId)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("la plantilla trae los sectores 3D; el dueño los cambia y el encargado los ve")
    void captureSectors() throws Exception {
        assertThat(json(ok(send(ownerToken, get("/api/units/" + casa1 + "/sectors")))))
                .extracting(node -> node.asString())
                .containsExactly("Frente", "Living y cocina", "Dormitorio principal", "Baño");

        JsonNode replaced = json(ok(send(ownerToken, put("/api/units/" + casa1 + "/sectors"), """
                {"names":["Frente"," Planta  baja ","","Garage"]}""")));
        assertThat(replaced).extracting(node -> node.asString()).containsExactly("Frente", "Planta baja", "Garage");

        send(ownerToken, put("/api/units/" + casa1 + "/sectors"), """
                {"names":["Baño","baño"]}""").andExpect(status().isBadRequest());

        ok(send(ownerToken, post(teamUrl()), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(supervisorId, casa1)));
        assertThat(json(ok(send(supervisorToken, get("/api/units/" + casa1 + "/sectors"))))).hasSize(3);
        send(supervisorToken, put("/api/units/" + casa1 + "/sectors"), """
                {"names":["Frente"]}""").andExpect(status().isForbidden());
        send(supervisorToken, get("/api/units/" + casa2 + "/sectors")).andExpect(status().isNotFound());

        JsonNode view = json(ok(send(ownerToken, get("/api/my-units/" + casa1)))).get("property");
        assertThat(view.get("sectors")).extracting(node -> node.asString()).containsExactly("Frente", "Planta baja", "Garage");
    }

    @Test
    @DisplayName("el trabajo de campo lista sólo las casas del encargado, con sus sectores y lo último cargado")
    void myWork() throws Exception {
        ok(send(ownerToken, post(teamUrl()), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(supervisorId, casa2)));
        ok(send(supervisorToken, post(progressUrl()), """
                {"entryDate":"2026-09-12","description":"Excavación terminada","unitId":"%s"}""".formatted(casa2)));
        ok(send(ownerToken, patch("/api/stages/" + firstStage(ownerToken, casa2) + "/plan"), """
                {"progressPercent":30}"""));

        JsonNode houses = json(ok(send(supervisorToken, get("/api/my-work"))));
        assertThat(houses).hasSize(1);
        JsonNode house = houses.get(0);
        assertThat(house.get("code").asString()).isEqualTo("Casa 2");
        assertThat(house.get("developmentName").asString()).isEqualTo("Barrio del Sur");
        assertThat(house.get("sectors")).hasSize(4);
        assertThat(house.get("lastEntryText").asString()).isEqualTo("Excavación terminada");
        assertThat(house.get("capturedSectors").asString()).isEqualTo("0");
        // La tarjeta de Hoy dice la etapa en curso y su avance: la misma etapa, no otra.
        JsonNode first = json(ok(send(ownerToken, get("/api/units/" + casa2 + "/stages")))).get(0);
        assertThat(house.get("currentStageName").asString()).isEqualTo(first.get("name").asString());
        assertThat(house.get("currentStagePercent").asString()).isEqualTo("30");

        assertThat(json(ok(send(ownerToken, get("/api/my-work"))))).hasSize(2);
    }

    // --- Armado ---

    private UUID firstStage(String token, UUID unitId) throws Exception {
        return UUID.fromString(json(ok(send(token, get("/api/units/" + unitId + "/stages")))).get(0).get("id").asString());
    }

    private String teamUrl() {
        return "/api/developments/" + developmentId + "/team";
    }

    private String progressUrl() {
        return "/api/developments/" + developmentId + "/progress";
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

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }
}
