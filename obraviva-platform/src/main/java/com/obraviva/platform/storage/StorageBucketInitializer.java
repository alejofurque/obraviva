package com.obraviva.platform.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Crea el bucket al arrancar si no existe. Se desactiva en tests
 * (obraviva.storage.init-bucket=false): firmar URLs no requiere
 * conexiÃ³n, asÃ­ que los tests no dependen de MinIO corriendo.
 *
 * <p>Corre antes que cualquier otro arranque ({@link #ORDER}): los seeders de instalaciÃ³n y
 * de demostraciÃ³n suben archivos, y con un MinIO reciÃ©n creado el bucket todavÃ­a no existe.
 * Sin orden explÃ­cito Spring lo ejecuta Ãºltimo, la carga de demostraciÃ³n falla en silencio
 * y el sistema queda con cuentas pero sin desarrollos.
 */
@Order(StorageBucketInitializer.ORDER)
@Component
@ConditionalOnProperty(name = "obraviva.storage.init-bucket", havingValue = "true")
public class StorageBucketInitializer implements CommandLineRunner {

    /** Antes que InstallationSeeder (10) y DemoDataSeeder (20). */
    public static final int ORDER = 0;

    private static final Logger log = LoggerFactory.getLogger(StorageBucketInitializer.class);

    private final S3Client s3Client;
    private final StorageProperties properties;

    public StorageBucketInitializer(S3Client s3Client, StorageProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        String bucket = properties.bucket();
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Bucket de almacenamiento disponible: {}", bucket);
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Bucket de almacenamiento creado: {}", bucket);
        }
    }
}