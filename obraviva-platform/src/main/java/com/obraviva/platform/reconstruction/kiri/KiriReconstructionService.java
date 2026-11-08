package com.obraviva.platform.reconstruction.kiri;

import com.obraviva.platform.reconstruction.ReconstructionException;
import com.obraviva.platform.reconstruction.ReconstructionRejectedException;
import com.obraviva.platform.reconstruction.ReconstructionService;
import com.obraviva.platform.reconstruction.ReconstructionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Adaptador real del servicio de reconstrucción 3DGS de KIRI Engine.
 *
 * <p>Endpoints utilizados, sobre la base {@code https://api.kiriengine.app/api}:
 * <ul>
 *   <li>{@code POST /v1/open/3dgs/video} — envío multipart. Consume un crédito.</li>
 *   <li>{@code GET /v1/open/model/getStatus?serialize=...}</li>
 *   <li>{@code GET /v1/open/model/getModelZip?serialize=...} — devuelve un enlace
 *       de descarga válido por 60 minutos, no el archivo.</li>
 *   <li>{@code GET /v1/open/balance} — los créditos que quedan, en {@code data.balance}.
 *       No consume nada.</li>
 * </ul>
 *
 * <p><strong>Determinación del éxito.</strong> La documentación muestra
 * {@code "code": 0} en todos sus ejemplos, pero la API real devuelve
 * {@code "code": 200} (verificado empíricamente contra el endpoint de saldo). Por eso
 * el éxito se decide por el código HTTP y el campo {@code ok}; {@code code} sólo se
 * registra para diagnóstico y se consulta para identificar errores de negocio 2xxx.
 *
 * <p>Se usa el cliente HTTP del JDK en lugar de la abstracción de Spring por dos
 * motivos: su API está congelada desde Java 11 y no depende de la autoconfiguración
 * de Boot, que ya nos costó varias rondas de errores; y {@code BodyPublishers.ofFile}
 * transmite el archivo por streaming, lo que importa con videos de cien megabytes.
 */
public class KiriReconstructionService implements ReconstructionService {

    private static final Logger log = LoggerFactory.getLogger(KiriReconstructionService.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 0 desactiva la generación de malla: el producto usa el splat, no geometría. */
    private static final String IS_MESH = "0";

    /**
     * 0 desactiva el enmascarado automático de objeto. No es preferencia: esa función
     * aísla un objeto de su fondo, y en una obra el fondo <em>es</em> el sujeto.
     */
    private static final String IS_MASK = "0";

    // Códigos de negocio del cuerpo de la respuesta (sección "500 Code Details").
    private static final int CODE_STILL_PROCESSING = 2000;
    private static final int CODE_GENERATION_FAILED = 2001;
    private static final int CODE_MODEL_EXPIRED = 2002;
    private static final int CODE_MODEL_NOT_FOUND = 2006;
    private static final int CODE_IN_QUEUE = 2008;
    private static final int CODE_VIDEO_REJECTED = 2009;
    private static final int CODE_BAD_FILE_FORMAT = 2010;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final Duration uploadTimeout;
    private final Duration requestTimeout;

    public KiriReconstructionService(
            String baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration uploadTimeout) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "La clave de API de KIRI es obligatoria. Definí KIRI_API_KEY en el archivo .env.");
        }
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
        this.uploadTimeout = uploadTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // --- Operaciones del puerto ---

    @Override
    public String submitVideo(Path videoFile) {
        if (videoFile == null || !Files.isRegularFile(videoFile) || !Files.isReadable(videoFile)) {
            throw new ReconstructionException(
                    "No existe o no se puede leer el video a enviar: " + videoFile);
        }

        String boundary = "obraviva-" + UUID.randomUUID();

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/open/3dgs/video"))
                    .timeout(uploadTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(multipartBody(boundary, videoFile))
                    .build();
        } catch (IOException e) {
            throw new ReconstructionException(
                    "No se pudo preparar el envío del video: " + videoFile, e);
        }

        log.info("Enviando video a reconstrucción ({} bytes). Esta operación consume un crédito.",
                sizeOrUnknown(videoFile));

        JsonNode data = executeForData(request, "envío del video");
        String serialize = textField(data, "serialize");

        if (serialize == null || serialize.isBlank()) {
            throw new ReconstructionException(
                    "El servicio aceptó el video pero no devolvió un identificador de tarea");
        }

        log.info("Video aceptado por el servicio de reconstrucción. Referencia: {}", serialize);
        return serialize;
    }

    @Override
    public ReconstructionStatus checkStatus(String externalReference) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/open/model/getStatus?serialize="
                        + URLEncoder.encode(externalReference, StandardCharsets.UTF_8)))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        JsonNode data = executeForData(request, "consulta de estado");
        Integer status = intField(data, "status");

        if (status == null) {
            throw new ReconstructionException(
                    "La consulta de estado no devolvió el campo esperado");
        }
        return KiriStatusCode.fromCode(status).toDomain();
    }

    @Override
    public void downloadResult(String externalReference, Path destination) {
        // Dos pasos: primero se pide el enlace, después se descarga. El enlace vive
        // 60 minutos, así que no tiene sentido guardarlo entre invocaciones.
        HttpRequest linkRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/open/model/getModelZip?serialize="
                        + URLEncoder.encode(externalReference, StandardCharsets.UTF_8)))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        JsonNode data = executeForData(linkRequest, "obtención del enlace de descarga");
        String modelUrl = textField(data, "modelUrl");

        if (modelUrl == null || modelUrl.isBlank()) {
            throw new ReconstructionException(
                    "El servicio no devolvió un enlace de descarga para la reconstrucción");
        }

        downloadTo(modelUrl, destination);
    }

    /**
     * El saldo de la cuenta. Verificado contra la API real (oct 2026):
     * {@code {"code":200,"msg":"success","data":{"balance":9},"ok":true}}. Si no responde o
     * responde algo que no se entiende, vacío: la pantalla no muestra número.
     */
    @Override
    public OptionalInt availableCredits() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/open/balance"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try {
            Integer balance = intField(executeForData(request, "consulta del saldo"), "balance");
            return balance == null || balance < 0 ? OptionalInt.empty() : OptionalInt.of(balance);
        } catch (ReconstructionException | ReconstructionRejectedException e) {
            log.warn("No se pudo leer el saldo de créditos: {}", e.getMessage());
            return OptionalInt.empty();
        }
    }

    // --- Transporte ---

    /**
     * Descarga el archivo comprimido. El enlace ya viene firmado por el proveedor, así
     * que no se le adjunta la clave de API: mandarla a un dominio de almacenamiento
     * ajeno sería filtrarla sin necesidad.
     */
    private void downloadTo(String modelUrl, Path destination) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(modelUrl))
                .timeout(uploadTimeout)
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() / 100 != 2) {
                throw new ReconstructionException(
                        "La descarga del resultado devolvió HTTP " + response.statusCode());
            }

            Path parent = destination.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (InputStream body = response.body()) {
                Files.copy(body, destination, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Resultado de la reconstrucción descargado en {} ({} bytes)",
                    destination, sizeOrUnknown(destination));

        } catch (IOException e) {
            throw new ReconstructionException("Error descargando el resultado de la reconstrucción", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReconstructionException("La descarga del resultado fue interrumpida", e);
        }
    }

    /** Ejecuta la petición, valida el sobre de respuesta y devuelve el nodo {@code data}. */
    private JsonNode executeForData(HttpRequest request, String operation) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ReconstructionException(
                    "Error de comunicación con el servicio de reconstrucción durante " + operation, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReconstructionException("La operación '" + operation + "' fue interrumpida", e);
        }

        JsonNode envelope = parse(response.body(), operation);
        Integer businessCode = intField(envelope, "code");

        // Los códigos de negocio son más específicos que el estado HTTP: se miran primero.
        if (businessCode != null) {
            rejectOnBusinessCode(businessCode, textField(envelope, "msg"));
        }
        rejectOnHttpStatus(response.statusCode(), operation, response.body());

        if (!isOk(envelope)) {
            throw new ReconstructionException(
                    "El servicio rechazó la operación '" + operation + "'. code=" + businessCode
                            + " msg=" + textField(envelope, "msg"));
        }

        JsonNode data = envelope.get("data");
        if (data == null || data.isNull()) {
            throw new ReconstructionException(
                    "La respuesta de '" + operation + "' no contiene datos");
        }
        return data;
    }

    private static void rejectOnBusinessCode(int code, String message) {
        switch (code) {
            case CODE_VIDEO_REJECTED, CODE_BAD_FILE_FORMAT -> throw new ReconstructionRejectedException(
                    ReconstructionRejectedException.Reason.VIDEO_REJECTED,
                    "El servicio de reconstrucción rechazó el video porque no cumple sus requisitos "
                            + "de formato, resolución o duración.");
            case CODE_MODEL_NOT_FOUND -> throw new ReconstructionRejectedException(
                    ReconstructionRejectedException.Reason.NOT_FOUND,
                    "La tarea de reconstrucción no existe en el servicio.");
            case CODE_MODEL_EXPIRED -> throw new ReconstructionRejectedException(
                    ReconstructionRejectedException.Reason.EXPIRED,
                    "El resultado venció y fue borrado del servicio. Las reconstrucciones se "
                            + "conservan tres días.");
            case CODE_GENERATION_FAILED -> throw new ReconstructionRejectedException(
                    ReconstructionRejectedException.Reason.NOT_FOUND,
                    "La reconstrucción falló en el servicio y no produjo un resultado descargable.");
            // Estos dos no son errores: el resultado todavía no está listo. Se dejan pasar
            // para que el llamador los resuelva por la vía normal de consulta de estado.
            case CODE_STILL_PROCESSING, CODE_IN_QUEUE -> log.debug(
                    "El servicio informa que el modelo sigue en proceso (code {}): {}", code, message);
            default -> {
                // Sin acción: no es un código de error conocido.
            }
        }
    }

    private static void rejectOnHttpStatus(int statusCode, String operation, String body) {
        switch (statusCode) {
            case 401 -> throw new ReconstructionException(
                    "La clave de API de KIRI es inválida o no está autorizada (HTTP 401). "
                            + "Revisá KIRI_API_KEY.");
            case 403 -> throw new ReconstructionRejectedException(
                    ReconstructionRejectedException.Reason.INSUFFICIENT_CREDITS,
                    "No hay créditos suficientes en la cuenta del servicio de reconstrucción. "
                            + "Reintentar no los genera.");
            case 404 -> throw new ReconstructionRejectedException(
                    ReconstructionRejectedException.Reason.NOT_FOUND,
                    "El recurso solicitado no existe en el servicio de reconstrucción.");
            default -> {
                if (statusCode / 100 != 2) {
                    throw new ReconstructionException(
                            "El servicio de reconstrucción devolvió HTTP " + statusCode
                                    + " durante " + operation + ": " + abbreviate(body));
                }
            }
        }
    }

    // --- Multipart armado a mano ---

    private static HttpRequest.BodyPublisher multipartBody(String boundary, Path videoFile)
            throws IOException {

        StringBuilder header = new StringBuilder();
        appendField(header, boundary, "isMesh", IS_MESH);
        appendField(header, boundary, "isMask", IS_MASK);

        header.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"videoFile\"; filename=\"")
                .append(videoFile.getFileName()).append("\"\r\n")
                .append("Content-Type: video/mp4\r\n\r\n");

        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        parts.add(HttpRequest.BodyPublishers.ofString(header.toString(), StandardCharsets.UTF_8));
        // ofFile transmite por streaming: el video no se carga entero en memoria.
        parts.add(HttpRequest.BodyPublishers.ofFile(videoFile));
        parts.add(HttpRequest.BodyPublishers.ofString(
                "\r\n--" + boundary + "--\r\n", StandardCharsets.UTF_8));

        return HttpRequest.BodyPublishers.concat(parts.toArray(new HttpRequest.BodyPublisher[0]));
    }

    private static void appendField(StringBuilder sb, String boundary, String name, String value) {
        sb.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n");
    }

    // --- Lectura de JSON ---

    private static JsonNode parse(String body, String operation) {
        try {
            return JSON.readTree(body);
        } catch (RuntimeException e) {
            throw new ReconstructionException(
                    "Respuesta ininteligible del servicio durante " + operation
                            + ": " + abbreviate(body), e);
        }
    }

    /**
     * Los valores se leen como texto y se convierten a mano. Jackson 3 renombró
     * {@code asText()} a {@code asString()}; el nombre de los accesores numéricos no
     * está verificado contra esta versión, y preferimos depender de un solo método
     * cuya existencia sí está confirmada antes que adivinar.
     */
    private static Integer intField(JsonNode node, String field) {
        String raw = textField(node, field);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textField(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asString();
    }

    private static boolean isOk(JsonNode envelope) {
        String raw = textField(envelope, "ok");
        return "true".equalsIgnoreCase(raw);
    }

    // --- Utilidades ---

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("La URL base del servicio es obligatoria");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "(sin cuerpo)";
        }
        String trimmed = body.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "...";
    }

    private static String sizeOrUnknown(Path path) {
        try {
            return String.valueOf(Files.size(path));
        } catch (IOException e) {
            return "desconocido";
        }
    }
}