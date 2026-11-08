package com.obraviva.platform.developments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DevelopmentRepository extends JpaRepository<Development, UUID> {

    List<Development> findByOrganizationIdIn(Collection<UUID> organizationIds);

    /** Búsqueda siempre acotada a las organizaciones del usuario. */
    Optional<Development> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);
}