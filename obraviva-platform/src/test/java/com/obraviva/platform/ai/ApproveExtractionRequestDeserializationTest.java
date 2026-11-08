package com.obraviva.platform.ai;

import com.obraviva.platform.ai.AudioDtos.ApproveExtractionRequest;
import com.obraviva.platform.developments.StageStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deserialización de la aprobación de un audio desde el JSON que manda la revisión: una
 * entrada por avance, con su cambio de etapa y sus fotos. Sin primitivos: lo que falta no
 * puede romper la construcción del record.
 */
class ApproveExtractionRequestDeserializationTest {

    /** Con la configuración de Spring: los desconocidos se ignoran, salvo en stageUpdate. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final UUID STAGE = UUID.randomUUID();

    @Test
    @DisplayName("la revisión del teléfono: una novedad con su etapa al 35% y una foto, y otra general")
    void fieldReviewBody() {
        UUID photo = UUID.randomUUID();
        ApproveExtractionRequest request = JSON.readValue("""
                {"entryDate":"2026-10-14","notes":null,"entries":[
                  {"stageId":"%s","description":"Revoque grueso del living","photoIds":["%s"],
                   "stageUpdate":{"status":"IN_PROGRESS","progressPercent":35}},
                  {"stageId":null,"description":"Llegó el hierro","photoIds":[],"stageUpdate":null}]}"""
                .formatted(STAGE, photo), ApproveExtractionRequest.class);

        assertThat(request.entryDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(request.entries()).hasSize(2);
        assertThat(request.entries().get(0).stageUpdate().status()).isEqualTo(StageStatus.IN_PROGRESS);
        assertThat(request.entries().get(0).stageUpdate().progressPercent()).isEqualTo(35);
        assertThat(request.entries().get(0).photoIds()).containsExactly(photo);
        assertThat(request.entries().get(1).stageId()).isNull();
        assertThat(request.entries().get(1).stageUpdate()).isNull();
    }

    @Test
    @DisplayName("un cambio de estado sin porcentaje: la IA propone estados, el porcentaje queda nulo")
    void statusWithoutPercent() {
        ApproveExtractionRequest request = JSON.readValue("""
                {"entryDate":"2026-10-14","entries":[{"stageId":"%s","description":"Se demoró","stageUpdate":{"status":"DELAYED"}}]}"""
                .formatted(STAGE), ApproveExtractionRequest.class);

        assertThat(request.entries().getFirst().stageUpdate().progressPercent()).isNull();
        assertThat(request.entries().getFirst().photoIds()).isNull();
        assertThat(request.notes()).isNull();
    }

    @Test
    @DisplayName("el lugar de cada foto va por su id; sin photoSectors, las fotos quedan en General")
    void photoSectors() {
        UUID frente = UUID.randomUUID();
        UUID suelta = UUID.randomUUID();
        ApproveExtractionRequest request = JSON.readValue("""
                {"entryDate":"2026-10-14","entries":[
                  {"stageId":null,"description":"Frente revocado","photoIds":["%s","%s"],"photoSectors":{"%s":"Frente"}},
                  {"stageId":null,"description":"Llegó el hierro","photoIds":[]}]}"""
                .formatted(frente, suelta, frente), ApproveExtractionRequest.class);

        assertThat(request.entries().get(0).photoSectors()).containsExactly(java.util.Map.entry(frente, "Frente"));
        assertThat(request.entries().get(1).photoSectors()).isNull();
    }

    @Test
    @DisplayName("presupuesto dentro del cambio de etapa se rechaza")
    void budgetInsideStageUpdateIsRejected() {
        assertThatThrownBy(() -> JSON.readValue("""
                {"entryDate":"2026-10-14","entries":[{"stageId":"%s","description":"x","stageUpdate":{"budgetAmount":1}}]}"""
                .formatted(STAGE), ApproveExtractionRequest.class))
                .hasMessageContaining("budgetAmount");
    }
}
