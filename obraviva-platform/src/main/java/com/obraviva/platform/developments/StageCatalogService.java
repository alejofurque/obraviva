package com.obraviva.platform.developments;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * API pública del módulo developments hacia otros módulos, en lo referido a etapas:
 * las expone como proyecciones y permite cambiar su estado, sin filtrar entidades
 * JPA fuera del módulo.
 *
 * <p>Existe porque el módulo de inteligencia artificial necesita la lista de etapas
 * para armar el contexto del modelo de lenguaje, y aplicar los cambios de estado que
 * un revisor aprueba. La regla del proyecto es que ningún módulo accede a las
 * entidades de otro. El panel del desarrollista es el segundo consumidor: necesita
 * además el avance declarado y el presupuesto.
 */
@Service
public class StageCatalogService {

    private final StageRepository stageRepository;

    public StageCatalogService(StageRepository stageRepository) {
        this.stageRepository = stageRepository;
    }

    /** Etapa vista desde fuera del módulo. */
    /** {@code unitId} nulo: etapa de las obras comunes del desarrollo. */
    public record StageSummary(UUID id, String name, int orderIndex, String status, UUID unitId) {
    }

    /**
     * Etapa con su avance declarado y su presupuesto, para el panel.
     *
     * <p>Se mantiene separada de {@link StageSummary} a propósito: el contexto que se
     * le arma al modelo de lenguaje no tiene por qué llevar cifras económicas, y la
     * forma más confiable de que no las lleve es que el record que recibe no las
     * tenga.
     */
    public record StagePlan(
            UUID id,
            String name,
            int orderIndex,
            String status,
            int progressPercent,
            int weight,
            BigDecimal budgetAmount,
            UUID unitId,
            /** Las fechas previstas: con presupuesto, arman la curva prevista del gasto. */
            LocalDate plannedStart,
            LocalDate plannedEnd) {
    }

    /**
     * Etapa con su avance y su peso, sin presupuesto. Es lo que necesita la vista del
     * comprador: igual que con el contexto del modelo de lenguaje, la forma más segura
     * de que una proyección no lleve cifras económicas es que su record no las tenga.
     */
    public record StageProgress(
            UUID id,
            String name,
            int orderIndex,
            String status,
            int progressPercent,
            int weight,
            UUID unitId) {
    }

    @Transactional(readOnly = true)
    public List<StageProgress> stageProgressOf(UUID developmentId, List<UUID> organizationIds) {
        return stageRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByOrderIndexAsc(
                        developmentId, organizationIds)
                .stream()
                .map(StageCatalogService::toProgress)
                .toList();
    }

    /** Lo que se le cuenta a un comprador de cada etapa, además de su avance. */
    public record StageDetail(
            UUID id,
            String description,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            LocalDate startedOn,
            LocalDate completedOn) {
    }

    @Transactional(readOnly = true)
    public Map<UUID, StageDetail> stageDetailsOf(UUID developmentId, List<UUID> organizationIds) {
        return stageRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByOrderIndexAsc(developmentId, organizationIds)
                .stream()
                .collect(Collectors.toMap(Stage::getId, stage -> new StageDetail(
                        stage.getId(), stage.getDescription(), stage.getPlannedStart(), stage.getPlannedEnd(),
                        stage.getStartedOn(), stage.getCompletedOn())));
    }

    static StageProgress toProgress(Stage stage) {
        return new StageProgress(
                stage.getId(),
                stage.getName(),
                stage.getOrderIndex(),
                stage.getStatus().name(),
                stage.getProgressPercent(),
                stage.getWeight(),
                stage.getUnitId());
    }

