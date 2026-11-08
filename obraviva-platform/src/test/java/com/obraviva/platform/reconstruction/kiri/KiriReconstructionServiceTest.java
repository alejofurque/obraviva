package com.obraviva.platform.reconstruction.kiri;

import com.obraviva.platform.reconstruction.ReconstructionException;
import com.obraviva.platform.reconstruction.ReconstructionRejectedException;
import com.obraviva.platform.reconstruction.ReconstructionStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests del adaptador real contra un servidor HTTP levantado en el propio test.
 * No se realiza ninguna llamada al servicio de KIRI y no se consume ningún crédito.
 *
 * <p>Se usa el servidor del JDK ({@code com.sun.net.httpserver}) en vez de una
 * librería de simulación: no agrega dependencias al proyecto y alcanza de sobra para
 * tres endpoints.
 */
class KiriReconstructionServiceTest {

    private static final String API_KEY = "kiri-clave-de-prueba";

    private HttpServer server;
    private KiriReconstructionService service;

    // Respuestas configurables por cada test.
    private int submitHttpStatus = 200;
    private String submitBody = envelope(200, "{\"serialize\":\"abc123\",\"calculateType\":3}");
    private int statusHttpStatus = 200;
    private String statusBody = envelope(200, "{\"serialize\":\"abc123\",\"status\":2}");
    private int zipHttpStatus = 200;
    private String zipBody;
    private int balanceHttpStatus = 200;
    // La forma exacta que devolvió la API real.
    private String balanceBody = "{\"code\":200,\"msg\":\"success\",\"data\":{\"balance\":9},\"ok\":true}";
    private byte[] downloadBytes = "contenido del zip".getBytes(StandardCharsets.UTF_8);

    // Lo que el servidor recibió, para verificar la petición.
    private String lastSubmitBody;
    private String lastAuthorizationHeader;
    private String lastStatusQuery;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

