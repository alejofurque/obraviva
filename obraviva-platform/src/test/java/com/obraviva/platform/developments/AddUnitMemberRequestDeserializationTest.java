package com.obraviva.platform.developments;

import com.obraviva.platform.developments.UnitMemberDtos.AddUnitMemberRequest;
import com.obraviva.platform.developments.UnitMemberDtos.SetPasswordRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deserialización del alta de un comprador. El cuerpo histórico sólo traía el
 * correo y tiene que seguir funcionando ahora que el nombre es opcional.
 */
class AddUnitMemberRequestDeserializationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("el cuerpo histórico, sólo con correo, se sigue aceptando")
    void legacyBodyIsAccepted() {
        AddUnitMemberRequest request = JSON.readValue(
                """
                {"email":"compradora@obraviva.test"}""",
                AddUnitMemberRequest.class);

        assertThat(request.email()).isEqualTo("compradora@obraviva.test");
        assertThat(request.fullName()).isNull();
    }

    @Test
    @DisplayName("un cuerpo con nombre se acepta para crear la cuenta")
    void bodyWithNameIsAccepted() {
        AddUnitMemberRequest request = JSON.readValue(
                """
                {"email":"compradora@obraviva.test","fullName":"Marta Compradora"}""",
                AddUnitMemberRequest.class);

        assertThat(request.fullName()).isEqualTo("Marta Compradora");
    }

    @Test
    @DisplayName("con contraseña, llega tal cual")
    void bodyWithPasswordIsAccepted() {
        AddUnitMemberRequest request = JSON.readValue(
                """
                {"email":"compradora@obraviva.test","fullName":"Marta Compradora","password":"Obra-2026!"}""",
                AddUnitMemberRequest.class);

        assertThat(request.password()).isEqualTo("Obra-2026!");
    }

    @Test
    @DisplayName("con la contraseña vacía, como la manda el formulario sin completar, queda nula")
    void emptyPasswordBecomesNull() {
        AddUnitMemberRequest request = JSON.readValue(
                """
                {"email":"compradora@obraviva.test","fullName":"Marta Compradora","password":""}""",
                AddUnitMemberRequest.class);

        assertThat(request.password()).isNull();
    }

    @Test
    @DisplayName("sin el campo de contraseña, queda nula")
    void missingPasswordIsNull() {
        AddUnitMemberRequest request = JSON.readValue(
                """
                {"email":"compradora@obraviva.test","fullName":"Marta Compradora"}""",
                AddUnitMemberRequest.class);

        assertThat(request.password()).isNull();
    }

    @Test
    @DisplayName("la nueva contraseña de un comprador: con campo, vacía y sin campo")
    void setPasswordRequest() {
        assertThat(JSON.readValue("""
                {"password":"Obra-2026!"}""", SetPasswordRequest.class).password()).isEqualTo("Obra-2026!");
        assertThat(JSON.readValue("""
                {"password":""}""", SetPasswordRequest.class).password()).isNull();
        assertThat(JSON.readValue("{}", SetPasswordRequest.class).password()).isNull();
    }
}
