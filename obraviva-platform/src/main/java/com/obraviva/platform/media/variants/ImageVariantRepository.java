package com.obraviva.platform.media.variants;

import com.obraviva.platform.media.MediaVariantsStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ImageVariantRepository extends JpaRepository<ImageVariant, String> {

    /** La cola del worker: es de todas las organizaciones, como la de las fotos. */
    List<ImageVariant> findTop5ByStatusOrderByCreatedAtAsc(MediaVariantsStatus status);

    Optional<ImageVariant> findByOrganizationIdInAndStorageKey(Collection<UUID> organizationIds, String storageKey);

    List<ImageVariant> findByOrganizationIdInAndStorageKeyIn(Collection<UUID> organizationIds, Collection<String> storageKeys);
}