        server.createContext("/api/v1/open/3dgs/video", exchange -> {
            lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastSubmitBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, submitHttpStatus, submitBody.getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/api/v1/open/model/getStatus", exchange -> {
            lastStatusQuery = exchange.getRequestURI().getQuery();
            respond(exchange, statusHttpStatus, statusBody.getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/api/v1/open/model/getModelZip", exchange ->
                respond(exchange, zipHttpStatus, zipBody.getBytes(StandardCharsets.UTF_8)));

        server.createContext("/api/v1/open/balance", exchange -> {
            lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, balanceHttpStatus, balanceBody.getBytes(StandardCharsets.UTF_8));
        });

        server.createContext("/files/model.zip", exchange ->
                respond(exchange, 200, downloadBytes));

        server.start();

        String base = "http://localhost:" + server.getAddress().getPort() + "/api";
        zipBody = envelope(200, "{\"serialize\":\"abc123\",\"modelUrl\":\"http://localhost:"
                + server.getAddress().getPort() + "/files/model.zip\"}");

        service = new KiriReconstructionService(
                base, API_KEY,
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String envelope(int code, String data) {
        return "{\"code\":" + code + ",\"msg\":\"success\",\"data\":" + data + ",\"ok\":true}";
    }

    private static String errorEnvelope(int code, String msg) {
        return "{\"code\":" + code + ",\"msg\":\"" + msg + "\",\"data\":null,\"ok\":false}";
    }

    private Path videoFile() throws IOException {
        Path video = tempDir.resolve("normalizado.mp4");
        Files.writeString(video, "bytes de video simulados");
        return video;
    }

    @Test
    @DisplayName("el saldo se lee de data.balance, con la clave, sin consumir nada")
    void balanceIsRead() {
        assertThat(service.availableCredits()).hasValue(9);
        assertThat(lastAuthorizationHeader).isEqualTo("Bearer " + API_KEY);
    }

    @Test
    @DisplayName("si el saldo no se puede leer, no hay número: ni error ni un valor inventado")
    void unreadableBalanceIsEmpty() {
        balanceHttpStatus = 401;
        balanceBody = "{}";
        assertThat(service.availableCredits()).isEmpty();

        balanceHttpStatus = 200;
        balanceBody = envelope(200, "{}");
        assertThat(service.availableCredits()).isEmpty();

        balanceBody = "no es json";
        assertThat(service.availableCredits()).isEmpty();
    }

    @Test
    @DisplayName("el envío incluye la clave, isMesh y isMask en cero, y el archivo")
    void submitSendsCredentialsAndFormFields() throws IOException {
        service.submitVideo(videoFile());

        assertThat(lastAuthorizationHeader).isEqualTo("Bearer " + API_KEY);
        assertThat(lastSubmitBody).contains("name=\"isMesh\"");
        assertThat(lastSubmitBody).contains("name=\"isMask\"");
        assertThat(lastSubmitBody).contains("name=\"videoFile\"");
        assertThat(lastSubmitBody).contains("filename=\"normalizado.mp4\"");
        assertThat(lastSubmitBody).contains("bytes de video simulados");
    }

    @Test
    @DisplayName("un sobre con code 200 y ok true se interpreta como éxito")
    void code200IsTreatedAsSuccess() throws IOException {
        // La documentación muestra code 0, pero la API real devuelve 200. El adaptador
        // decide por 'ok' y el estado HTTP, nunca por el valor de 'code'.
        assertThat(service.submitVideo(videoFile())).isEqualTo("abc123");
    }

    @Test
    @DisplayName("un sobre con code 0 y ok true también se interpreta como éxito")
    void code0IsAlsoTreatedAsSuccess() throws IOException {
        submitBody = envelope(0, "{\"serialize\":\"xyz789\",\"calculateType\":3}");

        assertThat(service.submitVideo(videoFile())).isEqualTo("xyz789");
    }

    @Test
    @DisplayName("el estado 2 del proveedor significa exitoso, no fallido")
    void status2MapsToSucceeded() {
        statusBody = envelope(200, "{\"status\":2}");

        assertThat(service.checkStatus("abc123")).isEqualTo(ReconstructionStatus.SUCCEEDED);
        assertThat(lastStatusQuery).contains("serialize=abc123");
    }

    @Test
    @DisplayName("el estado 1 del proveedor significa fallido, no exitoso")
    void status1MapsToFailed() {
        statusBody = envelope(200, "{\"status\":1}");

        assertThat(service.checkStatus("abc123")).isEqualTo(ReconstructionStatus.FAILED);
    }

    @Test
    @DisplayName("los estados intermedios se traducen sin resolver el trabajo")
    void intermediateStatusesAreMapped() {
        statusBody = envelope(200, "{\"status\":0}");
        assertThat(service.checkStatus("abc123")).isEqualTo(ReconstructionStatus.PROCESSING);

        statusBody = envelope(200, "{\"status\":3}");
        assertThat(service.checkStatus("abc123")).isEqualTo(ReconstructionStatus.QUEUED);

        statusBody = envelope(200, "{\"status\":-1}");
        assertThat(service.checkStatus("abc123")).isEqualTo(ReconstructionStatus.UPLOADING);
    }

    @Test
    @DisplayName("el estado 4 indica que el resultado venció")
    void status4MapsToExpired() {
        statusBody = envelope(200, "{\"status\":4}");

        assertThat(service.checkStatus("abc123")).isEqualTo(ReconstructionStatus.EXPIRED);
    }

    @Test
    @DisplayName("un estado desconocido es una falla técnica, no un estado inventado")
    void unknownStatusIsTechnicalFailure() {
        statusBody = envelope(200, "{\"status\":99}");

        assertThatThrownBy(() -> service.checkStatus("abc123"))
                .isInstanceOf(ReconstructionException.class);
    }

    @Test
    @DisplayName("HTTP 403 se traduce a falta de créditos, sin reintento")
    void http403MeansInsufficientCredits() throws IOException {
        submitHttpStatus = 403;
        submitBody = errorEnvelope(403, "not enough credit");

        assertThatThrownBy(() -> service.submitVideo(videoFile()))
                .isInstanceOf(ReconstructionRejectedException.class)
                .satisfies(thrown -> assertThat(
                        ((ReconstructionRejectedException) thrown).reason())
                        .isEqualTo(ReconstructionRejectedException.Reason.INSUFFICIENT_CREDITS));
    }

    @Test
    @DisplayName("el código 2009 se traduce a video rechazado, sin reintento")
    void businessCode2009MeansVideoRejected() throws IOException {
        submitHttpStatus = 500;
        submitBody = errorEnvelope(2009, "The video does not meet the requirements");

        assertThatThrownBy(() -> service.submitVideo(videoFile()))
                .isInstanceOf(ReconstructionRejectedException.class)
                .satisfies(thrown -> assertThat(
                        ((ReconstructionRejectedException) thrown).reason())
                        .isEqualTo(ReconstructionRejectedException.Reason.VIDEO_REJECTED));
    }

    @Test
    @DisplayName("el código 2002 indica que el modelo venció")
    void businessCode2002MeansExpired() {
        statusHttpStatus = 500;
        statusBody = errorEnvelope(2002, "The model has expired");

        assertThatThrownBy(() -> service.checkStatus("abc123"))
                .isInstanceOf(ReconstructionRejectedException.class)
                .satisfies(thrown -> assertThat(
                        ((ReconstructionRejectedException) thrown).reason())
                        .isEqualTo(ReconstructionRejectedException.Reason.EXPIRED));
    }

    @Test
    @DisplayName("un error de servidor sin código conocido es técnico y reintentable")
    void unknownServerErrorIsTechnical() throws IOException {
        submitHttpStatus = 502;
        submitBody = "{\"msg\":\"bad gateway\"}";

        assertThatThrownBy(() -> service.submitVideo(videoFile()))
                .isInstanceOf(ReconstructionException.class);
    }

    @Test
    @DisplayName("la descarga sigue el enlace devuelto y escribe el archivo")
    void downloadFollowsModelUrl() throws IOException {
        Path destination = tempDir.resolve("resultado.zip");

        service.downloadResult("abc123", destination);

        assertThat(destination).exists();
        assertThat(Files.readAllBytes(destination)).isEqualTo(downloadBytes);
    }

    @Test
    @DisplayName("sin clave de API el adaptador no se puede construir")
    void blankApiKeyIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new KiriReconstructionService(
                "http://localhost:1", "  ",
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}