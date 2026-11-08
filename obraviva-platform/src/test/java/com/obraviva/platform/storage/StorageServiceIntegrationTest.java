package com.obraviva.platform.storage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el cableado del adaptador de almacenamiento. La firma de
 * URLs es un cálculo local: no requiere que MinIO esté corriendo.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "obraviva.storage.init-bucket=false")
class StorageServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private StorageService storageService;

    @Test
    void presignedUploadUrlTargetsBucketAndKey() {
        String url = storageService.presignUpload("captures/test.spz", "application/octet-stream");

        assertThat(url).contains("obraviva-media");
        assertThat(url).contains("captures/test.spz");
        assertThat(url).contains("X-Amz-Signature");
        assertThat(url).startsWith("http://localhost:9000");
    }

    @Test
    void presignedDownloadUrlIsGenerated() {
        String url = storageService.presignDownload("captures/test.spz");

        assertThat(url).contains("obraviva-media");
        assertThat(url).contains("X-Amz-Expires");
    }
}