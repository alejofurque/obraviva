package com.obraviva.platform.comparisons;

import com.obraviva.platform.comparisons.ComparisonDtos.CreateComparisonRequest;
import com.obraviva.platform.comparisons.ComparisonDtos.RequestRenderUploadRequest;
import com.obraviva.platform.comparisons.ComparisonDtos.UpdateComparisonRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Cuerpos del comparador tal como los manda el cliente, con los opcionales ausentes. */
class ComparisonRequestsDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("una comparación de la obra llega sin unidad")
    void createWithoutUnit() {
        CreateComparisonRequest request = JSON.readValue("""
                {"title":"Fachada"}""", CreateComparisonRequest.class);

        assertThat(request.title()).isEqualTo("Fachada");
        assertThat(request.unitId()).isNull();
    }

    @Test
    @DisplayName("la actualización acepta quitar la foto con un nulo explícito")
    void updateWithNullPhoto() {
        UpdateComparisonRequest request = JSON.readValue("""
                {"title":"Living","unitId":null,"photoMediaId":null}""", UpdateComparisonRequest.class);

        assertThat(request.photoMediaId()).isNull();
    }

    @Test
    @DisplayName("el pedido de subida del render trae nombre y tipo")
    void renderUpload() {
        RequestRenderUploadRequest request = JSON.readValue("""
                {"filename":"render.jpg","contentType":"image/jpeg"}""", RequestRenderUploadRequest.class);

        assertThat(request.contentType()).isEqualTo("image/jpeg");
    }
}
