package com.obraviva.platform.progress;

import com.obraviva.platform.developments.StageStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deserialización del alta de avance desde el JSON real de los dos clientes: la Historia
 * del dueño, que no conoce los campos nuevos, y "Publicar avance" del encargado, que manda
 * las fotos y el cambio de la etapa.
 */
class CreateProgressEntryRequestDeserializationTest {

    /** Con la configuración de Spring: los campos desconocidos se ignoran, salvo en stageUpdate. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final UUID STAGE = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();

    @Test
    @DisplayName("el cuerpo de la Historia del dueño, sin fotos ni cambio de etapa")
    void ownerHistoryBody() {
        CreateProgressEntryRequest request = JSON.readValue("""
                {"stageId":"%s","entryDate":"2026-10-01","description":"Losa hormigonada","unitId":"%s"}"""
                .formatted(STAGE, UNIT), CreateProgressEntryRequest.class);

        assertThat(request.stageId()).isEqualTo(STAGE);
        assertThat(request.entryDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(request.unitId()).isEqualTo(UNIT);
        assertThat(request.photoIds()).isNull();
        assertThat(request.stageUpdate()).isNull();
    }

    @Test
    @DisplayName("el cuerpo de Publicar avance, con fotos y la etapa al 65% en ejecución")
    void fieldBodyWithStageUpdate() {
        UUID photo = UUID.randomUUID();
        CreateProgressEntryRequest request = JSON.readValue("""
                {"stageId":"%s","entryDate":"2026-10-01","description":"Revoque grueso del living","unitId":"%s",
                 "photoIds":["%s"],"stageUpdate":{"progressPercent":65,"status":"IN_PROGRESS"}}"""
                .formatted(STAGE, UNIT, photo), CreateProgressEntryRequest.class);

        assertThat(request.photoIds()).containsExactly(photo);
        assertThat(request.stageUpdate().progressPercent()).isEqualTo(65);
        assertThat(request.stageUpdate().status()).isEqualTo(StageStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("sin etapa (general de la casa), con fotos y stageUpdate nulo")
    void generalEntryWithPhotos() {
        CreateProgressEntryRequest request = JSON.readValue("""
                {"stageId":null,"entryDate":"2026-10-01","description":"Llegó el hierro","unitId":"%s",
                 "photoIds":[],"stageUpdate":null}""".formatted(UNIT), CreateProgressEntryRequest.class);

        assertThat(request.stageId()).isNull();
        assertThat(request.photoIds()).isEmpty();
        assertThat(request.stageUpdate()).isNull();
    }

    @Test
    @DisplayName("el estado solo, sin porcentaje: el porcentaje queda nulo, no en cero")
    void statusOnly() {
        CreateProgressEntryRequest request = JSON.readValue("""
                {"stageId":"%s","entryDate":"2026-10-01","description":"Falta material","stageUpdate":{"status":"DELAYED"}}"""
                .formatted(STAGE), CreateProgressEntryRequest.class);

        assertThat(request.stageUpdate().progressPercent()).isNull();
        assertThat(request.stageUpdate().status()).isEqualTo(StageStatus.DELAYED);
    }

    @Test
    @DisplayName("presupuesto o peso dentro de stageUpdate se rechazan, aunque Jackson ignore los desconocidos")
    void stageUpdateRejectsBudgetAndWeight() {
        assertThatThrownBy(() -> JSON.readValue("""
                {"stageId":"%s","entryDate":"2026-10-01","description":"x","stageUpdate":{"progressPercent":50,"budgetAmount":1000000}}"""
                .formatted(STAGE), CreateProgressEntryRequest.class))
                .hasMessageContaining("budgetAmount");
        assertThatThrownBy(() -> JSON.readValue("""
                {"stageId":"%s","entryDate":"2026-10-01","description":"x","stageUpdate":{"weight":3}}"""
                .formatted(STAGE), CreateProgressEntryRequest.class))
                .hasMessageContaining("weight");
    }

    @Test
    @DisplayName("el sector de cada foto llega como un mapa por id; sin él, nulo")
    void photoSectors() {
        UUID photo = UUID.randomUUID();
        CreateProgressEntryRequest request = JSON.readValue("""
                {"entryDate":"2026-10-01","description":"Frente revocado","unitId":"%s",
                 "photoIds":["%s"],"photoSectors":{"%s":"Frente"}}""".formatted(UNIT, photo, photo),
                CreateProgressEntryRequest.class);

        assertThat(request.photoSectors()).containsEntry(photo, "Frente");
        assertThat(JSON.readValue("""
                {"entryDate":"2026-10-01","description":"x"}""", CreateProgressEntryRequest.class).photoSectors()).isNull();
    }

    @Test
    @DisplayName("fuera de stageUpdate, un campo desconocido se sigue ignorando: el cliente del dueño no se rompe")
    void unknownFieldOutsideStageUpdateIsIgnored() {
        CreateProgressEntryRequest request = JSON.readValue("""
                {"entryDate":"2026-10-01","description":"x","algoNuevo":true}""", CreateProgressEntryRequest.class);

        assertThat(request.description()).isEqualTo("x");
    }
}
