package com.obraviva.platform.developments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    /** Gastos de la obra en orden cronológico: es el insumo de la serie acumulada. */
    List<Expense> findByDevelopmentIdAndOrganizationIdInOrderByIncurredOnAsc(
            UUID developmentId, Collection<UUID> organizationIds);

    Optional<Expense> findByIdAndOrganizationIdIn(UUID id, Collection<UUID> organizationIds);
}