package com.obraviva.platform.ai.extraction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a las interpretaciones de audio.
 *
 * <p>Las búsquedas destinadas a peticiones de usuario están acotadas por
 * organización en la propia firma del método: la seguridad vive en el repositorio,
 * no en la disciplina de cada consulta. {@code findById} sin acotar queda
 * disponible sólo para procesos de fondo, donde no hay usuario y la pertenencia se
 * verifica contra el trabajo.
 */
public interface AiExtractionRepository extends JpaRepository<AiExtraction, UUID> {

    Optional<AiExtraction> findByIdAndOrganizationIdIn(UUID id, List<UUID> organizationIds);

    List<AiExtraction> findByDevelopmentIdAndOrganizationIdInOrderByCreatedAtDesc(
            UUID developmentId, List<UUID> organizationIds);

    List<AiExtraction> findByOrganizationIdInAndReviewStatusOrderByCreatedAtAsc(
            List<UUID> organizationIds, ReviewStatus reviewStatus);
}