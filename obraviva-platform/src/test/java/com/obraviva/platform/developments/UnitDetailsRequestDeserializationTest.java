package com.obraviva.platform.developments;

import com.obraviva.platform.developments.UnitDetailsDtos.RequestCoverUploadRequest;
import com.obraviva.platform.developments.UnitDetailsDtos.UpdateSalesStatusRequest;
import com.obraviva.platform.developments.UnitDetailsDtos.UpdateUnitDetailsRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deserialización de la ficha desde el JSON que manda el cliente. La entrega estimada
 * viaja como año y mes, que es la precisión real con la que se promete una fecha de obra.
 */
class UnitDetailsRequestDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("la ficha completa se lee con el mes de entrega")
    void fullBodyIsAccepted() {
        UpdateUnitDetailsRequest request = JSON.readValue(
                """
                {"areaM2":126.5,"bedrooms":3,"bathrooms":2,
                 "features":["Cochera","Patio"],"estimatedDelivery":"2027-03"}""",
                UpdateUnitDetailsRequest.class);

        assertThat(request.areaM2()).isEqualByComparingTo("126.5");
        assertThat(request.bedrooms()).isEqualTo(3);
        assertThat(request.bathrooms()).isEqualTo(2);
        assertThat(request.features()).containsExactly("Cochera", "Patio");
        assertThat(request.estimatedDelivery()).isEqualTo(YearMonth.of(2027, 3));
    }

    @Test
    @DisplayName("un cuerpo parcial se acepta, sin componentes primitivos que lo rechacen")
    void partialBodyIsAccepted() {
        UpdateUnitDetailsRequest request = JSON.readValue(
                """
                {"bedrooms":2}""",
                UpdateUnitDetailsRequest.class);

        assertThat(request.bedrooms()).isEqualTo(2);
        assertThat(request.areaM2()).isNull();
        assertThat(request.bathrooms()).isNull();
        assertThat(request.features()).isNull();
        assertThat(request.estimatedDelivery()).isNull();
    }

    @Test
    @DisplayName("el pedido de subida de portada se lee")
    void coverUploadRequestIsAccepted() {
        RequestCoverUploadRequest request = JSON.readValue(
                """
                {"filename":"render.jpg","contentType":"image/jpeg"}""",
                RequestCoverUploadRequest.class);

        assertThat(request.filename()).isEqualTo("render.jpg");
        assertThat(request.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("el estado comercial llega como texto del enum")
    void salesStatusBodyIsAccepted() {
        UpdateSalesStatusRequest request = JSON.readValue(
                """
                {"status":"RESERVED"}""",
                UpdateSalesStatusRequest.class);

        assertThat(request.status()).isEqualTo(UnitSalesStatus.RESERVED);
    }
}