    /**
     * Avance de un desarrollo de casas: el promedio del avance de cada casa con etapas,
     * todas con el mismo peso. Una casa chica no pesa menos que una grande para quien
     * mira el barrio: cada una es una entrega. Sin casas con etapas, cuentan las obras
     * comunes.
     */
    public static int developmentProgressPercent(List<StageProgress> stages) {
        Map<UUID, List<StageProgress>> byUnit = stages.stream()
                .filter(stage -> stage.unitId() != null)
                .collect(Collectors.groupingBy(StageProgress::unitId));
        if (byUnit.isEmpty()) {
            return overallProgressPercent(stages);
        }
        BigDecimal sum = byUnit.values().stream()
                .map(unitStages -> weightedProgress(
                        unitStages.stream().mapToInt(StageProgress::progressPercent).toArray(),
                        unitStages.stream().mapToInt(StageProgress::weight).toArray()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(byUnit.size()), 0, RoundingMode.HALF_UP).intValue();
    }

    /** Casas con todas sus etapas completadas. Una casa sin etapas no cuenta como terminada. */
    public static int completedUnitCount(List<StageProgress> stages) {
        return (int) stages.stream()
                .filter(stage -> stage.unitId() != null)
                .collect(Collectors.groupingBy(StageProgress::unitId))
                .values().stream()
                .filter(unitStages -> unitStages.stream().allMatch(stage -> stage.status().equals(StageStatus.COMPLETED.name())))
                .count();
    }

    /**
     * Avance global redondeado a entero, para mostrar a una familia. Es el mismo cálculo
     * que {@link #overallProgress}: el comprador y el panel ven la misma cifra.
     */
    public static int overallProgressPercent(List<StageProgress> stages) {
        return weightedProgress(
                stages.stream().mapToInt(StageProgress::progressPercent).toArray(),
                stages.stream().mapToInt(StageProgress::weight).toArray())
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    /**
     * La etapa que la obra está atravesando.
     *
     * <p>Primero la de menor orden que esté en ejecución o demorada: una demora no deja
     * de ser lo que está pasando. Si ninguna lo está, la primera pendiente, que es lo
     * que viene. Si todas terminaron, o no hay etapas, no hay etapa actual.
     */
    public static Optional<StageProgress> currentStage(List<StageProgress> stages) {
        List<StageProgress> ordered = stages.stream()
                .sorted(Comparator.comparingInt(StageProgress::orderIndex))
                .toList();
        return ordered.stream()
                .filter(stage -> stage.status().equals(StageStatus.IN_PROGRESS.name())
                        || stage.status().equals(StageStatus.DELAYED.name()))
                .findFirst()
                .or(() -> ordered.stream()
                        .filter(stage -> stage.status().equals(StageStatus.PENDING.name()))
                        .findFirst());
    }

    @Transactional(readOnly = true)
    public List<StageSummary> stagesOf(UUID developmentId, List<UUID> organizationIds) {
        return stageRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByOrderIndexAsc(
                        developmentId, organizationIds)
                .stream()
                .map(stage -> new StageSummary(
                        stage.getId(),
                        stage.getName(),
                        stage.getOrderIndex(),
                        stage.getStatus().name(),
                        stage.getUnitId()))
                .toList();
    }

    /**
     * Etapas sobre las que puede publicar algo de una casa: las suyas. Sin casa, las de
     * las obras comunes.
     */
    @Transactional(readOnly = true)
    public List<StageSummary> stagesForScope(UUID developmentId, UUID unitId, List<UUID> organizationIds) {
        return stagesOf(developmentId, organizationIds).stream()
                .filter(stage -> Objects.equals(stage.unitId(), unitId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StagePlan> stagePlansOf(UUID developmentId, List<UUID> organizationIds) {
        return stageRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByOrderIndexAsc(
                        developmentId, organizationIds)
                .stream()
                .map(stage -> new StagePlan(
                        stage.getId(),
                        stage.getName(),
                        stage.getOrderIndex(),
                        stage.getStatus().name(),
                        stage.getProgressPercent(),
                        stage.getWeight(),
                        stage.getBudgetAmount(),
                        stage.getUnitId(),
                        stage.getPlannedStart(),
                        stage.getPlannedEnd()))
                .toList();
    }

    /**
     * Lo que un avance publicado cambia de su etapa: el porcentaje y el estado, nada más.
     * El presupuesto, el peso y el plan quedan fuera porque esta firma no los recibe.
     *
     * <p>Primero el porcentaje y después el estado, así Completado deja la etapa en 100
     * aunque haya llegado otro número: "completada al 40%" no se le muestra a nadie. Al
     * revés no se infiere nada: llegar a 100 no la completa. El cambio de estado queda con
     * la fecha del avance. No verifica acceso: quien llama ya validó la etapa contra la
     * casa y el usuario, y corre en su transacción.
     */
    @Transactional
    public AppliedStageChange applyProgressUpdate(
            UUID stageId, UUID organizationId, Integer progressPercent, StageStatus status, LocalDate effectiveOn) {
        Stage stage = stageRepository
                .findByIdAndOrganizationIdIn(stageId, List.of(organizationId))
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa no encontrada"));
        int percentBefore = stage.getProgressPercent();
        StageStatus statusBefore = stage.getStatus();
        if (progressPercent != null) {
            stage.declareProgress(progressPercent);
        }
        if (status != null && status != stage.getStatus()) {
            stage.changeStatus(status, effectiveOn);
        }
        return new AppliedStageChange(percentBefore, stage.getProgressPercent(), statusBefore, stage.getStatus());
    }

    /** Cómo estaba la etapa antes de un avance y cómo quedó. Completar lleva el porcentaje a 100. */
    public record AppliedStageChange(int percentBefore, int percentAfter, StageStatus statusBefore, StageStatus statusAfter) {
    }

    /**
     * Aplica un cambio de estado aprobado por un revisor.
     *
     * <p>La búsqueda se acota por organización en la firma del repositorio, de modo
     * que un identificador de otra organización devuelve 404 en lugar de aplicar el
     * cambio. El estado llega como texto porque proviene del exterior del módulo;
     * un valor inválido es un error del llamador.
     */
    @Transactional
    public void updateStageStatus(UUID stageId, List<UUID> organizationIds, String status) {
        Stage stage = stageRepository
                .findByIdAndOrganizationIdIn(stageId, organizationIds)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa no encontrada"));

        try {
            stage.changeStatus(StageStatus.valueOf(status.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Estado de etapa inválido: " + status);
        }
    }

    /**
     * Avance global de la obra: suma ponderada de los avances declarados, dividida por
     * la suma de los pesos.
     *
     * <p>Se divide por la suma real y no por 100 porque los pesos no están obligados a
     * sumar cien. Una obra sin etapas declara 0: no es un error, es que todavía no hay
     * nada que medir.
     *
     * <p>El cálculo vive acá y no en un modelo de lenguaje: ninguna cifra del panel
     * sale de la IA.
     */
    public static BigDecimal overallProgress(List<StagePlan> stages) {
        return weightedProgress(
                stages.stream().mapToInt(StagePlan::progressPercent).toArray(),
                stages.stream().mapToInt(StagePlan::weight).toArray());
    }

    private static BigDecimal weightedProgress(int[] progress, int[] weights) {
        int totalWeight = 0;
        int weighted = 0;
        for (int i = 0; i < progress.length; i++) {
            totalWeight += weights[i];
            weighted += progress[i] * weights[i];
        }
        if (totalWeight == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(weighted)
                .divide(BigDecimal.valueOf(totalWeight), 1, RoundingMode.HALF_UP);
    }
}