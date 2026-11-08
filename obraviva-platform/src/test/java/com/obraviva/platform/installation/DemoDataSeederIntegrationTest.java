package com.obraviva.platform.installation;

import com.obraviva.platform.auth.UserDirectoryService;
import com.obraviva.platform.auth.UserDirectoryService.UserSummary;
import com.obraviva.platform.captures3d.Capture3dService;
import com.obraviva.platform.comparisons.ProjectComparisonService;
import com.obraviva.platform.developments.DevelopmentResponse;
import com.obraviva.platform.developments.DevelopmentService;
import com.obraviva.platform.developments.DevelopmentType;
import com.obraviva.platform.developments.ExpenseService;
import com.obraviva.platform.developments.UnitResponse;
import com.obraviva.platform.developments.UnitSalesStatus;
import com.obraviva.platform.developments.StageStatus;
import com.obraviva.platform.developments.UnitStageService;
import com.obraviva.platform.documents.UnitDocumentService;
import com.obraviva.platform.organizations.OrganizationMemberService;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.organizations.OrganizationRole;
import com.obraviva.platform.plans.FloorPlanService;
import com.obraviva.platform.progress.ProgressService;
import com.obraviva.platform.sharing.MyPropertyDtos.MyUnitSummary;
import com.obraviva.platform.sharing.MyPropertyService;
import com.obraviva.platform.sharing.PublicViewDtos.PublicCapture;
import com.obraviva.platform.sharing.PublicViewDtos.PublicProgressEntry;
import com.obraviva.platform.sharing.PublicViewDtos.PublicUnitView;
import com.obraviva.platform.storage.StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Datos de demostración. El seeder corre al levantar el contexto: estos tests verifican
 * lo que quedó cargado, mirado con los servicios del producto y desde cada rol.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "obraviva.storage.init-bucket=false",
        "obraviva.jobs.enabled=false",
        "obraviva.dev-seed.enabled=true",
        "obraviva.demo-seed.enabled=true"
})
class DemoDataSeederIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void splat(DynamicPropertyRegistry registry) throws IOException {
        // El almacenamiento está simulado: alcanza con que el archivo exista. Sus
        // invocaciones no se verifican porque el mock se reinicia después de cada test y
        // el seeder corre una sola vez, al levantar el contexto.
        Path file = Files.createTempFile("demo-splat-", ".spz");
        file.toFile().deleteOnExit();
        registry.add("obraviva.demo-seed.splat-path", file::toString);
    }

    @MockitoBean private StorageService storageService;

    @Autowired private DemoDataSeeder seeder;
    @Autowired private UserDirectoryService userDirectory;
    @Autowired private OrganizationMembershipService membershipService;
    @Autowired private OrganizationMemberService organizationMembers;
    @Autowired private DevelopmentService developments;
    @Autowired private UnitStageService unitStages;
    @Autowired private ExpenseService expenses;
    @Autowired private ProgressService progress;
    @Autowired private Capture3dService captures;
    @Autowired private FloorPlanService floorPlans;
    @Autowired private MyPropertyService myProperty;
    @Autowired private ProjectComparisonService comparisons;
    @Autowired private UnitDocumentService documents;

    @Test
    @DisplayName("La instalación deja al responsable como dueño de la empresa, con el encargado como miembro")
    void installationOwnsTheCompany() {
        UserSummary admin = user("admin@obraviva.local");
        UUID organizationId = aromos(admin).organizationId();

        assertThat(membershipService.isOwnerOf(admin.id(), organizationId)).isTrue();
        assertThat(organizationMembers.list(admin.id(), organizationId))
                .anyMatch(member -> member.email().equals(DemoDataSeeder.SUPERVISOR_EMAIL)
                        && member.role() == OrganizationRole.MEMBER);
    }

    @Test
    @DisplayName("El barrio queda con sus datos, seis casas en distintos puntos de obra y obras comunes")
    void barrioIsFullyLoaded() {
        UserSummary admin = user("admin@obraviva.local");
        DevelopmentResponse barrio = aromos(admin);

        assertThat(barrio.developmentType()).isEqualTo(DevelopmentType.BARRIO_CERRADO);
        assertThat(barrio.locality()).isEqualTo("Villa Allende");
        assertThat(barrio.amenities()).contains("Pileta");
        assertThat(barrio.unitCount()).isEqualTo(6);
        // Promedio de las seis casas: 68,4 / 45 / 35,2 / 17,5 / 5 / 0.
        assertThat(barrio.overallProgressPercent()).isBetween(27, 30);

        List<UnitResponse> houses = developments.listUnits(admin.id(), barrio.id());
        assertThat(houses).extracting(UnitResponse::code)
                .containsExactlyInAnyOrder("Casa 1", "Casa 2", "Casa 3", "Casa 4", "Casa 5", "Casa 6");
        assertThat(houses).allMatch(house -> house.stageCount() == 12 && house.listPrice() != null && house.hasCover());

        UnitResponse casa1 = house(houses, "Casa 1");
        assertThat(casa1.progressPercent()).isBetween(67, 69);
        assertThat(casa1.currentStageName()).isEqualTo("Revoques, contrapisos y carpetas");
        assertThat(casa1.salesStatus()).isEqualTo(UnitSalesStatus.SOLD);
        assertThat(house(houses, "Casa 2").salesStatus()).isEqualTo(UnitSalesStatus.SOLD);
        assertThat(house(houses, "Casa 3").salesStatus()).isEqualTo(UnitSalesStatus.RESERVED);
        assertThat(house(houses, "Casa 6").progressPercent()).isZero();

        assertThat(unitStages.list(admin.id(), house(houses, "Casa 2").id()))
                .anyMatch(stage -> stage.status().name().equals("DELAYED"));
        assertThat(unitStages.list(admin.id(), casa1.id()).getFirst())
                .satisfies(stage -> {
                    assertThat(stage.startedOn()).isEqualTo(DemoDataSeeder.demoDate("2026-02-02"));
                    assertThat(stage.completedOn()).isEqualTo(DemoDataSeeder.demoDate("2026-02-12"));
                    assertThat(stage.budgetAmount()).isNotNull();
                });

        assertThat(developments.listStages(admin.id(), barrio.id())).hasSize(3);
        assertThat(expenses.listByDevelopment(admin.id(), barrio.id())).hasSize(26);
        assertThat(progress.timeline(admin.id(), barrio.id())).hasSize(22)
                .anyMatch(entry -> entry.source().name().equals("AUDIO"))
                .anyMatch(entry -> entry.version() > 1)
                .anyMatch(entry -> entry.unitId() == null);
        assertThat(captures.listAvailable(admin.id(), barrio.id())).hasSize(9);
        assertThat(floorPlans.list(admin.id(), casa1.id())).hasSize(2)
                .allMatch(plan -> !plan.rooms().isEmpty());
    }

    @Test
    @DisplayName("Las fechas siguen al día del seed: ninguna etapa abierta vencida, y la única demora la declara un avance")
    void datesFollowTheSeedDay() {
        UserSummary admin = user("admin@obraviva.local");
        LocalDate today = LocalDate.now();
        List<UnitResponse> houses = developments.listUnits(admin.id(), aromos(admin).id());

        for (UnitResponse house : houses) {
            assertThat(unitStages.list(admin.id(), house.id()))
                    .as(house.code())
                    .filteredOn(stage -> stage.status() != StageStatus.COMPLETED)
                    .allSatisfy(stage -> assertThat(stage.plannedEnd()).isAfterOrEqualTo(today));
        }
        assertThat(unitStages.list(admin.id(), house(houses, "Casa 1").id()))
                .filteredOn(stage -> stage.status() == StageStatus.IN_PROGRESS)
                .singleElement()
                .satisfies(stage -> assertThat(stage.plannedEnd()).isEqualTo(today.plusDays(30)));

        // La Casa 2 es la única demorada, y lo dice su avance, con el cambio de estado.
        assertThat(houses).filteredOn(house -> unitStages.list(admin.id(), house.id()).stream()
                        .anyMatch(stage -> stage.status() == StageStatus.DELAYED))
                .extracting(UnitResponse::code)
                .containsExactly("Casa 2");
        PublicUnitView casa2 = myProperty.property(admin.id(), house(houses, "Casa 2").id()).property();
        assertThat(casa2.timeline()).filteredOn(entry -> !entry.common() && entry.stageChange() != null)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.stageName()).isEqualTo("Cubierta y techo");
                    assertThat(entry.description()).contains("La chapa se demora");
                    assertThat(entry.stageChange().statusAfter()).isEqualTo("DELAYED");
                });
    }

    @Test
    @DisplayName("La familia compradora ve su casa: sus etapas, su historia con las novedades del barrio, galería y ficha")
    void buyerSeesTheirHouse() {
        UserSummary buyer = user(DemoDataSeeder.AROMOS_BUYER_EMAIL);

        List<MyUnitSummary> units = myProperty.myUnits(buyer.id());
        assertThat(units).singleElement()
                .satisfies(unit -> {
                    assertThat(unit.developmentName()).isEqualTo(DemoDataSeeder.AROMOS_NAME);
                    assertThat(unit.unitCode()).isEqualTo(DemoDataSeeder.BUYER_HOUSE);
                });

        PublicUnitView view = myProperty.property(buyer.id(), units.getFirst().unitId()).property();
        assertThat(view.stages()).hasSize(12);
        assertThat(view.stages().getFirst().description()).isNotBlank();
        assertThat(view.stages().getFirst().completedOn()).isEqualTo(DemoDataSeeder.demoDate("2026-02-12"));
        assertThat(view.overallProgressPercent()).isBetween(67, 69);
        assertThat(view.details().modelName()).isEqualTo("Modelo Aromo");
        assertThat(view.details().bedrooms()).isEqualTo(3);
        assertThat(view.details().longDescription()).isNotBlank();
        assertThat(view.development().locality()).isEqualTo("Villa Allende");
        assertThat(view.development().amenities()).contains("Pileta");

        // Once avances de la casa y dos de las obras comunes; nada de las otras casas.
        assertThat(view.timeline()).hasSize(13);
        assertThat(view.timeline()).filteredOn(PublicProgressEntry::common).hasSize(2);
        // Siete de la casa (el frente en tres fechas, el último recorrido completo) y uno del barrio.
        assertThat(view.captures()).hasSize(8);
        assertThat(view.sectors()).containsExactly("Frente", "Living y cocina", "Dormitorio principal", "Baño");
        assertThat(view.captures()).filteredOn(PublicCapture::common).hasSize(1);
        assertThat(view.showcase()).hasSize(2);
        assertThat(view.gallery()).hasSize(13);
        assertThat(view.plans()).hasSize(2);
    }

    @Test
    @DisplayName("Los planos traen su imagen, hay proyecto y realidad, y los documentos llegan sólo a su comprador")
    void plansComparisonsAndDocuments() {
        UserSummary admin = user("admin@obraviva.local");
        UUID casa1 = house(developments.listUnits(admin.id(), aromos(admin).id()), "Casa 1").id();

        assertThat(floorPlans.list(admin.id(), casa1)).isNotEmpty()
                .allMatch(plan -> plan.imageWidthPx() != null && plan.imageHeightPx() != null);

        UserSummary buyer = user(DemoDataSeeder.AROMOS_BUYER_EMAIL);
        PublicUnitView view = myProperty.property(buyer.id(), casa1).property();
        assertThat(view.comparisons()).singleElement()
                .satisfies(comparison -> assertThat(comparison.title()).isEqualTo("Fachada desde la calle"));
        assertThat(documents.listForUnit(buyer.id(), casa1)).hasSize(2);

        UserSummary nogalBuyer = user(DemoDataSeeder.NOGAL_BUYER_EMAIL);
        MyUnitSummary nogalHouse = myProperty.myUnits(nogalBuyer.id()).getFirst();
        assertThat(nogalHouse.developmentName()).isEqualTo(DemoDataSeeder.NOGAL_NAME);
        assertThat(documents.listForUnit(nogalBuyer.id(), nogalHouse.unitId())).hasSize(2);
        assertThat(myProperty.property(nogalBuyer.id(), nogalHouse.unitId()).property().captures()).hasSize(1);
    }

    @Test
    @DisplayName("Volver a correr el seeder no duplica los desarrollos")
    void seedingIsIdempotent() {
        UserSummary admin = user("admin@obraviva.local");
        int before = developments.listForUser(admin.id()).size();
        UUID barrioId = aromos(admin).id();
        int comparisonsBefore = comparisons.list(admin.id(), barrioId).size();
        int documentsBefore = documents.listForOwner(admin.id(), barrioId).size();

        seeder.seed();

        assertThat(developments.listForUser(admin.id())).hasSize(before);
        assertThat(comparisons.list(admin.id(), barrioId)).hasSize(comparisonsBefore);
        assertThat(documents.listForOwner(admin.id(), barrioId)).hasSize(documentsBefore);
        assertThat(before).isEqualTo(2);
    }

    private UserSummary user(String email) {
        return userDirectory.findByEmail(email).orElseThrow();
    }

    private DevelopmentResponse aromos(UserSummary admin) {
        return developments.listForUser(admin.id()).stream()
                .filter(development -> development.name().equals(DemoDataSeeder.AROMOS_NAME))
                .findFirst().orElseThrow();
    }

    private static UnitResponse house(List<UnitResponse> houses, String code) {
        return houses.stream().filter(house -> house.code().equals(code)).findFirst().orElseThrow();
    }
}
