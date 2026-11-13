package com.obraviva.platform.developments;

import com.obraviva.platform.developments.DashboardDtos.AlertStage;
import com.obraviva.platform.developments.DashboardDtos.DashboardResponse;
import com.obraviva.platform.developments.DashboardDtos.SpendingPoint;
import com.obraviva.platform.developments.DashboardDtos.StageDashboardRow;
import com.obraviva.platform.developments.DashboardDtos.StageOption;
import com.obraviva.platform.developments.DashboardDtos.TrafficLight;
import com.obraviva.platform.developments.DashboardDtos.UnitDashboardRow;
import com.obraviva.platform.developments.DevelopmentAccessService.DevelopmentSummary;
import com.obraviva.platform.developments.StageCatalogService.StagePlan;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Arma el panel del desarrollista cruzando avance físico declarado con gasto.
 *
 * <p><strong>Asimetría deliberada:</strong> el gasto se muestra como serie temporal y
 * el avance como valor actual. Cada gasto lleva su fecha, así que la historia existe;
 * el avance, en cambio, se sobrescribe con cada declaración, de modo que el sistema
 * sabe cuánto avanzó hoy pero no qué se declaró el mes pasado. Tener esa curva exigiría
 * historizar las declaraciones en una tabla aparte. Mostrar una curva de avance
 * reconstruida a partir de otra cosa sería inventarla.
 */
@Service
public class DashboardService {

    /** Hasta cuántos puntos de diferencia entre consumo y avance se considera normal. */
    private static final int GREEN_THRESHOLD = 10;
    private static final int YELLOW_THRESHOLD = 25;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DevelopmentAccessService developmentAccess;
    private final StageCatalogService stageCatalog;
    private final ExpenseRepository expenseRepository;
    private final OrganizationMembershipService membershipService;
    private final UnitRepository unitRepository;

