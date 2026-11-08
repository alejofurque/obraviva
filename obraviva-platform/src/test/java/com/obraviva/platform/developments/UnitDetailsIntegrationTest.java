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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ficha de la unidad: lo que "Mi propiedad" muestra de la casa o el departamento.
 *
 * <p>La portada es una imagen propia de la unidad y no entra a la galería de la obra:
 * un render mezclado con fotos de avance haría pasar lo proyectado por lo construido.
 */
@SpringBootTest
@AutoConfigureMockMvc // copiar import de AuthenticationIntegrationTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class UnitDetailsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "secreto123";

    private static final String FULL_DETAILS = """
            {"areaM2":126.5,"bedrooms":3,"bathrooms":2,
             "features":["Cochera","Galería","Patio"],"estimatedDelivery":"2027-03"}""";

    @MockitoBean private StorageService storageService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String ownerToken;
    private String memberToken;
    private UUID developmentId;
    private UUID unitId;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString()))
                .thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString(), any(Duration.class)))
                .thenReturn("https://almacenamiento.test/portada");
        when(storageService.exists(anyString())).thenReturn(true);

        Organization organization = organizationRepository.save(new Organization("Constructora Ficha"));
        ownerToken = login(createUser("duenio", organization, OrganizationRole.OWNER));
        memberToken = login(createUser("encargado", organization, OrganizationRole.MEMBER));

        developmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Los Aromos"}""".formatted(organization.getId()))));
        unitId = idOf(ok(send(ownerToken, post("/api/developments/" + developmentId + "/units"), """
                {"code":"Casa 12","description":"Tres dormitorios"}""")));
    }

    // --- Armado ---

    private String createUser(String prefix, Organization organization, OrganizationRole role) {
        String email = prefix + "-" + UUID.randomUUID() + "@obraviva.test";
        User user = userRepository.save(
                new User(email, passwordEncoder.encode(PASSWORD), "Usuario " + prefix));
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

    private ResultActions send(
            String token,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String body) throws Exception {
        return mockMvc.perform(builder
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String ok(ResultActions actions) throws Exception {
        return actions.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static UUID idOf(String json) {
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    private String detailsUrl() {
        return "/api/units/" + unitId + "/details";
    }

    // --- Ficha ---

    @Test
    @DisplayName("el dueño completa la ficha y se ve en el listado y en la vista de la unidad")
    void ownerFillsDetails() throws Exception {
        send(ownerToken, put(detailsUrl()), FULL_DETAILS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaM2").value(126.5))
                .andExpect(jsonPath("$.bedrooms").value(3))
                .andExpect(jsonPath("$.bathrooms").value(2))
                .andExpect(jsonPath("$.features[1]").value("Galería"))
                .andExpect(jsonPath("$.estimatedDelivery").value("2027-03"))
                .andExpect(jsonPath("$.hasCover").value(false));

        mockMvc.perform(get("/api/developments/" + developmentId + "/units")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$[0].bedrooms").value(3))
                .andExpect(jsonPath("$[0].features.length()").value(3));

        mockMvc.perform(get("/api/my-units/" + unitId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.property.details.areaM2").value(126.5))
                .andExpect(jsonPath("$.property.details.bedrooms").value(3))
                .andExpect(jsonPath("$.property.details.bathrooms").value(2))
                .andExpect(jsonPath("$.property.details.features[2]").value("Patio"))
                .andExpect(jsonPath("$.property.details.estimatedDelivery").value("2027-03"))
                .andExpect(jsonPath("$.property.details.coverUrl").value(nullValue()));
    }

    @Test
    @DisplayName("la ficha se reemplaza completa: lo que no viene queda vacío")
    void detailsAreReplaced() throws Exception {
        send(ownerToken, put(detailsUrl()), FULL_DETAILS).andExpect(status().isOk());

        send(ownerToken, put(detailsUrl()), """
                {"bedrooms":2}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bedrooms").value(2))
                .andExpect(jsonPath("$.areaM2").value(nullValue()))
                .andExpect(jsonPath("$.estimatedDelivery").value(nullValue()))
                .andExpect(jsonPath("$.features").isEmpty());
    }

    @Test
    @DisplayName("valores imposibles se rechazan")
    void invalidDetailsAreRejected() throws Exception {
        send(ownerToken, put(detailsUrl()), """
                {"bedrooms":-1}""").andExpect(status().isBadRequest());
        send(ownerToken, put(detailsUrl()), """
                {"areaM2":0}""").andExpect(status().isBadRequest());
        send(ownerToken, put(detailsUrl()), """
                {"features":["%s"]}""".formatted("x".repeat(61))).andExpect(status().isBadRequest());
        send(ownerToken, put(detailsUrl()), """
                {"features":["a","b","c","d","e","f","g","h","i","j","k","l","m"]}""")
                .andExpect(status().isBadRequest());
        send(ownerToken, put(detailsUrl()), """
                {"estimatedDelivery":"marzo 2027"}""").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("los extras vacíos o repetidos se depuran")
    void featuresAreCleaned() throws Exception {
        send(ownerToken, put(detailsUrl()), """
                {"features":["  Cochera ","","Cochera","Patio"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.length()").value(2))
                .andExpect(jsonPath("$.features[0]").value("Cochera"))
                .andExpect(jsonPath("$.features[1]").value("Patio"));
    }

    @Test
    @DisplayName("el encargado no edita la ficha ni la portada")
    void memberCannotEditDetails() throws Exception {
        send(memberToken, put(detailsUrl()), FULL_DETAILS).andExpect(status().isForbidden());
        send(memberToken, post("/api/units/" + unitId + "/cover/upload-url"), """
                {"filename":"render.jpg","contentType":"image/jpeg"}""").andExpect(status().isForbidden());
        send(memberToken, post("/api/units/" + unitId + "/cover/confirm"), "{}")
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("una unidad ajena responde 404")
    void foreignUnitIsNotFound() throws Exception {
        Organization other = organizationRepository.save(new Organization("Constructora Ajena"));
        String outsiderToken = login(createUser("ajeno", other, OrganizationRole.OWNER));

        send(outsiderToken, put(detailsUrl()), FULL_DETAILS).andExpect(status().isNotFound());
    }

    // --- Portada ---

    @Test
    @DisplayName("la portada se sube, se confirma y aparece en la vista, fuera de la galería")
    void coverIsUploadedAndShown() throws Exception {
        send(ownerToken, post("/api/units/" + unitId + "/cover/upload-url"), """
                {"filename":"Render Frente.PNG","contentType":"image/png"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value("https://almacenamiento.test/subida"))
                .andExpect(jsonPath("$.contentType").value("image/png"));

        verify(storageService).presignUpload(
                argThat(key -> key.startsWith("developments/" + developmentId + "/units/" + unitId + "/cover-")
                        && key.endsWith(".png")),
                eq("image/png"));

        send(ownerToken, post("/api/units/" + unitId + "/cover/confirm"), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCover").value(true));

        mockMvc.perform(get("/api/my-units/" + unitId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.property.details.coverUrl").value(startsWith("https://")))
                // La portada no es un medio de la obra: no se suma a la galería.
                .andExpect(jsonPath("$.property.gallery").isEmpty());
    }

    @Test
    @DisplayName("confirmar sin archivo subido, o sin haber pedido la subida, es un conflicto")
    void confirmWithoutFileIsConflict() throws Exception {
        send(ownerToken, post("/api/units/" + unitId + "/cover/confirm"), "{}")
                .andExpect(status().isConflict());

        send(ownerToken, post("/api/units/" + unitId + "/cover/upload-url"), """
                {"filename":"render.jpg","contentType":"image/jpeg"}""").andExpect(status().isOk());
        when(storageService.exists(anyString())).thenReturn(false);

        send(ownerToken, post("/api/units/" + unitId + "/cover/confirm"), "{}")
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("la portada tiene que ser una imagen")
    void coverMustBeAnImage() throws Exception {
        send(ownerToken, post("/api/units/" + unitId + "/cover/upload-url"), """
                {"filename":"contrato.pdf","contentType":"application/pdf"}""")
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("el enlace público muestra la ficha sin la clave de almacenamiento")
    void publicLinkShowsDetails() throws Exception {
        send(ownerToken, put(detailsUrl()), FULL_DETAILS).andExpect(status().isOk());
        String link = ok(send(ownerToken, post("/api/units/" + unitId + "/share-links"), """
                {"label":"Familia"}"""));
        String token = JSON.readTree(link).get("token").asString();

        String body = mockMvc.perform(get("/api/public/share/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.bedrooms").value(3))
                .andExpect(jsonPath("$.details.estimatedDelivery").value("2027-03"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(unitId.toString()).doesNotContain("storageKey");
    }
}
