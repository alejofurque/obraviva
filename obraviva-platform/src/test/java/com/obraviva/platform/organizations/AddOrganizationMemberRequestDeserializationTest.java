package com.obraviva.platform.organizations;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deserialización del alta de un encargado desde el JSON que manda el cliente: con
 * nombre cuando la cuenta es nueva, y sólo el correo cuando ya existe.
 */
class AddOrganizationMemberRequestDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("un cuerpo con correo y nombre se acepta")
    void bodyWithNameIsAccepted() {
        AddOrganizationMemberRequest request = JSON.readValue(
                """
                {"email":"raul@obraviva.test","fullName":"Raúl"}""",
                AddOrganizationMemberRequest.class);

        assertThat(request.email()).isEqualTo("raul@obraviva.test");
        assertThat(request.fullName()).isEqualTo("Raúl");
    }

    @Test
    @DisplayName("un cuerpo con sólo el correo se acepta")
    void bodyWithOnlyEmailIsAccepted() {
        AddOrganizationMemberRequest request = JSON.readValue(
                """
                {"email":"raul@obraviva.test"}""",
                AddOrganizationMemberRequest.class);

        assertThat(request.email()).isEqualTo("raul@obraviva.test");
        assertThat(request.fullName()).isNull();
    }
}
