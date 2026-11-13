package com.obraviva.platform.developments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CaptureSectorRepository extends JpaRepository<CaptureSector, UUID> {

    List<CaptureSector> findByUnitIdOrderByOrderIndexAsc(UUID unitId);
}