    public DashboardService(
            DevelopmentAccessService developmentAccess,
            StageCatalogService stageCatalog,
            ExpenseRepository expenseRepository,
            OrganizationMembershipService membershipService,
            UnitRepository unitRepository) {
        this.developmentAccess = developmentAccess;
        this.stageCatalog = stageCatalog;
        this.expenseRepository = expenseRepository;
        this.membershipService = membershipService;
        this.unitRepository = unitRepository;
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(UUID userId, UUID developmentId) {
        return dashboard(userId, developmentId, null);
    }

    /**
     * Panel de una casa, o del desarrollo con {@code unitId} nulo. En el del desarrollo los
     * totales suman todo; las filas de etapas son las de obras comunes y cada casa aparece
     * resumida en una fila propia, para no listar cien etapas de diez casas.
     */
    @Transactional(readOnly = true)
    public DashboardResponse dashboard(UUID userId, UUID developmentId, UUID unitId) {
        DevelopmentSummary development =
                developmentAccess.requireOwnedDevelopment(userId, developmentId);
        List<UUID> organizationIds = membershipService.organizationIdsOf(userId);

        List<StagePlan> allStages = stageCatalog.stagePlansOf(developmentId, organizationIds);
        List<Expense> allExpenses = expenseRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByIncurredOnAsc(
                        developmentId, organizationIds);
        List<Unit> units = unitRepository.findByDevelopmentIdAndOrganizationIdIn(developmentId, organizationIds);
        if (unitId != null && units.stream().noneMatch(unit -> unit.getId().equals(unitId))) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Casa no encontrada");
        }

        List<StagePlan> stages = allStages.stream()
                .filter(stage -> Objects.equals(stage.unitId(), unitId))
                .toList();
        List<Expense> expenses = unitId == null
                ? allExpenses
                : allExpenses.stream().filter(expense -> unitId.equals(expense.getUnitId())).toList();

        Map<UUID, BigDecimal> spentByStage = new HashMap<>();
        BigDecimal unassigned = BigDecimal.ZERO;
        BigDecimal totalSpent = BigDecimal.ZERO;

        for (Expense expense : expenses) {
            totalSpent = totalSpent.add(expense.getAmount());
            if (expense.getStageId() == null) {
                unassigned = unassigned.add(expense.getAmount());
            } else {
                spentByStage.merge(expense.getStageId(), expense.getAmount(), BigDecimal::add);
            }
        }

        List<StageDashboardRow> rows = stages.stream()
                .map(stage -> toRow(stage, spentByStage.getOrDefault(stage.id(), BigDecimal.ZERO)))
                .toList();

        // El presupuesto total del desarrollo suma también el de las casas.
        BigDecimal totalBudget = (unitId == null ? allStages : stages).stream()
                .map(StagePlan::budgetAmount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<UUID, BigDecimal> spentByAnyStage = spentByStage(allExpenses);
        List<UnitDashboardRow> unitRows = unitId != null ? List.of() : units.stream()
                .sorted(Comparator.comparing(Unit::getCode))
                .map(unit -> unitRow(unit.getId(), unit.getCode(),
                        allStages.stream().filter(stage -> unit.getId().equals(stage.unitId())).toList(),
                        allExpenses.stream().filter(expense -> unit.getId().equals(expense.getUnitId())).toList(),
                        spentByAnyStage))
                .toList();

        List<StagePlan> commonStages = allStages.stream().filter(stage -> stage.unitId() == null).toList();
        // Las obras comunes son sus etapas y lo cargado en ellas; lo general del desarrollo va aparte.
        UnitDashboardRow commonWorks = unitId != null || commonStages.isEmpty() ? null : unitRow(null, null, commonStages,
                allExpenses.stream().filter(expense -> expense.getUnitId() == null && expense.getStageId() != null).toList(),
                spentByAnyStage);
        BigDecimal generalSpent = unitId != null ? BigDecimal.ZERO : allExpenses.stream()
                .filter(expense -> expense.getUnitId() == null && expense.getStageId() == null)
                .map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<UUID, String> unitCodes = units.stream().collect(Collectors.toMap(Unit::getId, Unit::getCode));
        List<AlertStage> alerts = alertsOf(unitId == null ? allStages : stages, spentByAnyStage, unitCodes);

        BigDecimal overall = unitId != null
                ? StageCatalogService.overallProgress(stages)
                : BigDecimal.valueOf(StageCatalogService.developmentProgressPercent(allStages.stream()
                        .map(stage -> new StageCatalogService.StageProgress(stage.id(), stage.name(), stage.orderIndex(),
                                stage.status(), stage.progressPercent(), stage.weight(), stage.unitId()))
                        .toList()));

        List<StagePlan> scope = unitId == null ? allStages : stages;
        return new DashboardResponse(
                developmentId,
                unitId,
                development.name(),
                overall,
                totalBudget,
                totalSpent,
                percentOf(totalSpent, totalBudget),
                unassigned,
                rows,
                spendingCurve(expenses),
                expenses.isEmpty() ? null : expenses.get(expenses.size() - 1).getIncurredOn(),
                unitRows,
                alerts,
                plannedCurve(scope),
                (int) scope.stream().filter(DashboardService::undated).count(),
                scope.stream().filter(DashboardService::undated).map(StagePlan::budgetAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                commonWorks,
                generalSpent,
                scope.stream()
                        .sorted(Comparator.<StagePlan, Boolean>comparing(stage -> stage.unitId() == null)
                                .thenComparing(stage -> unitCodes.getOrDefault(stage.unitId(), ""))
                                .thenComparingInt(StagePlan::orderIndex))
                        .map(stage -> new StageOption(stage.id(), stage.unitId(), stage.name(), stage.orderIndex()))
                        .toList());
    }

    /** Tiene presupuesto pero le falta alguna fecha prevista: no puede entrar en la curva. */
    private static boolean undated(StagePlan stage) {
        return stage.budgetAmount() != null && stage.budgetAmount().signum() > 0
                && (stage.plannedStart() == null || stage.plannedEnd() == null || stage.plannedEnd().isBefore(stage.plannedStart()));
    }

    /**
     * La curva prevista: cada etapa con presupuesto y fechas reparte su presupuesto entre los
     * días de su plan (los dos extremos incluidos), y cada mes suma lo de sus días. Es lo que
     * el dueño planeó, no una proyección: sale de sus fechas y de su presupuesto.
     */
    static List<SpendingPoint> plannedCurve(List<StagePlan> stages) {
        Map<String, BigDecimal> byMonth = new TreeMap<>();
        for (StagePlan stage : stages) {
            if (stage.budgetAmount() == null || stage.budgetAmount().signum() <= 0 || undated(stage)) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(stage.plannedStart(), stage.plannedEnd()) + 1;
            BigDecimal perDay = stage.budgetAmount().divide(BigDecimal.valueOf(days), 10, RoundingMode.HALF_UP);
            LocalDate cursor = stage.plannedStart();
            while (!cursor.isAfter(stage.plannedEnd())) {
                LocalDate monthEnd = cursor.withDayOfMonth(cursor.lengthOfMonth());
                LocalDate until = monthEnd.isBefore(stage.plannedEnd()) ? monthEnd : stage.plannedEnd();
                long inMonth = ChronoUnit.DAYS.between(cursor, until) + 1;
                byMonth.merge(cursor.format(MONTH), perDay.multiply(BigDecimal.valueOf(inMonth)), BigDecimal::add);
                cursor = until.plusDays(1);
            }
        }
        List<SpendingPoint> points = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : byMonth.entrySet()) {
            BigDecimal amount = entry.getValue().setScale(2, RoundingMode.HALF_UP);
            accumulated = accumulated.add(entry.getValue());
            points.add(new SpendingPoint(entry.getKey(), amount, accumulated.setScale(2, RoundingMode.HALF_UP)));
        }
        return points;
    }

    /** Lo gastado por etapa; los gastos sin etapa no entran. */
    private static Map<UUID, BigDecimal> spentByStage(List<Expense> expenses) {
        Map<UUID, BigDecimal> spent = new HashMap<>();
        for (Expense expense : expenses) {
            if (expense.getStageId() != null) {
                spent.merge(expense.getStageId(), expense.getAmount(), BigDecimal::add);
            }
        }
        return spent;
    }

    /** Qué tan mal está un semáforo: primero el rojo. Sin presupuesto va último porque no es una alerta. */
    private static int severity(TrafficLight light) {
        return switch (light) {
            case RED -> 0;
            case YELLOW -> 1;
            case GREEN -> 2;
            case NO_BUDGET -> 3;
        };
    }

    private static boolean isAlert(TrafficLight light) {
        return light == TrafficLight.RED || light == TrafficLight.YELLOW;
    }

    /** Las etapas en alerta: las rojas primero, después por casa (las comunes al final) y por etapa. */
    private static List<AlertStage> alertsOf(
            List<StagePlan> stages, Map<UUID, BigDecimal> spentByStage, Map<UUID, String> unitCodes) {
        return stages.stream()
                .map(stage -> Map.entry(stage, toRow(stage, spentByStage.getOrDefault(stage.id(), BigDecimal.ZERO))))
                .filter(entry -> isAlert(entry.getValue().trafficLight()))
                .sorted(Comparator
                        .<Map.Entry<StagePlan, StageDashboardRow>>comparingInt(entry -> severity(entry.getValue().trafficLight()))
                        .thenComparing(entry -> entry.getKey().unitId() == null,
                                Boolean::compare)
                        .thenComparing(entry -> unitCodes.getOrDefault(entry.getKey().unitId(), ""))
                        .thenComparingInt(entry -> entry.getKey().orderIndex()))
                .map(entry -> new AlertStage(
                        entry.getKey().id(),
                        entry.getKey().name(),
                        entry.getKey().unitId(),
                        entry.getKey().unitId() == null ? null : unitCodes.get(entry.getKey().unitId()),
                        entry.getValue().trafficLight(),
                        entry.getValue().budgetUsedPercent(),
                        entry.getValue().progressPercent()))
                .toList();
    }

    private static UnitDashboardRow unitRow(
            UUID unitId, String code, List<StagePlan> stages, List<Expense> expenses, Map<UUID, BigDecimal> spentByStage) {
        BigDecimal budget = stages.stream().map(StagePlan::budgetAmount).filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal spent = expenses.stream().map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        int progress = StageCatalogService.overallProgress(stages).setScale(0, RoundingMode.HALF_UP).intValue();
        BigDecimal usedPercent = percentOf(spent, budget);
        StageDashboardRow worst = stages.stream()
                .map(stage -> toRow(stage, spentByStage.getOrDefault(stage.id(), BigDecimal.ZERO)))
                .min(Comparator.<StageDashboardRow>comparingInt(row -> severity(row.trafficLight()))
                        .thenComparingInt(StageDashboardRow::orderIndex))
                .orElse(null);
        TrafficLight worstLight = worst == null ? TrafficLight.NO_BUDGET : worst.trafficLight();
        int withoutBudget = (int) stages.stream().filter(stage -> stage.budgetAmount() == null).count();
        return new UnitDashboardRow(unitId, code, progress,
                budget.signum() == 0 ? null : budget, spent, usedPercent, trafficLight(usedPercent, progress),
                worstLight, isAlert(worstLight) ? worst.name() : null, withoutBudget, stages.size());
    }

    private static StageDashboardRow toRow(StagePlan stage, BigDecimal spent) {
        BigDecimal usedPercent = percentOf(spent, stage.budgetAmount());
        return new StageDashboardRow(
                stage.id(),
                stage.name(),
                stage.orderIndex(),
                stage.status(),
                stage.progressPercent(),
                stage.weight(),
                stage.budgetAmount(),
                spent,
                usedPercent,
                trafficLight(usedPercent, stage.progressPercent()));
    }

    /**
     * El semáforo compara cuánto del presupuesto se consumió contra cuánto se avanzó.
     * Una etapa que gastó el 80% y avanzó el 30% se fue de cauce; una que gastó el 30%
     * y avanzó el 80% va bien, y por eso sólo se mira la diferencia hacia arriba.
     */
    private static TrafficLight trafficLight(BigDecimal usedPercent, int progressPercent) {
        if (usedPercent == null) {
            return TrafficLight.NO_BUDGET;
        }
        BigDecimal deviation = usedPercent.subtract(BigDecimal.valueOf(progressPercent));

        if (deviation.compareTo(BigDecimal.valueOf(GREEN_THRESHOLD)) <= 0) {
            return TrafficLight.GREEN;
        }
        if (deviation.compareTo(BigDecimal.valueOf(YELLOW_THRESHOLD)) <= 0) {
            return TrafficLight.YELLOW;
        }
        return TrafficLight.RED;
    }

    /** Null cuando no hay presupuesto: no hay con qué comparar, y cero mentiría. */
    private static BigDecimal percentOf(BigDecimal amount, BigDecimal base) {
        if (base == null || base.signum() == 0) {
            return null;
        }
        return amount.multiply(BigDecimal.valueOf(100))
                .divide(base, 1, RoundingMode.HALF_UP);
    }

    /**
     * Serie mensual de gasto, con su acumulado.
     *
     * <p>Sólo aparecen los meses con movimiento: rellenar los vacíos exigiría decidir
     * dónde empieza la obra, dato que el sistema no tiene.
     */
    private static List<SpendingPoint> spendingCurve(List<Expense> expenses) {
        Map<String, BigDecimal> byMonth = new TreeMap<>();
        for (Expense expense : expenses) {
            byMonth.merge(
                    expense.getIncurredOn().format(MONTH), expense.getAmount(), BigDecimal::add);
        }

        List<SpendingPoint> points = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : byMonth.entrySet()) {
            accumulated = accumulated.add(entry.getValue());
            points.add(new SpendingPoint(entry.getKey(), entry.getValue(), accumulated));
        }
        return points;
    }
}