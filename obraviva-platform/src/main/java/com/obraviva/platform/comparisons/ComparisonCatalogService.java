package com.obraviva.platform.comparisons;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * API pública del comparador para la vista de una unidad. No verifica acceso: recibe la
 * organización ya resuelta, igual que los demás catálogos.
 */
@Service
public class ComparisonCatalogService {

    private final ProjectComparisonRepository comparisonRepository;

    public ComparisonCatalogService(ProjectComparisonRepository comparisonRepository) {
        this.comparisonRepository = comparisonRepository;
    }

    /** Comparación publicable: tiene render y foto. La clave del render no sale al cliente. */
    public record CatalogComparison(String title, UUID unitId, String renderStorageKey, UUID photoMediaId) {
    }

    @Transactional(readOnly = true)
    public List<CatalogComparison> completeComparisons(UUID developmentId, UUID organizationId) {
        return comparisonRepository
                .findByDevelopmentIdAndOrganizationIdInOrderByCreatedAtAsc(developmentId, List.of(organizationId))
                .stream()
                .filter(ProjectComparison::isComplete)
                .map(comparison -> new CatalogComparison(
                        comparison.getTitle(),
                        comparison.getUnitId(),
                        comparison.getRenderStorageKey(),
                        comparison.getPhotoMediaId()))
                .toList();
    }
}
