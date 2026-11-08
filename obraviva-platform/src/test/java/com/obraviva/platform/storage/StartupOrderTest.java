package com.obraviva.platform.storage;

import com.obraviva.platform.installation.DemoDataSeeder;
import com.obraviva.platform.installation.InstallationSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.AnnotationUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El bucket tiene que existir antes de que los seeders suban archivos. Spring ejecuta todos
 * los arranques (CommandLineRunner y ApplicationRunner) juntos y ordenados por {@code @Order};
 * sin orden el creador del bucket iba último, y con un MinIO recién creado la carga de
 * demostración fallaba en silencio dejando cuentas sin desarrollos.
 */
class StartupOrderTest {

    private static int orderOf(Class<?> type) {
        Order order = AnnotationUtils.findAnnotation(type, Order.class);
        assertThat(order).as("%s necesita @Order explícito", type.getSimpleName()).isNotNull();
        return order.value();
    }

    @Test
    @DisplayName("el bucket se crea antes que la instalación y la carga de demostración")
    void bucketIsCreatedBeforeTheSeeders() {
        assertThat(orderOf(StorageBucketInitializer.class))
                .isLessThan(orderOf(InstallationSeeder.class))
                .isLessThan(orderOf(DemoDataSeeder.class));
    }

    @Test
    @DisplayName("la instalación va antes que la demostración, que necesita al responsable y su empresa")
    void installationRunsBeforeDemo() {
        assertThat(orderOf(InstallationSeeder.class)).isLessThan(orderOf(DemoDataSeeder.class));
    }
}
