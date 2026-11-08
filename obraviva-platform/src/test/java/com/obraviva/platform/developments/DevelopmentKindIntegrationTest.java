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
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tipo de desarrollo.
 *
 * <p>Un desarrollo es una construcción con su propio avance, gasto e historia. Lo normal
 * es la obra única, una casa o un galpón: tiene una sola unidad, que se crea con la obra
 * y no se administra aparte. Un desarrollo con unidades es un edificio: varias personas
 * compran partes de la misma obra y cada una ve sus recorridos más los de áreas comunes.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class DevelopmentKindIntegrationTest {

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

    private String token;
    private UUID organizationId;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/firmada");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/firmada");
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.size(anyString())).thenReturn(com.obraviva.platform.support.SpzFixtures.TYPICAL_SIZE);
        when(storageService.readPrefix(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(com.obraviva.platform.support.SpzFixtures.valid());

        Organization organization = organizationRepository.save(new Organization("Constructora Tipos"));
        organizationId = organization.getId();
        String email = "tipos-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario Tipos"));
        memberRepository.save(new OrganizationMember(organization, user.getId(), OrganizationRole.OWNER));
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JSON.readTree(response).get("accessToken").asString();
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String ok(MockHttpServletRequestBuilder builder, String body) throws Exception {
        return send(builder, body).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private UUID development(String name, String kind) throws Exception {
        String body = kind == null
                ? """
                  {"organizationId":"%s","name":"%s"}""".formatted(organizationId, name)
                : """
                  {"organizationId":"%s","name":"%s","kind":"%s"}""".formatted(organizationId, name, kind);
        return UUID.fromString(JSON.readTree(ok(post("/api/developments"), body)).get("id").asString());
    }

    private UUID firstUnit(UUID developmentId) throws Exception {
        String units = mockMvc.perform(get("/api/developments/" + developmentId + "/units")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(units).get(0).get("id").asString());
    }

    private void capture(UUID developmentId, UUID unitId, String zone) throws Exception {
        String unit = unitId == null ? "" : ",\"unitId\":\"" + unitId + "\"";
        if (unitId != null) {
            // Desde la regla de sectores, el recorrido de una casa va en uno de los suyos.
            mockMvc.perform(put("/api/units/" + unitId + "/sectors")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"names":["%s"]}""".formatted(zone)))
                    .andExpect(status().isOk());
        }
        String created = ok(post("/api/developments/" + developmentId + "/captures"), """
                {"captureDate":"2026-09-10","zone":"%s"%s}""".formatted(zone, unit));
        UUID captureId = UUID.fromString(JSON.readTree(created).get("captureId").asString());
        ok(post("/api/captures/" + captureId + "/confirm"), "{}");
    }

    @Test
    @DisplayName("una obra única nace con su unidad y no admite otra")
    void singleDevelopmentHasExactlyOneUnit() throws Exception {
        String created = ok(post("/api/developments"), """
                {"organizationId":"%s","name":"Casa Los Aromos","kind":"SINGLE"}""".formatted(organizationId));
        UUID developmentId = UUID.fromString(JSON.readTree(created).get("id").asString());
        org.assertj.core.api.Assertions.assertThat(JSON.readTree(created).get("kind").asString()).isEqualTo("SINGLE");

        mockMvc.perform(get("/api/developments/" + developmentId + "/units").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1));

        send(post("/api/developments/" + developmentId + "/units"), """
                {"code":"Otra"}""").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("sin tipo, el desarrollo es con unidades y arranca vacío")
    void kindDefaultsToMultiUnit() throws Exception {
        UUID developmentId = development("Edificio Nogal", null);

        mockMvc.perform(get("/api/developments/" + developmentId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.kind").value("MULTI_UNIT"));
        mockMvc.perform(get("/api/developments/" + developmentId + "/units").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("en un edificio cada unidad ve sus recorridos y los de áreas comunes, no los de otra unidad")
    void multiUnitToursAreSeparated() throws Exception {
        UUID developmentId = development("Edificio Nogal", "MULTI_UNIT");
        UUID unitA = UUID.fromString(JSON.readTree(ok(post("/api/developments/" + developmentId + "/units"), """
                {"code":"2B"}""")).get("id").asString());
        UUID unitB = UUID.fromString(JSON.readTree(ok(post("/api/developments/" + developmentId + "/units"), """
                {"code":"3A"}""")).get("id").asString());

        capture(developmentId, unitA, "Living 2B");
        capture(developmentId, unitB, "Living 3A");
        capture(developmentId, null, "Hall de entrada");

        mockMvc.perform(get("/api/my-units/" + unitA).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.property.developmentKind").value("MULTI_UNIT"))
                .andExpect(jsonPath("$.property.captures.length()").value(2))
                .andExpect(jsonPath("$.property.captures[?(@.zone == 'Living 2B')].common").value(org.hamcrest.Matchers.contains(false)))
                .andExpect(jsonPath("$.property.captures[?(@.zone == 'Hall de entrada')].common").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.property.captures[?(@.zone == 'Living 3A')]").isEmpty());
    }

    @Test
    @DisplayName("en una obra única todos sus recorridos son de la unidad, aunque no la indiquen")
    void singleDevelopmentToursBelongToItsUnit() throws Exception {
        UUID developmentId = development("Casa Los Aromos", "SINGLE");
        capture(developmentId, null, "Cocina");

        mockMvc.perform(get("/api/my-units/" + firstUnit(developmentId)).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.property.developmentKind").value("SINGLE"))
                .andExpect(jsonPath("$.property.captures.length()").value(1))
                .andExpect(jsonPath("$.property.captures[0].common").value(false));
        mockMvc.perform(get("/api/my-units").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("un tipo inválido se rechaza")
    void invalidKindIsRejected() throws Exception {
        send(post("/api/developments"), """
                {"organizationId":"%s","name":"X","kind":"BARRIO"}""".formatted(organizationId))
                .andExpect(status().isBadRequest());
    }
}
