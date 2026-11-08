package com.obraviva.platform.captures3d;

import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureFromVideoRequest;
import com.obraviva.platform.captures3d.CaptureDtos.CreateCaptureRequest;
import com.obraviva.platform.captures3d.CaptureDtos.UpdateCaptureRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deserialización de los pedidos de captura desde el JSON del cliente. Los cuerpos
 * anteriores, sin ambiente, tienen que seguir aceptándose.
 */
class CaptureRequestsDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("el alta sin ambiente se sigue aceptando")
    void legacyCreateBodyIsAccepted() {
        CreateCaptureRequest request = JSON.readValue(
                """
                {"captureDate":"2026-09-10","description":"Recorrido"}""",
                CreateCaptureRequest.class);

        assertThat(request.captureDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(request.zone()).isNull();
    }

    @Test
    @DisplayName("el alta desde video lee el ambiente")
    void videoBodyWithZoneIsAccepted() {
        CreateCaptureFromVideoRequest request = JSON.readValue(
                """
                {"captureDate":"2026-09-10","videoFilename":"a.mp4","videoContentType":"video/mp4","zone":"Fachada"}""",
                CreateCaptureFromVideoRequest.class);

        assertThat(request.zone()).isEqualTo("Fachada");
        assertThat(request.videoContentType()).isEqualTo("video/mp4");
    }

    @Test
    @DisplayName("la corrección lee sus cuatro campos")
    void updateBodyIsAccepted() {
        UpdateCaptureRequest request = JSON.readValue(
                """
                {"captureDate":"2026-09-12","description":"Corregida","zone":"Galería"}""",
                UpdateCaptureRequest.class);

        assertThat(request.captureDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(request.description()).isEqualTo("Corregida");
        assertThat(request.zone()).isEqualTo("Galería");
        assertThat(request.stageId()).isNull();
    }
}
