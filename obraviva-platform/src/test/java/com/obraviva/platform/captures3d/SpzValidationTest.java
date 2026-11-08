package com.obraviva.platform.captures3d;

import com.obraviva.platform.support.SpzFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validación de un SPZ por su encabezado, con archivos armados como los exporta Scaniverse:
 * un gzip con {@code NGSP}, versión, puntos y el resto de los datos.
 */
class SpzValidationTest {

    private static byte[] spz(String magic, int version, int points, int payloadBytes) {
        return SpzFixtures.spz(magic, version, points, payloadBytes);
    }

    @Test
    @DisplayName("acepta las versiones 2 y 3, aunque sólo llegue el comienzo del archivo")
    void acceptsVersionsTwoAndThreeFromPrefix() {
        byte[] large = spz("NGSP", 3, 358_538, 400_000);
        byte[] prefix = Arrays.copyOf(large, Math.min(large.length, SpzValidation.PREFIX_BYTES));

        assertThat(large.length).isGreaterThan(SpzValidation.PREFIX_BYTES);
        assertThat(SpzValidation.problemOfPrefix(prefix)).isEmpty();
        assertThat(SpzValidation.problemOfPrefix(spz("NGSP", 2, 1000, 100))).isEmpty();
    }

    @Test
    @DisplayName("rechaza lo que no es gzip, un gzip sin NGSP, otra versión y un SPZ sin puntos")
    void rejectsInvalidHeaders() {
        assertThat(SpzValidation.problemOfPrefix("ply\nformat binary".getBytes(StandardCharsets.US_ASCII)))
                .hasValueSatisfying(message -> assertThat(message).contains("no es un SPZ válido"));
        assertThat(SpzValidation.problemOfPrefix(spz("ABCD", 3, 10, 100))).isPresent();
        assertThat(SpzValidation.problemOfPrefix(spz("NGSP", 4, 10, 100)))
                .hasValueSatisfying(message -> assertThat(message).contains("versión 4"));
        assertThat(SpzValidation.problemOfPrefix(spz("NGSP", 3, 0, 100)))
                .hasValueSatisfying(message -> assertThat(message).contains("no tiene puntos"));
        assertThat(SpzValidation.problemOfPrefix(new byte[0])).isPresent();
        assertThat(SpzValidation.problemOfPrefix(new byte[] {0x1f, (byte) 0x8b, 8, 0})).isPresent();
    }

    @Test
    @DisplayName("en el alta: la extensión .spz y hasta 50 MB")
    void declaredNameAndSize() {
        assertThat(SpzValidation.problemOfDeclared("Living.SPZ", 7_000_000L)).isEmpty();
        assertThat(SpzValidation.problemOfDeclared("living.ply", 7_000_000L)).isPresent();
        assertThat(SpzValidation.problemOfDeclared("living.spz", 50L * 1024 * 1024)).isEmpty();
        assertThat(SpzValidation.problemOfDeclared("living.spz", 50L * 1024 * 1024 + 1))
                .hasValueSatisfying(message -> assertThat(message).contains("máximo es 50 MB"));
        assertThat(SpzValidation.problemOfDeclared("living.spz", 0L)).isPresent();
    }
}
