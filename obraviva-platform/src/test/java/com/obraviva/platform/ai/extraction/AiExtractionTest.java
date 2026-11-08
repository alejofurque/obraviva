package com.obraviva.platform.ai.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiExtractionTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID developmentId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();

    private AiExtraction newExtraction() {
        return new AiExtraction(organizationId, developmentId, "ogg", authorId);
    }

    private AiExtraction readyExtraction() {
        AiExtraction extraction = newExtraction();
        extraction.markReadyForReview(
                "terminamos la estructura", "{\"stageProposals\":[]}", "{}", "v1", "gemini-2.5-flash");
        return extraction;
    }

    @Test
    @DisplayName("nace pendiente y con la clave de almacenamiento resuelta")
    void startsPendingWithStorageKey() {
        AiExtraction extraction = newExtraction();

        assertThat(extraction.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(extraction.getAudioStorageKey())
                .isEqualTo("developments/" + developmentId + "/audios/" + extraction.getId() + ".ogg");
    }

    @Test
    @DisplayName("al quedar lista conserva transcripción, propuesta y trazabilidad")
    void readyForReviewKeepsTraceability() {
        AiExtraction extraction = readyExtraction();

        assertThat(extraction.getReviewStatus()).isEqualTo(ReviewStatus.READY_FOR_REVIEW);
        assertThat(extraction.getTranscript()).isEqualTo("terminamos la estructura");
        assertThat(extraction.getPromptVersion()).isEqualTo("v1");
        assertThat(extraction.getModelId()).isEqualTo("gemini-2.5-flash");
        assertThat(extraction.getRawOutput()).isEqualTo("{}");
    }

    @Test
    @DisplayName("la transcripción puede guardarse antes de la interpretación")
    void transcriptCanBeAttachedEarly() {
        // Si el modelo de lenguaje falla después, el trabajo del transcriptor no se
        // pierde y el revisor conserva algo que mirar.
        AiExtraction extraction = newExtraction();

        extraction.attachTranscript("texto transcripto");

        assertThat(extraction.getTranscript()).isEqualTo("texto transcripto");
        assertThat(extraction.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    @DisplayName("aprobar registra quién y cuándo")
    void approvalRecordsReviewer() {
        AiExtraction extraction = readyExtraction();

        extraction.approve(reviewerId, "revisado, corregí una etapa");

        assertThat(extraction.getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(extraction.getReviewedBy()).isEqualTo(reviewerId);
        assertThat(extraction.getReviewedAt()).isNotNull();
        assertThat(extraction.getReviewNotes()).contains("corregí");
    }

    @Test
    @DisplayName("rechazar también registra quién y cuándo")
    void rejectionRecordsReviewer() {
        AiExtraction extraction = readyExtraction();

        extraction.reject(reviewerId, "el audio no era de esta obra");

        assertThat(extraction.getReviewStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(extraction.getReviewedBy()).isEqualTo(reviewerId);
    }

    @Test
    @DisplayName("no se puede aprobar algo que todavía no fue procesado")
    void cannotApproveBeforeProcessing() {
        AiExtraction extraction = newExtraction();

        assertThatThrownBy(() -> extraction.approve(reviewerId, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("una interpretación resuelta no vuelve atrás")
    void resolvedExtractionIsFinal() {
        // Corregir un avance ya publicado es tarea de la línea de tiempo, que
        // versiona su contenido; no se rehace la revisión.
        AiExtraction extraction = readyExtraction();
        extraction.approve(reviewerId, null);

        assertThatThrownBy(() -> extraction.reject(reviewerId, "me arrepentí"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("un fallo de procesamiento es terminal y no deja nada que revisar")
    void failureIsTerminal() {
        AiExtraction extraction = newExtraction();

        extraction.markFailed("No se reconoció ninguna voz en el audio");

        assertThat(extraction.getReviewStatus()).isEqualTo(ReviewStatus.FAILED);
        assertThat(extraction.getReviewStatus().isReviewable()).isFalse();
        assertThatThrownBy(() -> extraction.approve(reviewerId, null))
                .isInstanceOf(IllegalStateException.class);
    }
}