package com.obraviva.platform.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ChangePasswordRequestDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("el cambio de contraseña se lee desde el JSON del cliente")
    void bodyIsAccepted() {
        ChangePasswordRequest request = JSON.readValue(
                """
                {"currentPassword":"k7qp-2mxa-9lrt","newPassword":"miClaveNueva1"}""",
                ChangePasswordRequest.class);

        assertThat(request.currentPassword()).isEqualTo("k7qp-2mxa-9lrt");
        assertThat(request.newPassword()).isEqualTo("miClaveNueva1");
    }
}
