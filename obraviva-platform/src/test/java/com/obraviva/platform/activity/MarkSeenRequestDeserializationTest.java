package com.obraviva.platform.activity;

import com.obraviva.platform.activity.ActivityDtos.MarkSeenRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** "Visto hasta" desde el JSON que manda Novedades: el instante del ítem más nuevo mostrado. */
class MarkSeenRequestDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    @DisplayName("el instante llega con microsegundos, como lo devuelve el feed")
    void instantWithMicros() {
        MarkSeenRequest request = JSON.readValue("""
                {"until":"2026-10-20T13:52:15.000337Z"}""", MarkSeenRequest.class);

        assertThat(request.until()).isEqualTo(Instant.parse("2026-10-20T13:52:15.000337Z"));
    }

    @Test
    @DisplayName("sin until queda nulo y lo rechaza la validación, no la construcción")
    void missingUntil() {
        assertThat(JSON.readValue("{}", MarkSeenRequest.class).until()).isNull();
    }
}
