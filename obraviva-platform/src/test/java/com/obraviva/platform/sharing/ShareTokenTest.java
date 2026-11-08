package com.obraviva.platform.sharing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShareTokenTest {

    @Test
    @DisplayName("los tokens no se repiten")
    void tokensAreUnique() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            generated.add(ShareToken.generate());
        }
        assertThat(generated).hasSize(1000);
    }

    @Test
    @DisplayName("el token es apto para una URL y tiene entropía suficiente")
    void tokenIsUrlSafe() {
        String token = ShareToken.generate();

        // 32 bytes en base64 sin relleno: 43 caracteres.
        assertThat(token).hasSize(43);
        // Sin caracteres que obliguen a escapar al pegarlo en un mensaje.
        assertThat(token).doesNotContain("+", "/", "=");
    }

    @Test
    @DisplayName("el hash es estable y entra en la columna")
    void hashIsStableAndFits() {
        String token = ShareToken.generate();

        assertThat(ShareToken.hash(token)).isEqualTo(ShareToken.hash(token));
        assertThat(ShareToken.hash(token)).hasSize(64);
    }

    @Test
    @DisplayName("tokens distintos producen hashes distintos")
    void differentTokensHashDifferently() {
        assertThat(ShareToken.hash(ShareToken.generate()))
                .isNotEqualTo(ShareToken.hash(ShareToken.generate()));
    }
}