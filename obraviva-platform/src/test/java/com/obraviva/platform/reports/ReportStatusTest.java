package com.obraviva.platform.reports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportStatusTest {

    private Report newReport() {
        return new Report(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                "{}",
                UUID.randomUUID());
    }

    @Test
    @DisplayName("un reporte nace compuesto y sin redactar")
    void reportStartsComposed() {
        Report report = newReport();

        assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(report.getNarrative()).isNull();
        assertThat(report.getSourceData()).isNotNull();
    }

    @Test
    @DisplayName("la redacción deja el reporte a la espera de aprobación")
    void narrationLeavesReportAwaitingApproval() {
        Report report = newReport();

        report.markReadyForReview("Texto del período.", "crudo", "v1", "modelo-x");

        assertThat(report.getStatus()).isEqualTo(ReportStatus.READY_FOR_REVIEW);
        assertThat(report.getStatus().isReviewable()).isTrue();
        assertThat(report.getPromptVersion()).isEqualTo("v1");
    }

    @Test
    @DisplayName("un reporte resuelto no vuelve atrás")
    void resolvedReportDoesNotGoBack() {
        Report report = newReport();
        report.markReadyForReview("Texto del período.", "crudo", "v1", "modelo-x");
        report.approve(UUID.randomUUID(), null);

        assertThat(report.getStatus().isTerminal()).isTrue();
        assertThatThrownBy(() -> report.reject(UUID.randomUUID(), null))
                .isInstanceOf(IllegalStateException.class);
    }
}