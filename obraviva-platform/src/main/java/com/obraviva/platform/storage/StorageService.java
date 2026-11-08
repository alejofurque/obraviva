package com.obraviva.platform.storage;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Abstracción del almacenamiento de archivos. La implementación actual
 * habla el protocolo S3 (MinIO en local, S3 u otro compatible en
 * producción); ningún módulo de dominio conoce al proveedor.
 *
 * <p>Sobre las operaciones de transferencia: la regla de que el backend nunca
 * transporta bytes aplica al camino cliente ↔ almacenamiento, que sigue siendo
 * directo por URL firmada. Los métodos {@code download} y {@code upload} existen
 * para el procesamiento del lado del servidor (preprocesamiento de video,
 * conversión de formatos), donde el archivo tiene que estar en disco sí o sí.
 * No se usan para servir contenido al cliente.
 */
public interface StorageService {

    /**
     * URL temporal para que el cliente suba un archivo directamente al
     * almacenamiento, sin pasar por el backend.
     */
    String presignUpload(String key, String contentType);

    /** URL temporal de lectura para un objeto existente, con la vigencia configurada. */
    String presignDownload(String key);

    /**
     * URL temporal de lectura con vigencia explícita.
     *
     * <p>La usa la vista pública. Una URL firmada sobrevive a la revocación del
     * enlace que la originó —está firmada, el almacenamiento no consulta nada— así
     * que su vigencia acota la ventana en la que un enlace ya revocado todavía sirve
     * para bajar el archivo. Para el contenido privado del panel, la vigencia larga
     * por omisión está bien; para lo que sale al mundo, no.
     */
    String presignDownload(String key, Duration ttl);

    boolean exists(String key);

    /**
     * Tamaño real del objeto en bytes. Lo que declara el cliente al pedir la URL no
     * alcanza: la subida va directo al almacenamiento y puede no coincidir.
     *
     * @throws StorageException si el objeto no existe o falla la consulta
     */
    long size(String key);

    /**
     * Los primeros {@code maxBytes} bytes del objeto, o todos si es más chico. Sirve para
     * validar un formato por su encabezado sin bajar el archivo entero.
     *
     * @throws StorageException si el objeto no existe o falla la lectura
     */
    byte[] readPrefix(String key, int maxBytes);

    void delete(String key);

    /**
     * Descarga un objeto a un archivo local, para procesarlo en el servidor.
     * Sobrescribe el destino si ya existe.
     *
     * @throws StorageException si el objeto no existe o falla la transferencia
     */
    void download(String key, Path destination);

    /**
     * Sube un archivo local al almacenamiento, para publicar el resultado de un
     * procesamiento del servidor.
     *
     * @throws StorageException si el archivo no existe o falla la transferencia
     */
    void upload(Path source, String key, String contentType);
}