package com.obraviva.platform.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica la transferencia real de bytes contra MinIO. A diferencia del resto de
 * la suite, este test SÍ necesita MinIO corriendo (docker compose up -d): mockear
 * el SDK verificaría que Mockito devuelve lo que se le indicó, no que el protocolo
 * funcione. El acceso "path style" contra MinIO es justamente la clase de detalle
 * que sólo se rompe contra el servidor real.
 *
 * <p>Si MinIO no responde, la clase se omite entera. Ojo al leer el resultado: eso
 * aparece como "Tests run: 0" con build exitoso. El conteo esperado es 6.
 */
@SpringBootTest
@Testcontainers
class StorageTransferIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private StorageService storageService;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void requireMinio() {
        Assumptions.assumeTrue(
                minioResponds(),
                "MinIO no responde en http://localhost:9000: se omiten los tests de transferencia. "
                        + "Levantalo con 'docker compose up -d'.");
    }

    private String randomKey() {
        return "tests/transfer/" + UUID.randomUUID() + ".bin";
    }

    @Test
    @DisplayName("un archivo subido se recupera con el mismo contenido")
    void uploadedFileIsRetrievedIntact() throws IOException {
        String key = randomKey();
        String content = "contenido de prueba con acentos: ñáéíóú";

        Path source = tempDir.resolve("origen.txt");
        Files.writeString(source, content, StandardCharsets.UTF_8);

        storageService.upload(source, key, "text/plain");

        Path destination = tempDir.resolve("destino.txt");
        storageService.download(key, destination);

        assertThat(Files.readString(destination, StandardCharsets.UTF_8)).isEqualTo(content);
        assertThat(Files.size(destination)).isEqualTo(Files.size(source));

        storageService.delete(key);
    }

    @Test
    @DisplayName("exists distingue un objeto presente de uno ausente")
    void existsReflectsObjectPresence() throws IOException {
        String key = randomKey();
        assertThat(storageService.exists(key)).isFalse();

        Path source = tempDir.resolve("presencia.txt");
        Files.writeString(source, "algo");
        storageService.upload(source, key, "text/plain");

        assertThat(storageService.exists(key)).isTrue();

        storageService.delete(key);
        assertThat(storageService.exists(key)).isFalse();
    }

    @Test
    @DisplayName("descargar un objeto inexistente falla con StorageException")
    void downloadOfMissingObjectFails() {
        Path destination = tempDir.resolve("nunca.bin");

        assertThatThrownBy(() -> storageService.download(randomKey(), destination))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("la descarga sobrescribe un destino preexistente")
    void downloadOverwritesExistingDestination() throws IOException {
        String key = randomKey();

        Path source = tempDir.resolve("nuevo.txt");
        Files.writeString(source, "contenido nuevo");
        storageService.upload(source, key, "text/plain");

        // Un reintento del worker se encuentra con el archivo parcial del intento previo.
        Path destination = tempDir.resolve("preexistente.txt");
        Files.writeString(destination, "basura de un intento anterior mucho más larga");

        storageService.download(key, destination);

        assertThat(Files.readString(destination)).isEqualTo("contenido nuevo");

        storageService.delete(key);
    }

    @Test
    @DisplayName("size devuelve el tamaño real y readPrefix sólo el comienzo, o todo si el objeto es más chico")
    void sizeAndPrefix() throws IOException {
        String key = randomKey();
        byte[] content = new byte[100_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        Path source = tempDir.resolve("grande.bin");
        Files.write(source, content);
        storageService.upload(source, key, "application/octet-stream");

        assertThat(storageService.size(key)).isEqualTo(100_000L);
        byte[] prefix = storageService.readPrefix(key, 65_536);
        assertThat(prefix).hasSize(65_536);
        assertThat(prefix).isEqualTo(java.util.Arrays.copyOf(content, 65_536));
        assertThat(storageService.readPrefix(key, 200_000)).hasSize(100_000);

        storageService.delete(key);
    }

    @Test
    @DisplayName("size y readPrefix de un objeto inexistente fallan con StorageException")
    void sizeAndPrefixOfMissingObjectFail() {
        String key = randomKey();

        assertThatThrownBy(() -> storageService.size(key)).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> storageService.readPrefix(key, 16)).isInstanceOf(StorageException.class);
    }

    private static boolean minioResponds() {
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create("http://localhost:9000/minio/health/live")
                            .toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            int status = connection.getResponseCode();
            connection.disconnect();
            return status > 0;
        } catch (IOException e) {
            return false;
        }
    }
}