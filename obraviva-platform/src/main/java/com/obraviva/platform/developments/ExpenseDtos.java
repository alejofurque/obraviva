package com.obraviva.platform.developments;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ExpenseDtos {

    private ExpenseDtos() {
    }

    public record CreateExpenseRequest(
            /** Opcional: null para gastos generales de obra. */
            UUID stageId,
            @NotNull LocalDate incurredOn,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 300) String description,
            /** Casa del gasto. Nulo: gasto general o de obras comunes. */
            UUID unitId) {

        public CreateExpenseRequest(UUID stageId, LocalDate incurredOn, BigDecimal amount, String description) {
            this(stageId, incurredOn, amount, description, null);
        }
    }

    /**
     * Corrección de un gasto. Reemplaza los cuatro datos que se cargan; la casa del gasto no
     * cambia. La etapa, nula para un gasto general, tiene que ser de esa misma casa (o de
     * las obras comunes si el gasto es del desarrollo), igual que al crearlo.
     */
    public record UpdateExpenseRequest(
            UUID stageId,
            @NotNull LocalDate incurredOn,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @Size(max = 300) String description) {
    }

    public record ExpenseResponse(
            UUID id,
            UUID developmentId,
            UUID unitId,
            UUID stageId,
            LocalDate incurredOn,
            BigDecimal amount,
            String description,
            Instant createdAt) {

        static ExpenseResponse from(Expense expense) {
            return new ExpenseResponse(
                    expense.getId(),
                    expense.getDevelopmentId(),
                    expense.getUnitId(),
                    expense.getStageId(),
                    expense.getIncurredOn(),
                    expense.getAmount(),
                    expense.getDescription(),
                    expense.getCreatedAt());
        }
    }
}