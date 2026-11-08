package com.obraviva.platform.plans;

import com.obraviva.platform.developments.DevelopmentMemberRepository;
import com.obraviva.platform.support.SupervisorAssignments;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Planos interactivos: plantas a escala con ambientes tocables.
 *
 * <p>Las superficies se verifican contra cuentas hechas a mano. Un ambiente de 4 por 5
 * metros mide 20 m²; si el dueño declaró la superficie del plano de arquitectura, se
 * muestra ésa.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class FloorPlanIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    /** Living de 4×5 con superficie calculada, y cocina en L con superficie declarada. */
    private static final String ROOMS = """
            {"rooms":[
              {"name":"Living + Comedor","polygon":[0,0, 4,0, 4,5, 0,5]},
              {"name":"Cocina","polygon":[4,0, 7,0, 7,2, 6,2, 6,3, 4,3],"declaredAreaM2":9.1}
            ]}""";

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private DevelopmentMemberRepository assignmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private String memberToken;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/firmada");

        Organization organization = organizationRepository.save(new Organization("Constructora Planos"));
        ownerToken = login(user("duenio", organization, OrganizationRole.OWNER));
        memberToken = login(user("encargado", organization, OrganizationRole.MEMBER));

        UUID developmentId = idOf(ok(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Los Aromos"}""".formatted(organization.getId())));
        SupervisorAssignments.assignAllSupervisors(memberRepository, assignmentRepository, organization.getId(), developmentId);
        unitId = idOf(ok(ownerToken, post("/api/developments/" + developmentId + "/units"), """
                {"code":"Casa 12"}"""));
    }

    // --- Armado ---

    private String user(String prefix, Organization org, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User saved = userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
        if (org != null) {
            memberRepository.save(new OrganizationMember(org, saved.getId(), role));
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

    private static UUID idOf(String json) {
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    private UUID plan(String name, double width, double depth) throws Exception {
        return idOf(ok(ownerToken, post("/api/units/" + unitId + "/plans"), """
                {"name":"%s","widthM":%s,"depthM":%s}""".formatted(name, width, depth)));
    }

    // --- Plantas y ambientes ---

    @Test
    @DisplayName("el dueño crea una planta, dibuja ambientes y ve las superficies correctas")
    void ownerDrawsRoomsWithAreas() throws Exception {
        UUID planId = plan("Planta baja", 12, 9);

        send(ownerToken, put("/api/plans/" + planId + "/rooms"), ROOMS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms.length()").value(2))
                .andExpect(jsonPath("$.rooms[0].name").value("Living + Comedor"))
                .andExpect(jsonPath("$.rooms[0].areaM2").value(20.00))
                .andExpect(jsonPath("$.rooms[0].declaredArea").value(false))
                .andExpect(jsonPath("$.rooms[0].polygon[4]").value(4.0))
                .andExpect(jsonPath("$.rooms[1].areaM2").value(9.1))
                .andExpect(jsonPath("$.rooms[1].declaredArea").value(true));
    }

    @Test
    @DisplayName("la superficie dibujada de un polígono en L se calcula bien")
    void drawnAreaOfLShape() {
        // L de 3×3 menos un cuadrado de 1×1: 8 m², en cualquier sentido de recorrido.
        double[] clockwise = {4, 0, 7, 0, 7, 2, 6, 2, 6, 3, 4, 3};
        double[] reversed = {4, 3, 6, 3, 6, 2, 7, 2, 7, 0, 4, 0};

        assertThat(PlanRoom.drawnAreaM2(clockwise)).isEqualByComparingTo("8.00");
        assertThat(PlanRoom.drawnAreaM2(reversed)).isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("guardar ambientes reemplaza los anteriores")
    void roomsAreReplaced() throws Exception {
        UUID planId = plan("Planta baja", 12, 9);
        ok(ownerToken, put("/api/plans/" + planId + "/rooms"), ROOMS);

        send(ownerToken, put("/api/plans/" + planId + "/rooms"), """
                {"rooms":[{"name":"Toilette","polygon":[0,0, 1.5,0, 1.5,1.2, 0,1.2]}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms.length()").value(1))
                .andExpect(jsonPath("$.rooms[0].areaM2").value(1.80));
    }

    @Test
    @DisplayName("las plantas se ordenan, se editan y se borran con sus ambientes")
    void plansAreOrderedEditedAndDeleted() throws Exception {
        UUID first = plan("Planta baja", 12, 9);
        UUID second = plan("Planta alta", 12, 9);
        ok(ownerToken, put("/api/plans/" + first + "/rooms"), ROOMS);

        mockMvc.perform(get("/api/units/" + unitId + "/plans").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$[0].name").value("Planta baja"))
                .andExpect(jsonPath("$[1].name").value("Planta alta"))
                .andExpect(jsonPath("$[1].orderIndex").value(1));

        send(ownerToken, put("/api/plans/" + second), """
                {"name":"Instalaciones","widthM":12,"depthM":9,"orderIndex":5}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Instalaciones"));

        send(ownerToken, delete("/api/plans/" + first), "").andExpect(status().isNoContent());

        mockMvc.perform(get("/api/units/" + unitId + "/plans").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Instalaciones"));
    }

    @Test
    @DisplayName("achicar una planta que deja ambientes afuera es un conflicto")
    void shrinkingBelowRoomsIsConflict() throws Exception {
        UUID planId = plan("Planta baja", 12, 9);
        ok(ownerToken, put("/api/plans/" + planId + "/rooms"), ROOMS);

        send(ownerToken, put("/api/plans/" + planId), """
                {"name":"Planta baja","widthM":5,"depthM":9,"orderIndex":0}""")
                .andExpect(status().isConflict());
    }

    // --- Validación ---

    @Test
    @DisplayName("contornos inválidos se rechazan")
    void invalidPolygonsAreRejected() throws Exception {
        UUID planId = plan("Planta baja", 12, 9);

        send(ownerToken, put("/api/plans/" + planId + "/rooms"), """
                {"rooms":[{"name":"Impar","polygon":[0,0, 4,0, 4,5, 0]}]}""")
                .andExpect(status().isBadRequest());
        send(ownerToken, put("/api/plans/" + planId + "/rooms"), """
                {"rooms":[{"name":"Dos puntos","polygon":[0,0, 4,0]}]}""")
                .andExpect(status().isBadRequest());
        send(ownerToken, put("/api/plans/" + planId + "/rooms"), """
                {"rooms":[{"name":"Afuera","polygon":[0,0, 13,0, 13,5, 0,5]}]}""")
                .andExpect(status().isBadRequest());
        send(ownerToken, post("/api/units/" + unitId + "/plans"), """
                {"name":"Sin ancho","widthM":0,"depthM":9}""")
                .andExpect(status().isBadRequest());
    }

    // --- Permisos y vistas ---

    @Test
    @DisplayName("el encargado ve los planos pero no los edita")
    void memberReadsButCannotEdit() throws Exception {
        UUID planId = plan("Planta baja", 12, 9);

        mockMvc.perform(get("/api/units/" + unitId + "/plans").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        send(memberToken, post("/api/units/" + unitId + "/plans"), """
                {"name":"Otra","widthM":10,"depthM":10}""").andExpect(status().isForbidden());
        send(memberToken, put("/api/plans/" + planId + "/rooms"), ROOMS).andExpect(status().isForbidden());
        send(memberToken, delete("/api/plans/" + planId), "").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("fuera de la organización las plantas no existen")
    void outsiderGetsNotFound() throws Exception {
        UUID planId = plan("Planta baja", 12, 9);
        Organization other = organizationRepository.save(new Organization("Ajena"));
        String outsiderToken = login(user("ajeno", other, OrganizationRole.OWNER));

        mockMvc.perform(get("/api/units/" + unitId + "/plans").header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
        send(outsiderToken, put("/api/plans/" + planId + "/rooms"), ROOMS).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("los planos llegan a Mi propiedad y al enlace público, sin identificadores")
    void plansReachUnitViews() throws Exception {
        UUID planId = plan("Planta baja", 12, 9);
        String saved = ok(ownerToken, put("/api/plans/" + planId + "/rooms"), ROOMS);
        String roomId = JSON.readTree(saved).get("rooms").get(0).get("id").asString();

        mockMvc.perform(get("/api/my-units/" + unitId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.property.plans[0].name").value("Planta baja"))
                .andExpect(jsonPath("$.property.plans[0].widthM").value(12))
                .andExpect(jsonPath("$.property.plans[0].rooms[1].name").value("Cocina"))
                .andExpect(jsonPath("$.property.plans[0].rooms[1].areaM2").value(9.1));

        String link = ok(ownerToken, post("/api/units/" + unitId + "/share-links"), """
                {"label":"Familia"}""");
        String body = mockMvc.perform(get("/api/public/share/" + JSON.readTree(link).get("token").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[0].rooms.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(planId.toString()).doesNotContain(roomId);
    }
}
