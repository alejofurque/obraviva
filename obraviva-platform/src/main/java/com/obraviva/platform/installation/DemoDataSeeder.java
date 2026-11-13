package com.obraviva.platform.installation;

import com.obraviva.platform.auth.AccountProvisioningService;
import com.obraviva.platform.auth.UserDirectoryService.UserSummary;
import com.obraviva.platform.captures3d.Capture3dService;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureRequest;
import com.obraviva.platform.comparisons.ComparisonDtos.ComparisonResponse;
import com.obraviva.platform.comparisons.ComparisonDtos.CreateComparisonRequest;
import com.obraviva.platform.comparisons.ComparisonDtos.UpdateComparisonRequest;
import com.obraviva.platform.comparisons.ProjectComparisonService;
import com.obraviva.platform.developments.CreateDevelopmentRequest;
import com.obraviva.platform.developments.CaptureSectorService;
import com.obraviva.platform.developments.CreateStageRequest;
import com.obraviva.platform.developments.DevelopmentDetailsService;
import com.obraviva.platform.developments.DevelopmentDetailsService.UpdateDevelopmentRequest;
import com.obraviva.platform.developments.DevelopmentKind;
import com.obraviva.platform.developments.DevelopmentResponse;
import com.obraviva.platform.developments.DevelopmentService;
import com.obraviva.platform.developments.DevelopmentTeamDtos.AssignSupervisorRequest;
import com.obraviva.platform.developments.DevelopmentTeamService;
import com.obraviva.platform.developments.DevelopmentType;
import com.obraviva.platform.developments.ExpenseDtos.CreateExpenseRequest;
import com.obraviva.platform.developments.ExpenseService;
import com.obraviva.platform.developments.StagePlanService;
import com.obraviva.platform.developments.StageResponse;
import com.obraviva.platform.developments.StageStatus;
import com.obraviva.platform.developments.StandardHouseStages;
import com.obraviva.platform.developments.UnitDetailsDtos.CreateUnitsBatchRequest;
import com.obraviva.platform.developments.UnitDetailsDtos.UpdateSalesSheetRequest;
import com.obraviva.platform.developments.UnitDetailsDtos.UpdateUnitDetailsRequest;
import com.obraviva.platform.developments.UnitDetailsService;
import com.obraviva.platform.developments.UnitMemberDtos.AddUnitMemberRequest;
import com.obraviva.platform.developments.UnitMemberService;
import com.obraviva.platform.developments.UnitResponse;
import com.obraviva.platform.developments.UnitSalesStatus;
import com.obraviva.platform.developments.UnitStageService;
import com.obraviva.platform.developments.UpdateStagePlanRequest;
import com.obraviva.platform.developments.UpdateStageStatusRequest;
import com.obraviva.platform.documents.DocumentDtos.RequestDocumentUploadRequest;
import com.obraviva.platform.documents.UnitDocument;
import com.obraviva.platform.documents.UnitDocumentService;
import com.obraviva.platform.media.MediaAssetResponse;
import com.obraviva.platform.media.MediaKind;
import com.obraviva.platform.media.MediaService;
import com.obraviva.platform.organizations.AddOrganizationMemberRequest;
import com.obraviva.platform.organizations.OrganizationMemberService;
import com.obraviva.platform.plans.FloorPlanDtos.CreateFloorPlanRequest;
import com.obraviva.platform.plans.FloorPlanDtos.FloorPlanResponse;
import com.obraviva.platform.plans.FloorPlanDtos.ReplaceRoomsRequest;
import com.obraviva.platform.plans.FloorPlanDtos.RoomRequest;
import com.obraviva.platform.plans.FloorPlanImageService;
import com.obraviva.platform.plans.FloorPlanService;
import com.obraviva.platform.progress.CreateProgressEntryRequest;
import com.obraviva.platform.progress.CreateProgressEntryRequest.StageUpdate;
import com.obraviva.platform.progress.ProgressService;
import com.obraviva.platform.progress.UpdateProgressEntryRequest;
import com.obraviva.platform.publication.ProgressPublicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Datos de demostración: dos desarrollos de casas para mostrar el producto funcionando.
 *
 * <ul>
 *   <li><b>Barrio Los Aromos</b>, barrio cerrado con seis casas de dos modelos, cada una en
 *   un punto distinto de su obra (de terminada la gruesa a sin empezar), con una demorada,
 *   presupuesto y gastos por etapa, obras comunes (calles, redes, SUM), fichas de venta,
 *   galerías, planos, recorridos 3D, proyecto contra realidad, documentos y la familia
 *   compradora de la Casa 1.</li>
 *   <li><b>Barrio El Nogal</b>, barrio abierto que arranca: cuatro casas, dos en obra, y el
 *   comprador de su Casa 1.</li>
 * </ul>
 *
 * <p>Todo pasa por los servicios del producto, con las mismas validaciones que la
 * interfaz: el seeder no escribe tablas. Es idempotente por nombre de desarrollo: uno que
 * ya existe en la organización no se vuelve a cargar. Cada desarrollo se carga en una
 * transacción; si algo falla, no queda a medias en la base (sí pueden quedar archivos
 * huérfanos en el almacenamiento, que no molestan). Las imágenes de los planos se generan
 * después, fuera de esa transacción, porque convertir un PDF es trabajo pesado.
 *
 * <p>Las fechas están escritas contra un día de referencia ({@link #REFERENCE}) y se
 * corren al día en que corre el seed ({@link #demoDate}): la demo muestra siempre una obra
 * al día, con la etapa en curso de cada casa terminando dentro de un mes. La única demora es
 * la de la Casa 2 de Los Aromos, declarada en su avance. Sólo toca los dos barrios de
 * demostración; ningún otro desarrollo de la organización.
 *
 * <p>Las imágenes son ilustraciones vectoriales rotuladas "Ilustración de ejemplo": no son
 * fotos de una obra real y no deben pasar por tales. Los recorridos usan una única
 * captura de muestra en todas las fechas, así que el viaje en el tiempo muestra el mismo
 * modelo: la navegación entre fechas funciona, el contenido no cambia.
 *
 * <p>Se activa con {@code obraviva.demo-seed.enabled=true}. Sólo para desarrollo: las
 * cuentas de ejemplo tienen una contraseña conocida.
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "obraviva.demo-seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    static final String AROMOS_NAME = "Barrio Los Aromos";
    static final String NOGAL_NAME = "Barrio El Nogal";

    static final String SUPERVISOR_EMAIL = "encargado@obraviva.local";
    /** Encargado asignado sólo a la Casa 1 y la Casa 2 de Barrio Los Aromos. */
    static final String HOUSE_SUPERVISOR_EMAIL = "carlos.medina@obraviva.local";
    static final String AROMOS_BUYER_EMAIL = "familia.aromos@obraviva.local";
    static final String NOGAL_BUYER_EMAIL = "comprador.nogal@obraviva.local";
    static final String BUYER_HOUSE = "Casa 1";

    private static final String SVG = "image/svg+xml";

    static final String BUNDLED_SPLAT = "demo/captura-muestra.spz";

    /** El día contra el que están escritas las fechas del seed: el "hoy" de la demo. */
    static final LocalDate REFERENCE = LocalDate.of(2026, 9, 10);

    /** Fin previsto de la etapa en curso de cada casa: un mes después de la referencia. */
    private static final String CURRENT_STAGE_END = "2026-10-10";

    /** Duración prevista en días de cada etapa estándar, en el orden de la plantilla. */
    private static final int[] STAGE_DAYS = {10, 30, 45, 40, 25, 25, 20, 35, 20, 25, 20, 10};

    /** Qué se paga en cada etapa estándar, para que los gastos se lean como reales. */
    private static final String[] STAGE_EXPENSES = {
            "Cerco perimetral, obrador y replanteo",
            "Excavación, hormigón elaborado y acero de fundaciones",
            "Hormigón, acero y encofrado de columnas, vigas y losa",
            "Ladrillo cerámico, cemento y mano de obra de mampostería",
            "Estructura de la cubierta, chapa y aislación",
            "Cañerías de agua, desagües y gas; artefactos embutidos",
            "Cañerías, cableado y tablero eléctrico",
            "Revoques, contrapisos, carpetas y mano de obra",
            "Aberturas de aluminio con DVH y carpinterías",
            "Porcelanatos, revestimientos de baños y cocina",
            "Pintura interior y exterior, terminaciones",
            "Limpieza de obra y entrega"
    };

    private final InstallationSeeder installation;
    private final AccountProvisioningService accountProvisioning;
    private final OrganizationMemberService organizationMembers;
    private final DevelopmentService developments;
    private final DevelopmentDetailsService developmentDetails;
    private final UnitStageService unitStages;
    private final StagePlanService stagePlans;
    private final ExpenseService expenses;
    private final UnitDetailsService unitDetails;
    private final UnitMemberService unitMembers;
    private final ProgressService progress;
    private final ProgressPublicationService publication;
    private final MediaService media;
    private final Capture3dService captures;
    private final FloorPlanService floorPlans;
    private final DevelopmentTeamService team;
    private final FloorPlanImageService planImages;
    private final CaptureSectorService captureSectors;
    private final ProjectComparisonService comparisons;
    private final UnitDocumentService documents;
    private final TransactionTemplate transaction;
    private final String demoPassword;
    private final String splatPath;

    public DemoDataSeeder(
            InstallationSeeder installation,
            AccountProvisioningService accountProvisioning,
            OrganizationMemberService organizationMembers,
            DevelopmentService developments,
            DevelopmentDetailsService developmentDetails,
            UnitStageService unitStages,
            StagePlanService stagePlans,
            ExpenseService expenses,
            UnitDetailsService unitDetails,
            UnitMemberService unitMembers,
            ProgressService progress,
            ProgressPublicationService publication,
            MediaService media,
            Capture3dService captures,
            FloorPlanService floorPlans,
            DevelopmentTeamService team,
            FloorPlanImageService planImages,
            CaptureSectorService captureSectors,
            ProjectComparisonService comparisons,
            UnitDocumentService documents,
            TransactionTemplate transaction,
            @Value("${obraviva.demo-seed.password}") String demoPassword,
            @Value("${obraviva.demo-seed.splat-path:}") String splatPath) {
        this.installation = installation;
        this.accountProvisioning = accountProvisioning;
        this.organizationMembers = organizationMembers;
        this.developments = developments;
        this.developmentDetails = developmentDetails;
        this.unitStages = unitStages;
        this.stagePlans = stagePlans;
        this.expenses = expenses;
        this.unitDetails = unitDetails;
        this.unitMembers = unitMembers;
        this.progress = progress;
        this.publication = publication;
        this.media = media;
        this.captures = captures;
        this.floorPlans = floorPlans;
        this.team = team;
        this.planImages = planImages;
        this.captureSectors = captureSectors;
        this.comparisons = comparisons;
        this.documents = documents;
        this.transaction = transaction;
        this.demoPassword = demoPassword;
        this.splatPath = splatPath;
    }

    /** Un fallo de la demostración se loguea y no impide levantar la aplicación. */
    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (RuntimeException e) {
            log.error("No se pudieron cargar los datos de demostración", e);
        }
    }

    void seed() {
        UserSummary admin = installation.ensureAdmin();
        UUID organizationId = installation.ensureOrganization(admin.id());
        UserSummary supervisor = ensureSupervisor(admin.id(), organizationId);
        Path splat = resolveSplat();

        List<String> existing = developments.listForUser(admin.id()).stream()
                .filter(development -> development.organizationId().equals(organizationId))
                .map(DevelopmentResponse::name)
                .toList();

        if (!existing.contains(AROMOS_NAME)) {
            transaction.executeWithoutResult(status ->
                    seedAromos(admin.id(), supervisor.id(), organizationId, splat));
            log.info("Desarrollo de demostración cargado: {}", AROMOS_NAME);
        }
        if (!existing.contains(NOGAL_NAME)) {
            transaction.executeWithoutResult(status ->
                    seedNogal(admin.id(), supervisor.id(), organizationId, splat));
            log.info("Desarrollo de demostración cargado: {}", NOGAL_NAME);
        }

        // Los planos reciben su imagen fuera de la transacción de carga.
        for (DevelopmentResponse development : developments.listForUser(admin.id())) {
            if (development.organizationId().equals(organizationId)
                    && (development.name().equals(AROMOS_NAME) || development.name().equals(NOGAL_NAME))) {
                List<UnitResponse> units = developments.listUnits(admin.id(), development.id());
                for (UnitResponse unit : units) {
                    addPlanImages(admin.id(), unit.id());
                    // Barrios cargados antes de los sectores 3D: reciben los estándar.
                    if (captureSectors.list(admin.id(), unit.id()).isEmpty()) {
                        captureSectors.replace(admin.id(), unit.id(), new CaptureSectorService.ReplaceSectorsRequest(
                                CaptureSectorService.STANDARD_SECTORS.stream()
                                        .map(name -> new CaptureSectorService.SectorChange(name, null, null))
                                        .toList()));
                    }
                }
                if (development.name().equals(AROMOS_NAME)) {
                    ensureHouseSupervisor(admin.id(), organizationId, development.id(), units);
                }
            }
        }
    }

    /** El encargado de casas puntuales, también en un barrio cargado antes de existir la asignación por casa. */
    private void ensureHouseSupervisor(UUID adminId, UUID organizationId, UUID barrio, List<UnitResponse> units) {
        UserSummary houseSupervisor = ensureMember(adminId, organizationId, HOUSE_SUPERVISOR_EMAIL, "Carlos Medina");
        boolean assigned = team.team(adminId, barrio).assigned().stream()
                .anyMatch(person -> person.userId().equals(houseSupervisor.id()));
        List<UUID> houses = units.stream()
                .filter(unit -> unit.code().equals("Casa 1") || unit.code().equals("Casa 2"))
                .map(UnitResponse::id)
                .toList();
        if (!assigned && !houses.isEmpty()) {
            team.assign(adminId, barrio, new AssignSupervisorRequest(houseSupervisor.id(), houses));
        }
    }

    // --- Barrio Los Aromos ---

    private void seedAromos(UUID adminId, UUID supervisorId, UUID organizationId, Path splat) {
        UUID barrio = developments.create(adminId, new CreateDevelopmentRequest(
                organizationId, AROMOS_NAME, "Camino a La Calera km 4,5", DevelopmentKind.MULTI_UNIT)).id();
        developmentDetails.update(adminId, barrio, new UpdateDevelopmentRequest(
                AROMOS_NAME,
                "Camino a La Calera km 4,5",
                "Barrio cerrado de 24 lotes al pie de las sierras, a diez minutos del centro de Villa Allende. "
                        + "Casas de dos modelos pensadas para familias, con calles arboladas, espacios verdes y un "
                        + "club house con pileta. La primera etapa son seis casas llave en mano.",
                "Villa Allende",
                "Córdoba",
                DevelopmentType.BARRIO_CERRADO,
                new BigDecimal("42000"),
                demoDate("2026-01-12"),
                YearMonth.from(demoDate("2027-06-15")),
                List.of("Seguridad 24 h", "Club house con SUM", "Pileta", "Plaza de juegos", "Senderos aeróbicos"),
                List.of("Agua corriente", "Cloacas", "Gas natural", "Electricidad subterránea", "Fibra óptica"),
                "Estudio Paredes Luján Arquitectos",
                "Constructora Sierras Chicas",
                "EXP-2025-01873"));
        developmentDetails.importCover(adminId, barrio, DemoAssets.renderPng(), "image/png");
        team.assign(adminId, barrio, new AssignSupervisorRequest(supervisorId));

        // Obras comunes del barrio: lo que es de todos y no de una casa.
        List<UUID> common = new ArrayList<>();
        common.add(commonStage(adminId, barrio, 1, "Apertura de calles y cordón cuneta",
                "Trazado de calles internas, compactación, cordón cuneta y desagües pluviales.",
                30, "96000", StageStatus.COMPLETED, 100, "2026-01-12", "2026-03-06"));
        common.add(commonStage(adminId, barrio, 2, "Redes de agua, cloacas, gas y electricidad",
                "Tendido de redes de servicios hasta el frente de cada lote, con conexión a la red pública.",
                40, "148000", StageStatus.IN_PROGRESS, 70, "2026-02-16", null));
        common.add(commonStage(adminId, barrio, 3, "Portal de acceso, club house y pileta",
                "Portal con guardia, SUM de 180 m², vestuarios y pileta de 20 x 8 m.",
                30, "124000", StageStatus.IN_PROGRESS, 35, "2026-06-01", null));
        expense(adminId, barrio, null, common.get(0), demoDate("2026-02-20"), "58300", "Movimiento de suelo y cordón cuneta de calles internas");
        expense(adminId, barrio, null, common.get(0), demoDate("2026-03-04"), "39100", "Desagües pluviales y alcantarillas");
        expense(adminId, barrio, null, common.get(1), demoDate("2026-06-12"), "71800", "Red de agua y cloacas, primera etapa");
        expense(adminId, barrio, null, common.get(2), demoDate("2026-08-14"), "36500", "Estructura del club house");
        commonEntry(adminId, barrio, common.get(0), "2026-03-05",
                "Terminado el cordón cuneta de todas las calles internas. Se habilitó el ingreso de camiones a los lotes de la primera etapa.",
                "obra-01-terreno.svg");
        commonEntry(adminId, barrio, common.get(2), "2026-08-28",
                "Estructura del club house terminada y pileta excavada. Empieza la mampostería del SUM.",
                "obra-03-estructura.svg");

        List<UnitResponse> houses = developments.createUnits(adminId, barrio, new CreateUnitsBatchRequest("Casa", 1, 6, true));
        HouseModel aromo = HouseModel.AROMO;
        HouseModel espinillo = HouseModel.ESPINILLO;
        List<HouseSeed> seeds = List.of(
                new HouseSeed(aromo, "2026-02-02", 7, 7, 40, false, UnitSalesStatus.AVAILABLE, "275000", "Norte", "600"),
                new HouseSeed(aromo, "2026-03-02", 4, 4, 50, true, UnitSalesStatus.SOLD, "279000", "Noreste", "620"),
                new HouseSeed(espinillo, "2026-04-06", 3, 3, 60, false, UnitSalesStatus.RESERVED, "195000", "Norte", "450"),
                new HouseSeed(aromo, "2026-06-01", 2, 2, 30, false, UnitSalesStatus.AVAILABLE, "284000", "Oeste", "610"),
                new HouseSeed(espinillo, "2026-08-17", 1, 1, 20, false, UnitSalesStatus.AVAILABLE, "199000", "Este", "455"),
                new HouseSeed(espinillo, "2026-10-05", 0, -1, 0, false, UnitSalesStatus.AVAILABLE, "199000", "Norte", "470"));

        List<List<UUID>> stagesByHouse = new ArrayList<>();
        for (int i = 0; i < houses.size(); i++) {
            stagesByHouse.add(loadHouse(adminId, barrio, houses.get(i), seeds.get(i)));
        }

        // Casa 1: la obra completa que sigue la familia compradora.
        UUID casa1 = houses.get(0).id();
        List<UUID> s1 = stagesByHouse.get(0);
        entry(adminId, barrio, casa1, s1.get(0), "2026-02-10",
                "Limpieza del terreno, cerco perimetral y replanteo con estacas. Se instaló el obrador y la conexión provisoria de agua.",
                "obra-01-terreno.svg");
        entry(adminId, barrio, casa1, s1.get(1), "2026-02-24",
                "Excavación de zapatas y vigas de fundación terminada. Los niveles se verificaron contra el estudio de suelos.",
                "obra-01-terreno.svg");
        entry(adminId, barrio, casa1, s1.get(1), "2026-03-12",
                "Fundaciones hormigonadas y platea de la cochera terminada. Se tomaron probetas para el laboratorio.",
                "obra-02-fundaciones.svg");
        entry(adminId, barrio, casa1, s1.get(2), "2026-04-10",
                "Columnas de planta baja hormigonadas. El encofrado de la losa sobre planta baja está en curso.",
                "obra-03-estructura.svg");
        UUID slab = entry(adminId, barrio, casa1, s1.get(2), "2026-04-27",
                "Losa sobre planta baja hormigonada. Se desencofra a los 21 días.",
                "obra-03-estructura.svg");
        // Corrección: el hilo conserva la versión original y la línea de tiempo muestra la vigente.
        progress.createCorrection(adminId, slab, new UpdateProgressEntryRequest(s1.get(2), demoDate("2026-04-27"),
                "Losa sobre planta baja hormigonada con H-25. Por las bajas temperaturas, el desencofrado se reprogramó a los 28 días."));
        entry(adminId, barrio, casa1, s1.get(3), "2026-05-20",
                "Mampostería de planta baja al 60 %: muros exteriores de ladrillo cerámico y tabiques del sector de dormitorios.",
                "obra-04-mamposteria.svg");
        // Avance originado en un audio del encargado, ya revisado.
        UUID audioEntry = progress.publishFromAudio(organizationId, barrio, s1.get(3), demoDate("2026-06-05"),
                "Se terminaron los muros de planta alta y los dinteles de las aberturas del frente.", supervisorId, casa1).id();
        media.importFile(supervisorId, barrio, audioEntry, null, "obra-04-mamposteria.svg", SVG, image("obra-04-mamposteria.svg"));
        entry(adminId, barrio, casa1, s1.get(4), "2026-06-30",
                "Cubierta terminada: estructura de madera, aislación hidrófuga y térmica y chapa prepintada.",
                "obra-05-cubierta.svg");
        entry(supervisorId, barrio, casa1, s1.get(5), "2026-07-24",
                "Cañerías de agua fría y caliente embutidas en baños y cocina. Prueba hidráulica aprobada, sin pérdidas.",
                "obra-06-instalaciones.svg");
        entry(supervisorId, barrio, casa1, s1.get(6), "2026-08-12",
                "Cañerías eléctricas colocadas en toda la casa y cableado de planta baja terminado.", null);
        entry(supervisorId, barrio, casa1, s1.get(7), "2026-09-08",
                "Revoque grueso interior de planta baja terminado. Esta semana arrancan los contrapisos.", null);

        plan(adminId, casa1, "Planta baja", "12", "10", List.of(
                room("Living y comedor", 0, 0, 7, 5),
                room("Cocina", 7, 0, 12, 5),
                room("Dormitorio principal", 0, 5, 5, 10),
                room("Baño", 5, 5, 7.5, 8),
                room("Circulación", 5, 8, 7.5, 10),
                room("Dormitorio 2", 7.5, 5, 12, 10)));
        plan(adminId, casa1, "Planta alta", "12", "6", List.of(
                room("Dormitorio 3", 0, 0, 5, 6),
                room("Estudio", 5, 0, 9, 6),
                room("Baño", 9, 0, 12, 3),
                room("Terraza", 9, 3, 12, 6)));

        media.listAvailable(adminId, barrio).stream()
                .filter(asset -> asset.kind() == MediaKind.PHOTO && casa1.equals(asset.unitId())
                        && asset.progressEntryId() != null && asset.originalFilename().equals("obra-05-cubierta.svg"))
                .map(MediaAssetResponse::id)
                .findFirst()
                .ifPresent(photoId -> {
                    ComparisonResponse comparison = comparisons.create(
                            adminId, barrio, new CreateComparisonRequest("Fachada desde la calle", casa1));
                    comparisons.importRender(adminId, comparison.id(), DemoAssets.renderPng(), "image/png");
                    comparisons.update(adminId, comparison.id(), new UpdateComparisonRequest("Fachada desde la calle", casa1, photoId));
                });

        document(adminId, barrio, null, "Reglamento interno del barrio", UnitDocument.Category.REGLAMENTO,
                List.of("Reglamento de ejemplo para mostrar cómo el comprador consulta los documentos del desarrollo.",
                        "Uso de espacios comunes, expensas, obras particulares y convivencia se describirían en este documento."));
        document(adminId, barrio, casa1, "Boleto de compraventa Casa 1", UnitDocument.Category.CONTRATO,
                List.of("Boleto de ejemplo de la Casa 1. No es un contrato real ni tiene validez legal."));

        // Casa 2: demorada en la cubierta, con el gasto por encima del avance.
        UUID casa2 = houses.get(1).id();
        List<UUID> s2 = stagesByHouse.get(1);
        entry(adminId, barrio, casa2, s2.get(1), "2026-03-20",
                "Fundaciones hormigonadas. El estudio de suelos pidió vigas de fundación más profundas en el fondo del lote.",
                "obra-02-fundaciones.svg");
        entry(adminId, barrio, casa2, s2.get(2), "2026-05-20",
                "Estructura de planta baja y losa terminadas.", "obra-03-estructura.svg");
        entry(supervisorId, barrio, casa2, s2.get(3), "2026-07-03",
                "Mampostería de planta alta terminada.", "obra-04-mamposteria.svg");
        entry(adminId, barrio, casa2, s2.get(4), "2026-08-18",
                "Estructura de la cubierta montada. La chapa se demora porque el proveedor no entregó a tiempo; la colocación se reprograma para dentro de tres semanas.",
                "obra-05-cubierta.svg", new StageUpdate(50, StageStatus.DELAYED));

        UUID casa3 = houses.get(2).id();
        List<UUID> s3 = stagesByHouse.get(2);
        entry(adminId, barrio, casa3, s3.get(1), "2026-05-10",
                "Fundaciones terminadas y aprobadas por la dirección técnica.", "obra-02-fundaciones.svg");
        entry(supervisorId, barrio, casa3, s3.get(3), "2026-08-20",
                "Muros exteriores levantados; faltan los tabiques del sector de dormitorios.", "obra-04-mamposteria.svg");

        UUID casa4 = houses.get(3).id();
        List<UUID> s4 = stagesByHouse.get(3);
        entry(adminId, barrio, casa4, s4.get(1), "2026-07-08",
                "Fundaciones hormigonadas.", "obra-02-fundaciones.svg");
        entry(supervisorId, barrio, casa4, s4.get(2), "2026-09-02",
                "Columnas de planta baja hormigonadas; se arma el encofrado de la losa.", null);

        UUID casa5 = houses.get(4).id();
        entry(supervisorId, barrio, casa5, stagesByHouse.get(4).get(0), "2026-08-25",
                "Terreno limpio, cerco y replanteo listos. Arranca la excavación.", "obra-01-terreno.svg");

        if (splat != null) {
            // Casa 1 se grabó por sectores (los estándar de la plantilla): el frente en cada
            // fecha, el interior a medida que hubo interior, y el último recorrido completo.
            capture(adminId, barrio, casa1, s1.get(2), "2026-04-12", "Frente", "Estructura de planta baja recién desencofrada", splat);
            capture(adminId, barrio, casa1, s1.get(3), "2026-05-22", "Frente", "Muros de planta baja levantados", splat);
            capture(adminId, barrio, casa1, s1.get(3), "2026-05-22", "Living y cocina", "Muros del living y la cocina", splat);
            for (String sector : List.of("Frente", "Living y cocina", "Dormitorio principal", "Baño")) {
                capture(supervisorId, barrio, casa1, s1.get(7), "2026-09-05", sector, "Instalaciones embutidas y revoque grueso", splat);
            }
            capture(adminId, barrio, casa2, s2.get(3), "2026-07-06", "Frente", "Mampostería de planta alta terminada", splat);
            capture(adminId, barrio, null, common.get(2), "2026-08-20", "Club house", "Estructura del club house y la pileta", splat);
        }

        ensureAccount(AROMOS_BUYER_EMAIL, "Lucía Rinaldi");
        unitMembers.add(adminId, casa1, new AddUnitMemberRequest(AROMOS_BUYER_EMAIL, "Lucía Rinaldi"));

    }

    // --- Barrio El Nogal ---

    private void seedNogal(UUID adminId, UUID supervisorId, UUID organizationId, Path splat) {
        UUID barrio = developments.create(adminId, new CreateDevelopmentRequest(
                organizationId, NOGAL_NAME, "Av. Los Nogales 2300", DevelopmentKind.MULTI_UNIT)).id();
        developmentDetails.update(adminId, barrio, new UpdateDevelopmentRequest(
                NOGAL_NAME,
                "Av. Los Nogales 2300",
                "Barrio abierto de casas compactas en Mendiolaza, con servicios completos y a cinco minutos de colegios y "
                        + "comercios. Primera etapa de cuatro casas del modelo Espinillo.",
                "Mendiolaza",
                "Córdoba",
                DevelopmentType.BARRIO_ABIERTO,
                new BigDecimal("9800"),
                demoDate("2026-06-15"),
                YearMonth.from(demoDate("2027-09-15")),
                List.of("Plaza central", "Bicisenda"),
                List.of("Agua corriente", "Gas natural", "Electricidad", "Fibra óptica"),
                "Estudio Paredes Luján Arquitectos",
                "Constructora Sierras Chicas",
                "EXP-2026-00412"));
        developmentDetails.importCover(adminId, barrio, DemoAssets.renderPng(), "image/png");
        team.assign(adminId, barrio, new AssignSupervisorRequest(supervisorId));

        List<UnitResponse> houses = developments.createUnits(adminId, barrio, new CreateUnitsBatchRequest("Casa", 1, 4, true));
        List<HouseSeed> seeds = List.of(
                new HouseSeed(HouseModel.ESPINILLO, "2026-06-15", 2, 2, 50, false, UnitSalesStatus.AVAILABLE, "192000", "Norte", "360"),
                new HouseSeed(HouseModel.ESPINILLO, "2026-07-20", 1, 1, 40, false, UnitSalesStatus.RESERVED, "194000", "Noreste", "360"),
                new HouseSeed(HouseModel.ESPINILLO, "2026-10-01", 0, -1, 0, false, UnitSalesStatus.AVAILABLE, "196000", "Sur", "380"),
                new HouseSeed(HouseModel.ESPINILLO, "2026-11-02", 0, -1, 0, false, UnitSalesStatus.AVAILABLE, "196000", "Norte", "380"));
        List<List<UUID>> stagesByHouse = new ArrayList<>();
        for (int i = 0; i < houses.size(); i++) {
            stagesByHouse.add(loadHouse(adminId, barrio, houses.get(i), seeds.get(i)));
        }

        UUID casa1 = houses.get(0).id();
        List<UUID> s1 = stagesByHouse.get(0);
        entry(adminId, barrio, casa1, s1.get(0), "2026-06-22",
                "Replanteo y obrador listos.", "obra-01-terreno.svg");
        entry(adminId, barrio, casa1, s1.get(1), "2026-07-20",
                "Platea de fundación hormigonada.", "obra-02-fundaciones.svg");
        entry(supervisorId, barrio, casa1, s1.get(2), "2026-09-01",
                "Columnas y vigas de encadenado a mitad de camino.", "obra-03-estructura.svg");
        entry(supervisorId, barrio, houses.get(1).id(), stagesByHouse.get(1).get(1), "2026-08-20",
                "Excavación de fundaciones terminada.", "obra-01-terreno.svg");

        plan(adminId, casa1, "Planta única", "14", "9", List.of(
                room("Living y comedor", 0, 0, 7, 5),
                room("Cocina", 7, 0, 10, 5),
                room("Galería", 10, 0, 14, 5),
                room("Dormitorio principal", 0, 5, 5, 9),
                room("Baño", 5, 5, 8, 9),
                room("Dormitorio 2", 8, 5, 14, 9)));

        document(adminId, barrio, null, "Memoria descriptiva Modelo Espinillo", UnitDocument.Category.MEMORIA,
                List.of("Platea de hormigón armado, mampostería de ladrillo cerámico y cubierta de chapa con aislación.",
                        "Aberturas de aluminio con doble vidriado hermético. Calefacción por radiadores."));
        document(adminId, barrio, casa1, "Boleto de compraventa Casa 1", UnitDocument.Category.CONTRATO,
                List.of("Boleto de ejemplo de la Casa 1 de Barrio El Nogal. No es un contrato real ni tiene validez legal."));

        if (splat != null) {
            capture(supervisorId, barrio, casa1, s1.get(2), "2026-09-03", "Frente", "Estructura en curso", splat);
        }

        ensureAccount(NOGAL_BUYER_EMAIL, "Tomás Ferreyra");
        unitMembers.add(adminId, casa1, new AddUnitMemberRequest(NOGAL_BUYER_EMAIL, "Tomás Ferreyra"));
    }

    // --- Una casa ---

    /** Los dos modelos de casa del desarrollista, con lo que necesita su ficha de venta. */
    private enum HouseModel {
        AROMO("Modelo Aromo", "186", "32", 3, 2, 1, 5, 2, 2,
                "Casa de dos plantas con galería y parrilla",
                "Casa de dos plantas pensada para una familia que recibe. En planta baja, living y comedor integrados "
                        + "con la cocina y abiertos a la galería con parrilla; dormitorio principal en suite. En planta alta, "
                        + "dos dormitorios, estudio y una terraza con vista a las sierras.",
                "Pisos de porcelanato 60 x 60, aberturas de aluminio con DVH, losa radiante, mesadas de granito y "
                        + "grifería monocomando.",
                List.of("Galería con parrilla", "Cochera doble", "Losa radiante", "Aberturas con DVH", "Terraza"),
                "210000", "casa-portada-render.svg"),
        ESPINILLO("Modelo Espinillo", "128", "24", 2, 1, 1, 4, 1, 1,
                "Casa de una planta, compacta y luminosa",
                "Casa de una sola planta, de fácil mantenimiento. Living, comedor y cocina en un mismo espacio "
                        + "con salida a la galería; dos dormitorios con placard y baño completo, más toilette de "
                        + "recepción.",
                "Pisos de porcelanato, aberturas de aluminio con DVH, calefacción por radiadores y revestimiento de piedra en el frente.",
                List.of("Galería", "Cochera cubierta", "Frente de piedra", "Aberturas con DVH"),
                "150000", "casa-modelo-espinillo.svg");

        final String name;
        final String coveredAreaM2;
        final String semiCoveredAreaM2;
        final int bedrooms;
        final int bathrooms;
        final int toilettes;
        final int rooms;
        final int floors;
        final int parkingSpaces;
        final String shortDescription;
        final String longDescription;
        final String finishes;
        final List<String> features;
        final String budget;
        final String cover;

        HouseModel(String name, String coveredAreaM2, String semiCoveredAreaM2, int bedrooms, int bathrooms,
                   int toilettes, int rooms, int floors, int parkingSpaces, String shortDescription,
                   String longDescription, String finishes, List<String> features, String budget, String cover) {
            this.name = name;
            this.coveredAreaM2 = coveredAreaM2;
            this.semiCoveredAreaM2 = semiCoveredAreaM2;
            this.bedrooms = bedrooms;
            this.bathrooms = bathrooms;
            this.toilettes = toilettes;
            this.rooms = rooms;
            this.floors = floors;
            this.parkingSpaces = parkingSpaces;
            this.shortDescription = shortDescription;
            this.longDescription = longDescription;
            this.finishes = finishes;
            this.features = features;
            this.budget = budget;
            this.cover = cover;
        }
    }

    /**
     * Estado de la obra de una casa: cuántas etapas terminadas, cuál está en curso
     * ({@code current} -1 si no arrancó) y con qué avance.
     */
    private record HouseSeed(HouseModel model, String start, int completed, int current, int currentProgress,
                             boolean delayed, UnitSalesStatus salesStatus, String priceUsd, String orientation,
                             String lotAreaM2) {
    }

    /**
     * Ficha, portada, galería y etapas de una casa: fechas previstas en secuencia desde el
     * inicio, las terminadas con sus fechas reales, presupuesto por peso y gastos.
     */
    private List<UUID> loadHouse(UUID adminId, UUID developmentId, UnitResponse house, HouseSeed seed) {
        HouseModel model = seed.model();
        UUID unitId = house.id();
        LocalDate start = demoDate(seed.start());
        List<LocalDate> plannedEnds = plannedEnds(start, seed.current());
        LocalDate delivery = plannedEnds.getLast();

        unitDetails.updateDetails(adminId, unitId, new UpdateUnitDetailsRequest(
                new BigDecimal(model.coveredAreaM2), model.bedrooms, model.bathrooms, model.features, YearMonth.from(delivery)));
        unitDetails.updateSalesSheet(adminId, unitId, new UpdateSalesSheetRequest(
                model.name, new BigDecimal(seed.lotAreaM2()), new BigDecimal(model.coveredAreaM2),
                new BigDecimal(model.semiCoveredAreaM2), model.rooms, model.toilettes, model.floors, model.parkingSpaces,
                seed.orientation(), model.longDescription, model.finishes, new BigDecimal(seed.priceUsd()), "USD"));
        developments.updateUnit(adminId, unitId,
                new com.obraviva.platform.developments.UnitDetailsDtos.UpdateUnitRequest(house.code(), model.shortDescription));
        unitDetails.importCover(adminId, unitId, "portada.svg", SVG, image(model.cover));
        media.importFile(adminId, developmentId, null, unitId, model.cover, SVG, image(model.cover));
        media.importFile(adminId, developmentId, null, unitId, "casa-interior-living.svg", SVG, image("casa-interior-living.svg"));
        if (seed.salesStatus() != UnitSalesStatus.AVAILABLE) {
            unitDetails.changeSalesStatus(adminId, unitId, seed.salesStatus());
        }

        List<StageResponse> stages = unitStages.list(adminId, unitId);
        BigDecimal total = new BigDecimal(model.budget);
        List<UUID> ids = new ArrayList<>();
        LocalDate plannedStart = start;
        for (int i = 0; i < stages.size(); i++) {
            StageResponse stage = stages.get(i);
            LocalDate plannedEnd = plannedEnds.get(i);
            BigDecimal budget = total.multiply(BigDecimal.valueOf(StandardHouseStages.STAGES.get(i).weight()))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            boolean current = i == seed.current();
            stagePlans.updatePlan(adminId, stage.id(), new UpdateStagePlanRequest(
                    current ? seed.currentProgress() : null, null, null, budget, null, null, plannedStart, plannedEnd, null));

            if (i < seed.completed()) {
                status(adminId, stage.id(), StageStatus.IN_PROGRESS, plannedStart);
                status(adminId, stage.id(), StageStatus.COMPLETED, plannedEnd);
                BigDecimal factor = new BigDecimal(new String[]{"0.96", "1.02", "1.05", "0.98", "1.01", "0.97"}[i % 6]);
                expense(adminId, developmentId, unitId, stage.id(), plannedEnd.minusDays(3),
                        budget.multiply(factor).setScale(2, RoundingMode.HALF_UP).toPlainString(), STAGE_EXPENSES[i]);
            } else if (current) {
                // La demora, si la hay, la declara un avance de la casa, como en la obra.
                status(adminId, stage.id(), StageStatus.IN_PROGRESS, plannedStart);
                // Una etapa demorada ya gastó más de lo que avanzó: el semáforo lo muestra.
                BigDecimal spentShare = BigDecimal.valueOf(seed.currentProgress() * (seed.delayed() ? 16 : 10))
                        .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
                expense(adminId, developmentId, unitId, stage.id(), plannedStart.plusDays(5),
                        budget.multiply(spentShare).setScale(2, RoundingMode.HALF_UP).toPlainString(), STAGE_EXPENSES[i]);
            }
            ids.add(stage.id());
            plannedStart = plannedEnd;
        }
        return ids;
    }

    /**
     * Fin previsto de cada etapa: en secuencia desde el inicio con su duración, salvo la
     * etapa en curso, que termina un mes después de la referencia; las siguientes la siguen.
     */
    private static List<LocalDate> plannedEnds(LocalDate start, int current) {
        List<LocalDate> ends = new ArrayList<>();
        LocalDate end = start;
        for (int i = 0; i < STAGE_DAYS.length; i++) {
            LocalDate byDuration = end.plusDays(STAGE_DAYS[i]);
            LocalDate target = demoDate(CURRENT_STAGE_END);
            end = i == current && target.isAfter(end) ? target : byDuration;
            ends.add(end);
        }
        return ends;
    }

    /** Una fecha escrita contra {@link #REFERENCE}, corrida al día en que corre el seed. */
    public static LocalDate demoDate(String date) {
        return LocalDate.parse(date).plusDays(ChronoUnit.DAYS.between(REFERENCE, LocalDate.now()));
    }

    // --- Piezas ---

    private UUID commonStage(UUID adminId, UUID developmentId, int order, String name, String description, int weight,
                             String budget, StageStatus status, int progressPercent, String startedOn, String completedOn) {
        StageResponse stage = developments.createStage(adminId, developmentId, new CreateStageRequest(name, order));
        stagePlans.updatePlan(adminId, stage.id(), new UpdateStagePlanRequest(
                status == StageStatus.COMPLETED ? null : progressPercent, null, weight, new BigDecimal(budget), null,
                description, null, null, null));
        status(adminId, stage.id(), StageStatus.IN_PROGRESS, demoDate(startedOn));
        if (status == StageStatus.COMPLETED) {
            status(adminId, stage.id(), StageStatus.COMPLETED, demoDate(completedOn));
        }
        return stage.id();
    }

    private void status(UUID userId, UUID stageId, StageStatus status, LocalDate on) {
        developments.updateStageStatus(userId, stageId, new UpdateStageStatusRequest(status, on));
    }

    private UserSummary ensureSupervisor(UUID adminId, UUID organizationId) {
        return ensureMember(adminId, organizationId, SUPERVISOR_EMAIL, "Martín Sosa");
    }

    /** Encargado de la empresa: la cuenta y su lugar en la organización, si todavía no los tiene. */
    private UserSummary ensureMember(UUID adminId, UUID organizationId, String email, String fullName) {
        UserSummary supervisor = ensureAccount(email, fullName);
        boolean member = organizationMembers.list(adminId, organizationId).stream()
                .anyMatch(existing -> existing.userId().equals(supervisor.id()));
        if (!member) {
            organizationMembers.add(adminId, organizationId, new AddOrganizationMemberRequest(email, fullName));
        }
        return supervisor;
    }

    /** Cuenta con la contraseña de demostración; si ya existía, conserva la suya. */
    private UserSummary ensureAccount(String email, String fullName) {
        return accountProvisioning.ensureAccount(email, fullName, demoPassword);
    }

    private void expense(UUID userId, UUID developmentId, UUID unitId, UUID stageId, LocalDate date, String amount, String description) {
        expenses.create(userId, developmentId, new CreateExpenseRequest(
                stageId, date, new BigDecimal(amount), description, unitId));
    }

    /** Avance de una casa, con ilustración si {@code imageName} no es nulo. */
    private UUID entry(UUID userId, UUID developmentId, UUID unitId, UUID stageId, String date, String description, String imageName) {
        return entry(userId, developmentId, unitId, stageId, date, description, imageName, null);
    }

    /** Avance que además cambia el avance o el estado de su etapa, como "Publicar avance". */
    private UUID entry(UUID userId, UUID developmentId, UUID unitId, UUID stageId, String date, String description,
                       String imageName, StageUpdate stageUpdate) {
        CreateProgressEntryRequest request = new CreateProgressEntryRequest(stageId, demoDate(date), description, unitId);
        // Con cambio de etapa, por la misma publicación atómica que "Publicar avance": guarda el antes y el después.
        UUID entryId = stageUpdate == null
                ? progress.create(userId, developmentId, request).id()
                : publication.publish(userId, developmentId, new CreateProgressEntryRequest(
                        stageId, request.entryDate(), description, unitId, null, stageUpdate)).id();
        if (imageName != null) {
            media.importFile(userId, developmentId, entryId, null, imageName, SVG, image(imageName));
        }
        return entryId;
    }

    private void commonEntry(UUID userId, UUID developmentId, UUID stageId, String date, String description, String imageName) {
        entry(userId, developmentId, null, stageId, date, description, imageName);
    }

    private void plan(UUID adminId, UUID unitId, String name, String widthM, String depthM, List<RoomRequest> rooms) {
        UUID planId = floorPlans.create(adminId, unitId,
                new CreateFloorPlanRequest(name, new BigDecimal(widthM), new BigDecimal(depthM))).id();
        floorPlans.replaceRooms(adminId, planId, new ReplaceRoomsRequest(rooms));
    }

    /** Cada planta sin imagen recibe un plano en PDF dibujado con sus propios ambientes. */
    private void addPlanImages(UUID adminId, UUID unitId) {
        for (FloorPlanResponse plan : floorPlans.list(adminId, unitId)) {
            if (plan.imageWidthPx() != null || plan.rooms().isEmpty()) {
                continue;
            }
            Path pdf = DemoAssets.floorPlanPdf(plan.widthM().doubleValue(), plan.depthM().doubleValue(),
                    plan.rooms().stream().map(room -> new DemoAssets.DemoRoom(room.name(), room.polygon())).toList());
            planImages.importPdf(adminId, plan.id(), pdf);
        }
    }

    /** Ambiente rectangular, en metros. */
    private static RoomRequest room(String name, double x1, double y1, double x2, double y2) {
        return new RoomRequest(name, List.of(x1, y1, x2, y1, x2, y2, x1, y2), null);
    }

    private void document(UUID adminId, UUID developmentId, UUID unitId, String title,
                          UnitDocument.Category category, List<String> paragraphs) {
        Path pdf = DemoAssets.documentPdf(title, paragraphs);
        documents.importDocument(adminId, developmentId,
                new RequestDocumentUploadRequest(title, category, unitId, title.toLowerCase().replace(' ', '-') + ".pdf", "application/pdf"),
                pdf);
    }

    private void capture(UUID userId, UUID developmentId, UUID unitId, UUID stageId,
            String date, String zone, String description, Path splat) {
        captures.importSplat(userId, developmentId,
                new CreateCaptureRequest(demoDate(date), description, unitId, stageId, zone), splat);
    }

    private Path resolveSplat() {
        if (splatPath == null || splatPath.isBlank()) {
            Path bundled = bundledSplat(BUNDLED_SPLAT);
            if (bundled == null) {
                log.warn("No está la captura de muestra {}: los desarrollos de demostración quedan sin recorridos 3D",
                        BUNDLED_SPLAT);
            }
            return bundled;
        }
        Path path = Path.of(splatPath);
        if (!Files.isRegularFile(path)) {
            log.warn("No se encontró la captura de muestra en {}: los desarrollos quedan sin recorridos 3D", path);
            return null;
        }
        return path;
    }

    /** La captura que viaja en el jar, para que un clon limpio tenga recorridos 3D; nulo si no está. */
    static Path bundledSplat(String resource) {
        ClassPathResource splat = new ClassPathResource(resource);
        if (!splat.exists()) {
            return null;
        }
        try (InputStream input = splat.getInputStream()) {
            Path file = Files.createTempFile("obraviva-demo-", ".spz");
            file.toFile().deleteOnExit();
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la captura de muestra " + resource, e);
        }
    }

    /** Las ilustraciones viajan en el jar; el almacenamiento necesita un archivo en disco. */
    private static Path image(String name) {
        try (InputStream input = new ClassPathResource("demo/images/" + name).getInputStream()) {
            Path file = Files.createTempFile("obraviva-demo-", "-" + name);
            file.toFile().deleteOnExit();
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la ilustración " + name, e);
        }
    }
}
