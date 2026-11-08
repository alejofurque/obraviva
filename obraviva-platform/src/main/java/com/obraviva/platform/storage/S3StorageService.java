package com.obraviva.platform.storage;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

@Service
public class S3StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public S3StorageService(
            S3Client s3Client, S3Presigner presigner, StorageProperties properties) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public String presignUpload(String key, String contentType) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(properties.uploadUrlTtl())
                .putObjectRequest(objectRequest)
                .build();

        return presigner.presignPutObject(presignRequest).url().toString();
    }

    @Override
    public String presignDownload(String key) {
        return presignDownload(key, properties.downloadUrlTtl());
    }

    @Override
    public String presignDownload(String key, Duration ttl) {
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(objectRequest)
                .build();

        return presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Una operación HEAD no trae cuerpo, así que el SDK no siempre puede leer el
     * código de error del proveedor: según el caso lanza {@link NoSuchKeyException}
     * o un {@link S3Exception} genérico con status 404. Se contemplan los dos, o
     * un objeto ausente se propagaría como falla técnica.
     */
    @Override
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException(
                    "Error consultando el objeto '" + key + "' en el almacenamiento", e);
        }
    }

    @Override
    public long size(String key) {
        try {
            return s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build()).contentLength();
        } catch (S3Exception e) {
            throw new StorageException(
                    "No se pudo consultar el tamaño del objeto '" + key + "' en el almacenamiento", e);
        }
    }

    /** Lectura parcial con un encabezado Range: el almacenamiento manda sólo esos bytes. */
    @Override
    public byte[] readPrefix(String key, int maxBytes) {
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .range("bytes=0-" + (maxBytes - 1))
                    .build()).asByteArray();
        } catch (S3Exception e) {
            throw new StorageException(
                    "No se pudo leer el comienzo del objeto '" + key + "' en el almacenamiento", e);
        }
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build());
    }

    @Override
    public void download(String key, Path destination) {
        try {
            Path parent = destination.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build();

            // getObject(request, Path) falla si el archivo ya existe; se descarga a un
            // temporal y se mueve, para que el método sea idempotente ante reintentos.
            Path temp = Files.createTempFile("obraviva-download-", ".part");
            try {
                Files.deleteIfExists(temp);
                s3Client.getObject(request, temp);
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }

        } catch (NoSuchKeyException e) {
            throw new StorageException(
                    "El objeto '" + key + "' no existe en el almacenamiento", e);
        } catch (S3Exception e) {
            throw new StorageException(
                    "Error descargando el objeto '" + key + "' del almacenamiento", e);
        } catch (IOException e) {
            throw new StorageException(
                    "Error escribiendo el objeto '" + key + "' en disco: " + destination, e);
        }
    }

    @Override
    public void upload(Path source, String key, String contentType) {
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new StorageException("No existe o no se puede leer el archivo a subir: " + source);
        }

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .contentType(contentType)
                    .contentLength(Files.size(source))
                    .build();

            s3Client.putObject(request, RequestBody.fromFile(source));

        } catch (S3Exception e) {
            throw new StorageException(
                    "Error subiendo el archivo al almacenamiento con clave '" + key + "'", e);
        } catch (IOException e) {
            throw new StorageException("Error leyendo el archivo a subir: " + source, e);
        }
    }
}