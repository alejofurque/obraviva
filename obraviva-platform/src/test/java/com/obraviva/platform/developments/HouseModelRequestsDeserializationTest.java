package com.obraviva.platform.developments;

import com.obraviva.platform.ai.AudioDtos.CreateAudioExtractionRequest;
import com.obraviva.platform.developments.DevelopmentDetailsService.UpdateDevelopmentRequest;
import com.obraviva.platform.developments.ExpenseDtos.CreateExpenseRequest;
import com.obraviva.platform.developments.UnitDetailsDtos.CreateUnitsBatchRequest;
import com.obraviva.platform.developments.UnitDetailsDtos.UpdateSalesSheetRequest;
import com.obraviva.platform.developments.UnitDetailsDtos.UpdateUnitRequest;
import com.obraviva.platform.media.RequestUploadRequest;
import com.obraviva.platform.progress.CreateProgressEntryRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los pedidos del modelo de casas, leídos desde el JSON que manda el cliente. Todos
 * aceptan cuerpos parciales: ningún componente primitivo los rechaza.
 */
class HouseModelRequestsDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID UNIT = UUID.fromString("6f1c2a3b-0d4e-4f5a-8b6c-7d8e9f0a1b2c");

    @Test
    @DisplayName("el alta de varias casas se lee; sin plantilla indicada, no la aplica")
    void batchRequest() {
        CreateUnitsBatchRequest full = JSON.readValue("""
                {"prefix":"Casa","firstNumber":1,"count":10,"withStandardStages":true}""", CreateUnitsBatchRequest.class);
        assertThat(full.count()).isEqualTo(10);
        assertThat(full.withStandardStages()).isTrue();

        CreateUnitsBatchRequest partial = JSON.readValue("""
                {"prefix":"Lote","firstNumber":3,"count":2}""", CreateUnitsBatchRequest.class);
        assertThat(partial.withStandardStages()).isNull();
    }

    @Test
    @DisplayName("el alta de una casa lee la plantilla opcional")
    void createUnitRequest() {
        assertThat(JSON.readValue("""
                {"code":"Casa 7","withStandardStages":true}""", CreateUnitRequest.class).shouldApplyStandardStages()).isTrue();
        assertThat(JSON.readValue("""
                {"code":"Casa 7"}""", CreateUnitRequest.class).shouldApplyStandardStages()).isFalse();
    }

    @Test
    @DisplayName("renombrar una casa y su ficha de venta se leen, completas o parciales")
    void unitAndSalesSheet() {
        UpdateUnitRequest rename = JSON.readValue("""
                {"code":"Lote 14"}""", UpdateUnitRequest.class);
        assertThat(rename.code()).isEqualTo("Lote 14");
        assertThat(rename.description()).isNull();

        UpdateSalesSheetRequest sheet = JSON.readValue("""
                {"modelName":"Modelo Aromo","lotAreaM2":600,"coveredAreaM2":186.5,"semiCoveredAreaM2":32,
                 "rooms":5,"toilettes":1,"floors":2,"parkingSpaces":2,"orientation":"Norte",
                 "longDescription":"Casa de dos plantas","finishes":"Porcelanato",
                 "listPrice":189000,"priceCurrency":"USD"}""", UpdateSalesSheetRequest.class);
        assertThat(sheet.coveredAreaM2()).isEqualByComparingTo("186.5");
        assertThat(sheet.floors()).isEqualTo(2);
        assertThat(sheet.priceCurrency()).isEqualTo("USD");

        UpdateSalesSheetRequest partial = JSON.readValue("""
                {"rooms":3}""", UpdateSalesSheetRequest.class);
        assertThat(partial.rooms()).isEqualTo(3);
        assertThat(partial.listPrice()).isNull();
    }

    @Test
    @DisplayName("los datos del desarrollo leen el tipo, la entrega como año y mes, y las listas")
    void developmentDetails() {
        UpdateDevelopmentRequest request = JSON.readValue("""
                {"name":"Barrio Los Aromos","developmentType":"BARRIO_CERRADO","landAreaM2":42000,
                 "startedOn":"2026-01-12","estimatedDelivery":"2027-06",
                 "amenities":["Pileta","SUM"],"services":["Gas natural"]}""", UpdateDevelopmentRequest.class);
        assertThat(request.developmentType()).isEqualTo(DevelopmentType.BARRIO_CERRADO);
        assertThat(request.startedOn()).isEqualTo(LocalDate.of(2026, 1, 12));
        assertThat(request.estimatedDelivery()).isEqualTo(YearMonth.of(2027, 6));
        assertThat(request.amenities()).containsExactly("Pileta", "SUM");

        UpdateDevelopmentRequest partial = JSON.readValue("""
                {"name":"Barrio"}""", UpdateDevelopmentRequest.class);
        assertThat(partial.amenities()).isNull();
        assertThat(partial.developmentType()).isNull();
    }

    @Test
    @DisplayName("la etapa lee descripción, fechas previstas y la fecha de un cambio de estado")
    void stagePlanAndStatus() {
        UpdateStagePlanRequest plan = JSON.readValue("""
                {"description":"Cerco y obrador","plannedStart":"2026-03-01","plannedEnd":"2026-03-10"}""",
                UpdateStagePlanRequest.class);
        assertThat(plan.description()).isEqualTo("Cerco y obrador");
        assertThat(plan.plannedEnd()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(plan.shouldClearPlannedDates()).isFalse();

        UpdateStageStatusRequest status = JSON.readValue("""
                {"status":"COMPLETED","effectiveOn":"2026-03-12"}""", UpdateStageStatusRequest.class);
        assertThat(status.effectiveOn()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(JSON.readValue("""
                {"status":"IN_PROGRESS"}""", UpdateStageStatusRequest.class).effectiveOn()).isNull();
    }

    @Test
    @DisplayName("la asignación de un encargado lee sus casas; sin casas es todo el desarrollo")
    void supervisorAssignment() {
        UUID person = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
        DevelopmentTeamDtos.AssignSupervisorRequest houses = JSON.readValue("""
                {"userId":"%s","unitIds":["%s"]}""".formatted(person, UNIT), DevelopmentTeamDtos.AssignSupervisorRequest.class);
        assertThat(houses.unitIds()).containsExactly(UNIT);
        assertThat(JSON.readValue("""
                {"userId":"%s"}""".formatted(person), DevelopmentTeamDtos.AssignSupervisorRequest.class).unitIds()).isNull();
        assertThat(JSON.readValue("""
                {"unitIds":[]}""", DevelopmentTeamDtos.SupervisorScopeRequest.class).unitIds()).isEmpty();
    }

    @Test
    @DisplayName("avance, foto, gasto y audio llevan la casa opcional")
    void unitScopedRequests() {
        assertThat(JSON.readValue("""
                {"entryDate":"2026-03-05","description":"Obrador","unitId":"%s"}""".formatted(UNIT),
                CreateProgressEntryRequest.class).unitId()).isEqualTo(UNIT);
        assertThat(JSON.readValue("""
                {"entryDate":"2026-03-05","description":"Cordón cuneta"}""",
                CreateProgressEntryRequest.class).unitId()).isNull();
        assertThat(JSON.readValue("""
                {"filename":"fachada.jpg","contentType":"image/jpeg","unitId":"%s"}""".formatted(UNIT),
                RequestUploadRequest.class).unitId()).isEqualTo(UNIT);
        assertThat(JSON.readValue("""
                {"incurredOn":"2026-03-05","amount":1500.50,"unitId":"%s"}""".formatted(UNIT),
                CreateExpenseRequest.class).unitId()).isEqualTo(UNIT);
        assertThat(JSON.readValue("""
                {"audioFilename":"parte.ogg","audioContentType":"audio/ogg","unitId":"%s"}""".formatted(UNIT),
                CreateAudioExtractionRequest.class).unitId()).isEqualTo(UNIT);
    }
}
