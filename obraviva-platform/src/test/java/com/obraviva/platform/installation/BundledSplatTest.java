package com.obraviva.platform.installation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Captura de muestra que viaja en el jar")
class BundledSplatTest {

    @Test
    @DisplayName("se copia a un archivo en disco, que es lo que pide el almacenamiento")
    void copiesTheResourceToDisk() throws IOException {
        String resource = "demo/images/obra-01-terreno.svg";

        Path file = DemoDataSeeder.bundledSplat(resource);

        assertThat(file).isRegularFile();
        assertThat(Files.readAllBytes(file))
                .isEqualTo(new ClassPathResource(resource).getContentAsByteArray());
    }

    @Test
    @DisplayName("la muestra de la demostración viaja en el repositorio")
    void theDemoSampleIsBundled() throws IOException {
        Path file = DemoDataSeeder.bundledSplat(DemoDataSeeder.BUNDLED_SPLAT);

        assertThat(file).isNotNull();
        assertThat(Files.size(file)).isPositive();
    }

    @Test
    @DisplayName("si no está en el jar, no hay recorridos y no se rompe la carga")
    void missingResourceIsNull() {
        assertThat(DemoDataSeeder.bundledSplat("demo/no-existe.spz")).isNull();
    }
}
