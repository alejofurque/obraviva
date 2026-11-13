package com.obraviva.platform.developments;

import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.captures3d.Capture3dRepository;
import com.obraviva.platform.media.MediaAssetRepository;
import com.obraviva.platform.organizations.Organization;
import com.obraviva.platform.organizations.OrganizationMember;
import com.obraviva.platform.organizations.OrganizationMemberRepository;
import com.obraviva.platform.organizations.OrganizationRepository;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.storage.StorageService;
import com.obraviva.platform.support.SpzFixtures;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Los sectores de una casa organizan también sus fotos y videos, no sólo el 3D.
 *
 * <p>Se prueba lo que no puede romperse: un renombre lleva todo el material del lugar al
 * nombre nuevo sin tocar otra casa, quitar un lugar con material no deja nada colgado, una
 * foto sólo lleva un sector de su casa, y el comprador ve cada lugar mes a mes sin
 * identificadores y sin lo de otras casas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false"
})
class HouseSectorsIntegrationTest {

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
    @Autowired private MediaAssetRepository mediaRepository;
    @Autowired private Capture3dRepository captureRepository;
    @Autowired private CaptureSectorRepository sectorRepository;
    @Autowired private com.obraviva.platform.comparisons.ProjectComparisonRepository comparisonRepository;

    private String ownerToken;
    private String supervisorToken;
    private String outsiderToken;
    private UUID developmentId;
    private UUID casa1;
    private UUID casa2;

