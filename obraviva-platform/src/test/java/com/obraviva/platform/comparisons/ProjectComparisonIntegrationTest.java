package com.obraviva.platform.comparisons;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.developments.DevelopmentMemberRepository;
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
 * Comparador proyecto/realidad: el dueño lo arma con un render y una foto de avance; el
 * comprador lo ve sólo completo, y en un edificio sólo el de su unidad y los de la obra.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class ProjectComparisonIntegrationTest {

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
    private String buyerToken;
    private String developmentId;
    private String unitA;
    private String unitB;
    private String photoId;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/firmada");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/publica");
        when(storageService.exists(anyString())).thenReturn(true);

        Organization organization = organizationRepository.save(new Organization("Constructora Renders"));
        ownerToken = login(user("duenio", organization, OrganizationRole.OWNER));
        memberToken = login(user("encargado", organization, OrganizationRole.MEMBER));
        String buyerEmail = user("comprador", null, null);
        buyerToken = login(buyerEmail);

        developmentId = idOf(ok(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Edificio Tilo","kind":"MULTI_UNIT"}""".formatted(organization.getId())));
        SupervisorAssignments.assignAllSupervisors(memberRepository, assignmentRepository, organization.getId(), UUID.fromString(developmentId));
        unitA = idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/units"), """
                {"code":"1A"}"""));
        unitB = idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/units"), """
                {"code":"1B"}"""));
        ok(ownerToken, post("/api/units/" + unitA + "/members"), """
                {"email":"%s"}""".formatted(buyerEmail));

        photoId = photoOf(developmentId);
    }

    @Test
    @DisplayName("El dueño arma la comparación: render subido y foto de avance elegida")
    void ownerBuildsComparison() throws Exception {
        String comparison = createComplete(null, "Fachada desde la vereda");

        JsonNode list = JSON.readTree(ok(ownerToken, get("/api/developments/" + developmentId + "/comparisons"), ""));
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("id").asString()).isEqualTo(comparison);
        assertThat(list.get(0).get("complete").asBoolean()).isTrue();
        assertThat(list.get(0).get("renderUrl").asString()).isEqualTo("https://almacenamiento.test/firmada");
        assertThat(list.get(0).get("photoUrl").asString()).isEqualTo("https://almacenamiento.test/firmada");
    }

    @Test
    @DisplayName("El comprador ve las de su unidad y las de la obra, nunca las de otra unidad ni las incompletas")
    void buyerSeesOwnAndCommonComparisons() throws Exception {
        createComplete(null, "Fachada");
        createComplete(unitA, "Living 1A");
        createComplete(unitB, "Living 1B");
        idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/comparisons"), """
                {"title":"Sin render todavía"}"""));

        JsonNode comparisons = JSON.readTree(ok(buyerToken, get("/api/my-units/" + unitA), ""))
                .get("property").get("comparisons");

        assertThat(comparisons.size()).isEqualTo(2);
        assertThat(comparisons.get(0).get("title").asString()).isEqualTo("Fachada");
        assertThat(comparisons.get(0).get("common").asBoolean()).isTrue();
        assertThat(comparisons.get(1).get("title").asString()).isEqualTo("Living 1A");
        assertThat(comparisons.get(1).get("common").asBoolean()).isFalse();
        assertThat(comparisons.get(1).get("renderUrl").asString()).isEqualTo("https://almacenamiento.test/publica");
    }

    @Test
    @DisplayName("La foto tiene que ser de esta obra, y la unidad también")
    void photoAndUnitMustBelongToDevelopment() throws Exception {
        String otherDevelopment = idOf(ok(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Otra obra","kind":"SINGLE"}""".formatted(organizationIdOf(developmentId))));
        String foreignPhoto = photoOf(otherDevelopment);
        String foreignUnit = JSON.readTree(ok(ownerToken, get("/api/developments/" + otherDevelopment + "/units"), ""))
                .get(0).get("id").asString();
        String comparison = idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/comparisons"), """
                {"title":"Cocina"}"""));

        send(ownerToken, put("/api/comparisons/" + comparison), """
                {"title":"Cocina","photoMediaId":"%s"}""".formatted(foreignPhoto)).andExpect(status().isBadRequest());
        send(ownerToken, post("/api/developments/" + developmentId + "/comparisons"), """
                {"title":"Cocina","unitId":"%s"}""".formatted(foreignUnit)).andExpect(status().isBadRequest());
        send(ownerToken, post("/api/comparisons/" + comparison + "/render/upload-url"), """
                {"filename":"render.svg","contentType":"image/svg+xml"}""").andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("El encargado no arma comparaciones (403) y el comprador no llega a la lista (404)")
    void onlyOwnerManages() throws Exception {
        send(memberToken, get("/api/developments/" + developmentId + "/comparisons"), "").andExpect(status().isForbidden());
        send(memberToken, post("/api/developments/" + developmentId + "/comparisons"), """
                {"title":"Fachada"}""").andExpect(status().isForbidden());
        send(buyerToken, get("/api/developments/" + developmentId + "/comparisons"), "").andExpect(status().isNotFound());
    }

    // --- Armado ---

    private String createComplete(String unitId, String title) throws Exception {
        String unit = unitId == null ? "null" : "\"" + unitId + "\"";
        String comparison = idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/comparisons"), """
                {"title":"%s","unitId":%s}""".formatted(title, unit)));
        ok(ownerToken, post("/api/comparisons/" + comparison + "/render/upload-url"), """
                {"filename":"render.jpg","contentType":"image/jpeg"}""");
        ok(ownerToken, post("/api/comparisons/" + comparison + "/render/confirm"), "");
        ok(ownerToken, put("/api/comparisons/" + comparison), """
                {"title":"%s","unitId":%s,"photoMediaId":"%s"}""".formatted(title, unit, photoId));
        return comparison;
    }

    private String photoOf(String development) throws Exception {
        String entry = idOf(ok(ownerToken, post("/api/developments/" + development + "/progress"), """
                {"entryDate":"2026-09-01","description":"Fachada revocada"}"""));
        String media = JSON.readTree(ok(ownerToken, post("/api/developments/" + development + "/media/upload-url"), """
                {"filename":"fachada.jpg","contentType":"image/jpeg","progressEntryId":"%s"}""".formatted(entry)))
                .get("mediaId").asString();
        ok(ownerToken, post("/api/media/" + media + "/confirm"), "");
        return media;
    }

    private String organizationIdOf(String development) throws Exception {
        return JSON.readTree(ok(ownerToken, get("/api/developments/" + development), "")).get("organizationId").asString();
    }

    private String user(String prefix, Organization organization, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User saved = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        if (organization != null) {
            memberRepository.save(new OrganizationMember(organization, saved.getId(), role));
        }
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

    private ResultActions send(String token, MockHttpServletRequestBuilder builder, String body) throws Exception {
        return mockMvc.perform(builder
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String ok(String token, MockHttpServletRequestBuilder builder, String body) throws Exception {
        return send(token, builder, body).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private static String idOf(String json) {
        return JSON.readTree(json).get("id").asString();
    }
}
