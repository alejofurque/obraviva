package com.obraviva.platform.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MinIOContainer;

/**
 * Armado de tests: un MinIO propio de la suite, para que ningún test toque el de desarrollo.
 * Lo necesitan los tests que arrancan el contexto con el bucket ({@code StorageBucketInitializer})
 * o que mueven bytes de verdad.
 *
 * <p>Uno solo para toda la corrida, arrancado la primera vez que una clase lo pide y apagado por
 * Testcontainers al terminar: Spring guarda los contextos entre clases, y un contenedor por clase
 * dejaría a un contexto guardado apuntando a un MinIO que ya no existe. Cada test usa claves
 * propias, así que compartirlo no los mezcla.
 *
 * <p>Uso, en la clase de test:
 * <pre>{@code
 * @DynamicPropertySource
 * static void storage(DynamicPropertyRegistry registry) {
 *     TestMinio.register(registry);
 * }
 * }</pre>
 */
public final class TestMinio {

    /** La misma imagen que {@code docker-compose.yml}. */
    private static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

    private TestMinio() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("obraviva.storage.endpoint", MINIO::getS3URL);
        registry.add("obraviva.storage.access-key", MINIO::getUserName);
        registry.add("obraviva.storage.secret-key", MINIO::getPassword);
    }

    private static synchronized void start() {
        if (!MINIO.isRunning()) {
            MINIO.start();
        }
    }
}