    @BeforeEach
    void setUp() throws Exception {
        when(storageService.presignUpload(anyString(), anyString())).thenReturn("https://almacenamiento.test/subida");
        when(storageService.presignDownload(anyString())).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.presignDownload(anyString(), any(Duration.class))).thenReturn("https://almacenamiento.test/objeto");
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.size(anyString())).thenReturn(SpzFixtures.TYPICAL_SIZE);
        when(storageService.readPrefix(anyString(), anyInt())).thenReturn(SpzFixtures.valid());

        Organization organization = organizationRepository.save(new Organization("Casas del Sur"));
        ownerToken = login(createUser("duenio", organization, OrganizationRole.OWNER));
        String supervisorEmail = createUser("carlos", organization, OrganizationRole.MEMBER);
        UUID supervisorId = userRepository.findByEmail(supervisorEmail).orElseThrow().getId();
        supervisorToken = login(supervisorEmail);
        outsiderToken = login(createUser("ajeno", organizationRepository.save(new Organization("Otra")), OrganizationRole.OWNER));

        developmentId = idOf(ok(send(ownerToken, post("/api/developments"), """
                {"organizationId":"%s","name":"Barrio del Sur"}""".formatted(organization.getId()))));
        JsonNode houses = json(ok(send(ownerToken, post("/api/developments/" + developmentId + "/units/batch"), """
                {"prefix":"Casa","firstNumber":1,"count":2,"withStandardStages":true}""")));
        casa1 = UUID.fromString(houses.get(0).get("id").asString());
        casa2 = UUID.fromString(houses.get(1).get("id").asString());
        ok(send(ownerToken, post("/api/developments/" + developmentId + "/team"), """
                {"userId":"%s","unitIds":["%s"]}""".formatted(supervisorId, casa1)));
    }

    // --- Renombrar ---

    @Test
    @DisplayName("renombrar lleva fotos y recorridos al nombre nuevo, conserva el sector, y no toca otra casa con el mismo nombre")
    void renameCascadesOnlyInThatHouse() throws Exception {
        UUID photo1 = galleryPhoto(ownerToken, casa1, "Living y cocina");
        UUID capture1 = capture(ownerToken, casa1, "Living y cocina", "2026-09-01");
        UUID photo2 = galleryPhoto(ownerToken, casa2, "Living y cocina");
        UUID capture2 = capture(ownerToken, casa2, "Living y cocina", "2026-09-01");
        UUID sectorId = sectorId(casa1, "Living y cocina");

        JsonNode sectors = json(ok(send(ownerToken, put("/api/units/" + casa1 + "/sectors"), renamed("Living y cocina", "Living"))));

        assertThat(texts(sectors)).containsExactly("Frente", "Living", "Dormitorio principal", "Baño");
        assertThat(sectorId(casa1, "Living")).isEqualTo(sectorId);
        assertThat(mediaRepository.findById(photo1).orElseThrow().getSector()).isEqualTo("Living");
        assertThat(captureRepository.findById(capture1).orElseThrow().getZone()).isEqualTo("Living");
        assertThat(mediaRepository.findById(photo2).orElseThrow().getSector()).isEqualTo("Living y cocina");
        assertThat(captureRepository.findById(capture2).orElseThrow().getZone()).isEqualTo("Living y cocina");
    }

    @Test
    @DisplayName("renombrar a un sector que ya existe da 400; el encargado no renombra (403) y otra organización no llega (404)")
    void renameConflictsAndPermissions() throws Exception {
        send(ownerToken, put("/api/units/" + casa1 + "/sectors"), renamed("Baño", "frente")).andExpect(status().isBadRequest());
        send(supervisorToken, put("/api/units/" + casa1 + "/sectors"), renamed("Baño", "Baño principal")).andExpect(status().isForbidden());
        send(outsiderToken, put("/api/units/" + casa1 + "/sectors"), renamed("Baño", "Baño principal")).andExpect(status().isNotFound());
        // Cambiar sólo mayúsculas es un renombre de sí mismo, no un choque.
        assertThat(texts(json(ok(send(ownerToken, put("/api/units/" + casa1 + "/sectors"), renamed("Baño", "baño")))))).contains("baño");
    }

    /** Los sectores de la plantilla de la Casa 1 con uno renombrado, como los manda el editor. */
    private static String renamed(String from, String to) {
        List<String> rows = new ArrayList<>();
        for (String name : List.of("Frente", "Living y cocina", "Dormitorio principal", "Baño")) {
            rows.add("{\"name\":\"%s\",\"previousName\":\"%s\"}".formatted(name.equals(from) ? to : name, name));
        }
        return "{\"sectors\":[" + String.join(",", rows) + "]}";
    }

    // --- Cambiar sectores por diferencias ---

    @Test
    @DisplayName("cambiar sectores calcula diferencias: renombra con su material, agrega, reordena y conserva los ids")
    void replaceComputesDifferences() throws Exception {
        UUID frentePhoto = galleryPhoto(ownerToken, casa1, "Frente");
        UUID banioPhoto = galleryPhoto(ownerToken, casa1, "Baño");
        UUID frenteId = sectorId(casa1, "Frente");
        UUID banioId = sectorId(casa1, "Baño");

        // Intercambia dos nombres con material, agrega uno y deja afuera uno vacío.
        JsonNode sectors = json(ok(send(ownerToken, put("/api/units/" + casa1 + "/sectors"), """
                {"sectors":[
                  {"name":"Baño","previousName":"Frente"},
                  {"name":"Quincho"},
                  {"name":"Frente","previousName":"Baño"},
                  {"name":"Living y cocina","previousName":"Living y cocina"}]}""")));

        assertThat(texts(sectors)).containsExactly("Baño", "Quincho", "Frente", "Living y cocina");
        assertThat(sectorId(casa1, "Baño")).isEqualTo(frenteId);
        assertThat(sectorId(casa1, "Frente")).isEqualTo(banioId);
        assertThat(mediaRepository.findById(frentePhoto).orElseThrow().getSector()).isEqualTo("Baño");
        assertThat(mediaRepository.findById(banioPhoto).orElseThrow().getSector()).isEqualTo("Frente");
    }

    @Test
    @DisplayName("quitar un sector con material da 409 con el conteo y no cambia nada; sin material, se quita")
    void removingASectorWithMaterialConflicts() throws Exception {
        galleryPhoto(ownerToken, casa1, "Frente");
        galleryPhoto(ownerToken, casa1, "Frente");
        capture(ownerToken, casa1, "Frente", "2026-09-01");

        String message = send(ownerToken, put("/api/units/" + casa1 + "/sectors"), """
                {"sectors":[{"name":"Baño","previousName":"Baño"}]}""")
                .andExpect(status().isConflict())
                .andReturn().getResponse().getErrorMessage();
        assertThat(message).isEqualTo("Frente tiene 2 fotos y 1 recorrido: renombralo o movelos antes de quitarlo.");
        assertThat(texts(json(ok(send(ownerToken, get("/api/units/" + casa1 + "/sectors"))))))
                .containsExactly("Frente", "Living y cocina", "Dormitorio principal", "Baño");

        assertThat(texts(json(ok(send(ownerToken, put("/api/units/" + casa1 + "/sectors"), """
                {"sectors":[{"name":"Frente","previousName":"Frente"},{"name":"Baño","previousName":"Baño"}]}"""))))).containsExactly("Frente", "Baño");
    }

    // --- El sector de una foto ---

    @Test
    @DisplayName("una foto lleva un sector de su casa; con otro, o en obras comunes, 400")
    void photoSectorMustBeOfItsHouse() throws Exception {
        send(ownerToken, post(uploadUrl()), """
                {"filename":"x.jpg","contentType":"image/jpeg","unitId":"%s","sector":"Quincho"}""".formatted(casa1))
                .andExpect(status().isBadRequest());
        send(ownerToken, post(uploadUrl()), """
                {"filename":"x.jpg","contentType":"image/jpeg","sector":"Frente"}""")
                .andExpect(status().isBadRequest());
        UUID photo = galleryPhoto(ownerToken, casa1, null);
        assertThat(mediaRepository.findById(photo).orElseThrow().getSector()).isNull();
    }

    @Test
    @DisplayName("cambiar el sector de una foto publicada: el dueño y el encargado de su casa; casa ajena 403, otra organización 404")
    void changeTheSectorOfAPublishedPhoto() throws Exception {
        UUID own = galleryPhoto(ownerToken, casa1, null);
        UUID other = galleryPhoto(ownerToken, casa2, null);

        JsonNode changed = json(ok(send(ownerToken, patch("/api/media/" + own + "/sector"), """
                {"sector":"Frente"}""")));
        assertThat(changed.get("sector").asString()).isEqualTo("Frente");
        ok(send(supervisorToken, patch("/api/media/" + own + "/sector"), """
                {"sector":"Baño"}"""));
        assertThat(mediaRepository.findById(own).orElseThrow().getSector()).isEqualTo("Baño");
        ok(send(supervisorToken, patch("/api/media/" + own + "/sector"), """
                {"sector":null}"""));
        assertThat(mediaRepository.findById(own).orElseThrow().getSector()).isNull();

        send(supervisorToken, patch("/api/media/" + other + "/sector"), """
                {"sector":"Frente"}""").andExpect(status().isForbidden());
        send(outsiderToken, patch("/api/media/" + own + "/sector"), """
                {"sector":"Frente"}""").andExpect(status().isNotFound());
        send(ownerToken, patch("/api/media/" + own + "/sector"), """
                {"sector":"Quincho"}""").andExpect(status().isBadRequest());
        assertThat(mediaRepository.findById(other).orElseThrow().getSector()).isNull();
    }

    @Test
    @DisplayName("al publicar un avance, cada foto lleva su sector; uno inválido no publica nada")
    void publicationAssignsPhotoSectors() throws Exception {
        UUID frente = pendingPhoto(supervisorToken, casa1);
        UUID general = pendingPhoto(supervisorToken, casa1);

        send(supervisorToken, post("/api/developments/" + developmentId + "/progress"), """
                {"entryDate":"2026-09-10","description":"x","unitId":"%s","photoIds":["%s"],"photoSectors":{"%s":"Quincho"}}"""
                .formatted(casa1, frente, frente)).andExpect(status().isBadRequest());
        send(supervisorToken, post("/api/developments/" + developmentId + "/progress"), """
                {"entryDate":"2026-09-10","description":"x","unitId":"%s","photoIds":["%s"],"photoSectors":{"%s":"Frente"}}"""
                .formatted(casa1, frente, general)).andExpect(status().isBadRequest());
        assertThat(json(ok(send(ownerToken, get("/api/developments/" + developmentId + "/progress"))))).isEmpty();

        ok(send(supervisorToken, post("/api/developments/" + developmentId + "/progress"), """
                {"entryDate":"2026-09-10","description":"Frente revocado","unitId":"%s","photoIds":["%s","%s"],"photoSectors":{"%s":"Frente"}}"""
                .formatted(casa1, frente, general, frente)));
        assertThat(mediaRepository.findById(frente).orElseThrow().getSector()).isEqualTo("Frente");
        assertThat(mediaRepository.findById(general).orElseThrow().getSector()).isNull();
    }

    // --- Lo que ve el comprador ---

    @Test
    @DisplayName("la vista pública muestra cada lugar mes a mes, General al final, sin la galería, sin ids, sin otras casas ni obras comunes")
    void publicViewShowsEachPlaceMonthByMonth() throws Exception {
        UUID entry = idOf(ok(send(ownerToken, post("/api/developments/" + developmentId + "/progress"), """
                {"entryDate":"2026-08-10","description":"Frente revocado","unitId":"%s"}""".formatted(casa1))));
        UUID avancePhoto = idOf(ok(send(ownerToken, post(uploadUrl()), """
                {"filename":"frente.jpg","contentType":"image/jpeg","progressEntryId":"%s","sector":"Frente"}""".formatted(entry))), "mediaId");
        ok(send(ownerToken, post("/api/media/" + avancePhoto + "/confirm"), "{}"));
        // Una foto del avance sin lugar va a General.
        UUID sinLugar = idOf(ok(send(ownerToken, post(uploadUrl()), """
                {"filename":"obra.jpg","contentType":"image/jpeg","progressEntryId":"%s"}""".formatted(entry))), "mediaId");
        ok(send(ownerToken, post("/api/media/" + sinLugar + "/confirm"), "{}"));
        // La galería es material de venta: no entra en lugar por lugar, tenga o no lugar.
        galleryPhoto(ownerToken, casa1, "Frente");
        galleryPhoto(ownerToken, casa1, null);
        UUID video = idOf(ok(send(ownerToken, post(uploadUrl()), """
                {"filename":"living.mp4","contentType":"video/mp4","unitId":"%s","sector":"Living y cocina","showcase":false,"takenOn":"%s"}"""
                .formatted(casa1, LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires")).minusDays(1)))), "mediaId");
        ok(send(ownerToken, post("/api/media/" + video + "/confirm"), "{}"));
        capture(ownerToken, casa1, "Frente", "2026-09-05");
        galleryPhoto(ownerToken, casa2, "Frente");
        UUID common = idOf(ok(send(ownerToken, post(uploadUrl()), """
                {"filename":"calle.jpg","contentType":"image/jpeg"}""")), "mediaId");
        ok(send(ownerToken, post("/api/media/" + common + "/confirm"), "{}"));

        String token = json(ok(send(ownerToken, post("/api/units/" + casa1 + "/share-links"), """
                {"label":"Familia"}"""))).get("token").asString();
        String body = mockMvc.perform(get("/api/public/share/" + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode places = json(body).get("places");

        assertThat(names(places)).containsExactly("Frente", "Living y cocina", "General");
        JsonNode frente = places.get(0).get("months");
        assertThat(months(frente)).containsExactly("2026-08", "2026-09");
        assertThat(frente.get(0).get("photos")).hasSize(1);
        assertThat(frente.get(0).get("photos").get(0).get("entryDate").asString()).isEqualTo("2026-08-10");
        assertThat(frente.get(1).get("photos")).isEmpty();
        assertThat(frente.get(1).get("tour").asBoolean()).isTrue();
        assertThat(places.get(1).get("months").get(0).get("videos")).hasSize(1);
        assertThat(places.get(1).get("months").get(0).get("photos")).isEmpty();
        assertThat(places.get(2).get("months").get(0).get("photos")).hasSize(1);
        assertThat(body).doesNotContain(avancePhoto.toString()).doesNotContain(common.toString())
                .doesNotContain(casa1.toString()).doesNotContain(entry.toString());
    }

    // --- Fotos de la sesión de recorrido y galería ---

    @Test
    @DisplayName("una foto de la sesión no es galería: va a su lugar en el mes de la sesión; sin decir nada, de casa y sin avance es galería")
    void sessionPhotosAreNotShowcase() throws Exception {
        UUID session = idOf(ok(send(supervisorToken, post(uploadUrl()), """
                {"filename":"frente.jpg","contentType":"image/jpeg","unitId":"%s","sector":"Frente","showcase":false,"takenOn":"2026-09-30"}"""
                .formatted(casa1))), "mediaId");
        ok(send(supervisorToken, post("/api/media/" + session + "/confirm"), "{}"));
        UUID gallery = galleryPhoto(ownerToken, casa1, null);

        assertThat(mediaRepository.findById(session).orElseThrow().isShowcase()).isFalse();
        assertThat(mediaRepository.findById(gallery).orElseThrow().isShowcase()).isTrue();
        JsonNode view = json(ok(send(ownerToken, get("/api/my-units/" + casa1))));
        JsonNode property = view.has("property") ? view.get("property") : view;
        assertThat(property.get("showcase")).hasSize(1);
        JsonNode frente = property.get("places").get(0);
        assertThat(frente.get("name").asString()).isEqualTo("Frente");
        assertThat(frente.get("months").get(0).get("month").asString()).isEqualTo("2026-09");
    }

    @Test
    @DisplayName("la fecha de la sesión no puede ser futura; la galería es de una casa y no de un avance")
    void sessionDateAndShowcaseRules() throws Exception {
        send(supervisorToken, post(uploadUrl()), """
                {"filename":"x.jpg","contentType":"image/jpeg","unitId":"%s","showcase":false,"takenOn":"%s"}"""
                .formatted(casa1, LocalDate.now().plusDays(1))).andExpect(status().isBadRequest());
        send(ownerToken, post(uploadUrl()), """
                {"filename":"x.jpg","contentType":"image/jpeg","showcase":true}""").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("confirmar o borrar una foto de la casa B, siendo encargado de la casa A: 403; otra organización: 404")
    void confirmAndDeleteRequireTheHouse() throws Exception {
        UUID pending = pendingPhoto(ownerToken, casa2);
        UUID published = galleryPhoto(ownerToken, casa2, null);

        send(supervisorToken, post("/api/media/" + pending + "/confirm"), "{}").andExpect(status().isForbidden());
        send(supervisorToken, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/media/" + published))
                .andExpect(status().isForbidden());
        send(outsiderToken, post("/api/media/" + pending + "/confirm"), "{}").andExpect(status().isNotFound());

        assertThat(mediaRepository.findById(pending).orElseThrow().getStatus())
                .isEqualTo(com.obraviva.platform.media.MediaStatus.PENDING);
        assertThat(mediaRepository.findById(published)).isPresent();
    }

    // --- El frente ---

    @Test
    @DisplayName("la plantilla trae el frente marcado, y renombrarlo no lo pierde: la vista sigue sabiendo cuál es")
    void frontSurvivesRename() throws Exception {
        JsonNode detail = json(ok(send(ownerToken, get("/api/units/" + casa1 + "/sectors/detail"))));
        assertThat(detail.get(0).get("name").asString()).isEqualTo("Frente");
        assertThat(detail.get(0).get("front").asBoolean()).isTrue();
        assertThat(detail.get(1).get("front").asBoolean()).isFalse();

        ok(send(ownerToken, put("/api/units/" + casa1 + "/sectors"), renamed("Frente", "Frente de la casa")));

        String token = json(ok(send(ownerToken, post("/api/units/" + casa1 + "/share-links"), """
                {"label":"Familia"}"""))).get("token").asString();
        JsonNode view = json(mockMvc.perform(get("/api/public/share/" + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(view.get("front").get("sector").asString()).isEqualTo("Frente de la casa");
    }

    @Test
    @DisplayName("un segundo frente en la misma casa se rechaza; pasar la marca de un sector a otro, sí")
    void onlyOneFront() throws Exception {
        send(ownerToken, put("/api/units/" + casa1 + "/sectors"), """
                {"sectors":[{"name":"Frente","previousName":"Frente"},{"name":"Baño","previousName":"Baño","front":true}]}""")
                .andExpect(status().isBadRequest());

        ok(send(ownerToken, put("/api/units/" + casa1 + "/sectors"), """
                {"sectors":[{"name":"Frente","previousName":"Frente","front":false},{"name":"Baño","previousName":"Baño","front":true}]}"""));
        JsonNode detail = json(ok(send(ownerToken, get("/api/units/" + casa1 + "/sectors/detail"))));
        assertThat(detail.get(0).get("front").asBoolean()).isFalse();
        assertThat(detail.get(1).get("front").asBoolean()).isTrue();
        assertThat(json(ok(send(ownerToken, get("/api/units/" + casa2 + "/sectors/detail")))).get(0).get("front").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("el frente del comprador: portada y \"cuando empezó\" de sesión y avances, nunca de la galería")
    void buyerFrontIgnoresTheGallery() throws Exception {
        // URLs distintas por objeto, para saber qué foto eligió la vista.
        when(storageService.presignDownload(anyString(), any(Duration.class)))
                .thenAnswer(invocation -> "https://almacenamiento.test/" + invocation.getArgument(0));
        UUID entry = idOf(ok(send(ownerToken, post("/api/developments/" + developmentId + "/progress"), """
                {"entryDate":"2026-08-10","description":"Frente revocado","unitId":"%s"}""".formatted(casa1))));
        UUID avance = idOf(ok(send(ownerToken, post(uploadUrl()), """
                {"filename":"frente.jpg","contentType":"image/jpeg","progressEntryId":"%s","sector":"Frente"}""".formatted(entry))), "mediaId");
        ok(send(ownerToken, post("/api/media/" + avance + "/confirm"), "{}"));
        UUID session = idOf(ok(send(supervisorToken, post(uploadUrl()), """
                {"filename":"frente.jpg","contentType":"image/jpeg","unitId":"%s","sector":"Frente","showcase":false,"takenOn":"2026-09-30"}"""
                .formatted(casa1))), "mediaId");
        ok(send(supervisorToken, post("/api/media/" + session + "/confirm"), "{}"));
        // Subida hoy, la más nueva de todas, y con el sector del frente: igual no cuenta.
        UUID gallery = galleryPhoto(ownerToken, casa1, "Frente");

        UUID comparison = idOf(ok(send(ownerToken, post("/api/developments/" + developmentId + "/comparisons"), """
                {"title":"Fachada","unitId":"%s"}""".formatted(casa1))));
        ok(send(ownerToken, post("/api/comparisons/" + comparison + "/render/upload-url"), """
                {"filename":"render.jpg","contentType":"image/jpeg"}"""));
        ok(send(ownerToken, post("/api/comparisons/" + comparison + "/render/confirm"), ""));
        ok(send(ownerToken, put("/api/comparisons/" + comparison), """
                {"title":"Fachada","unitId":"%s","photoMediaId":"%s"}""".formatted(casa1, avance)));

        String token = json(ok(send(ownerToken, post("/api/units/" + casa1 + "/share-links"), """
                {"label":"Familia"}"""))).get("token").asString();
        JsonNode view = json(mockMvc.perform(get("/api/public/share/" + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode front = view.get("front");
        String galleryUrl = "https://almacenamiento.test/" + mediaRepository.findById(gallery).orElseThrow().getStorageKey();

        assertThat(front.get("sector").asString()).isEqualTo("Frente");
        assertThat(front.get("latest").get("url").asString())
                .isEqualTo("https://almacenamiento.test/" + mediaRepository.findById(session).orElseThrow().getStorageKey());
        assertThat(front.get("latest").get("date").asString()).isEqualTo("2026-09-30");
        assertThat(front.get("first").get("url").asString())
                .isEqualTo("https://almacenamiento.test/" + mediaRepository.findById(avance).orElseThrow().getStorageKey());
        assertThat(front.get("first").get("date").asString()).isEqualTo("2026-08-10");
        assertThat(front.toString()).doesNotContain(galleryUrl);
        // Sin render del frente, El proyecto es la comparación de la casa contra su propia foto.
        assertThat(front.get("renderUrl").isNull()).isTrue();
        assertThat(front.get("project").get("renderUrl").asString()).isEqualTo("https://almacenamiento.test/"
                + comparisonRepository.findById(comparison).orElseThrow().getRenderStorageKey());
        assertThat(front.get("project").get("photoUrl").asString())
                .isEqualTo("https://almacenamiento.test/" + mediaRepository.findById(avance).orElseThrow().getStorageKey());

        JsonNode august = view.get("places").get(0).get("months").get(0);
        assertThat(august.get("month").asString()).isEqualTo("2026-08");
        assertThat(august.get("notes")).hasSize(1);
        assertThat(august.get("notes").get(0).get("description").asString()).isEqualTo("Frente revocado");
        assertThat(view.get("places").get(0).get("months").get(1).get("photos").get(0).get("takenOn").asString())
                .isEqualTo("2026-09-30");
    }

    // --- Armado ---

    private String uploadUrl() {
        return "/api/developments/" + developmentId + "/media/upload-url";
    }

    private UUID galleryPhoto(String token, UUID unitId, String sector) throws Exception {
        String sectorField = sector == null ? "" : ",\"sector\":\"" + sector + "\"";
        UUID mediaId = idOf(ok(send(token, post(uploadUrl()), """
                {"filename":"foto.jpg","contentType":"image/jpeg","unitId":"%s"%s}""".formatted(unitId, sectorField))), "mediaId");
        ok(send(token, post("/api/media/" + mediaId + "/confirm"), "{}"));
        return mediaId;
    }

    private UUID pendingPhoto(String token, UUID unitId) throws Exception {
        return idOf(ok(send(token, post(uploadUrl()), """
                {"filename":"foto.jpg","contentType":"image/jpeg","unitId":"%s"}""".formatted(unitId))), "mediaId");
    }

    private UUID capture(String token, UUID unitId, String zone, String date) throws Exception {
        UUID captureId = idOf(ok(send(token, post("/api/developments/" + developmentId + "/captures"), """
                {"captureDate":"%s","unitId":"%s","zone":"%s","filename":"s.spz","fileSize":1000}"""
                .formatted(date, unitId, zone))), "captureId");
        ok(send(token, post("/api/captures/" + captureId + "/confirm"), "{}"));
        return captureId;
    }

    private UUID sectorId(UUID unitId, String name) {
        return sectorRepository.findByUnitIdOrderByOrderIndexAsc(unitId).stream()
                .filter(sector -> sector.getName().equals(name)).findFirst().orElseThrow().getId();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private static List<String> names(JsonNode places) {
        List<String> values = new ArrayList<>();
        places.forEach(node -> values.add(node.get("name").asString()));
        return values;
    }

    private static List<String> months(JsonNode months) {
        List<String> values = new ArrayList<>();
        months.forEach(node -> values.add(node.get("month").asString()));
        return values;
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
        return idOf(body, "id");
    }

    private static UUID idOf(String body, String field) {
        return UUID.fromString(JSON.readTree(body).get(field).asString());
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }
}
