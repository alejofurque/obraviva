package com.obraviva.platform.reconstruction.mock;

import com.obraviva.platform.reconstruction.ReconstructionException;
import com.obraviva.platform.reconstruction.ReconstructionRejectedException;
import com.obraviva.platform.reconstruction.ReconstructionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests del adaptador simulado. Sin Spring, sin Docker y sin red: se instancia
 * directamente porque toda su configuración entra por el constructor.
 */
class MockReconstructionServiceTest {

    @TempDir
    Path tempDir;

    private Path videoFile() throws IOException {
        Path video = tempDir.resolve("normalizado.mp4");
        Files.writeString(video, "contenido irrelevante para el mock");
        return video;
    }

    private MockReconstructionService service(
            int polls, MockReconstructionService.Outcome outcome) {
        return new MockReconstructionService(polls, outcome);
    }

    @Test
    @DisplayName("el envío devuelve una referencia identificable como simulada")
    void submitReturnsMockReference() throws IOException {
        String reference = service(2, MockReconstructionService.Outcome.SUCCESS)
                .submitVideo(videoFile());

        assertThat(reference).startsWith("mock-");
    }

    @Test
    @DisplayName("enviar un archivo inexistente es una falla técnica")
    void submitOfMissingFileFails() {
        assertThatThrownBy(() -> service(2, MockReconstructionService.Outcome.SUCCESS)
                .submitVideo(tempDir.resolve("no-existe.mp4")))
                .isInstanceOf(ReconstructionException.class);
    }

    @Test
    @DisplayName("el estado permanece en curso durante las consultas configuradas")
    void statusStaysProcessingBeforeResolving() throws IOException {
        MockReconstructionService service =
                service(2, MockReconstructionService.Outcome.SUCCESS);
        String reference = service.submitVideo(videoFile());

        assertThat(service.checkStatus(reference)).isEqualTo(ReconstructionStatus.PROCESSING);
        assertThat(service.checkStatus(reference)).isEqualTo(ReconstructionStatus.PROCESSING);
        assertThat(service.checkStatus(reference)).isEqualTo(ReconstructionStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("con desenlace fallido el estado resuelve en FAILED")
    void failedOutcomeResolvesToFailed() throws IOException {
        MockReconstructionService service =
                service(0, MockReconstructionService.Outcome.FAILED);
        String reference = service.submitVideo(videoFile());

        assertThat(service.checkStatus(reference)).isEqualTo(ReconstructionStatus.FAILED);
    }

    @Test
    @DisplayName("con desenlace rechazado el envío falla de forma definitiva")
    void rejectedOutcomeFailsAtSubmit() throws IOException {
        assertThatThrownBy(() -> service(0, MockReconstructionService.Outcome.REJECTED)
                .submitVideo(videoFile()))
                .isInstanceOf(ReconstructionRejectedException.class)
                .satisfies(thrown -> assertThat(
                        ((ReconstructionRejectedException) thrown).reason())
                        .isEqualTo(ReconstructionRejectedException.Reason.VIDEO_REJECTED));
    }

    @Test
    @DisplayName("una referencia desconocida se reporta como inexistente")
    void unknownReferenceIsReportedAsNotFound() {
        assertThatThrownBy(() -> service(0, MockReconstructionService.Outcome.SUCCESS)
                .checkStatus("mock-inventada"))
                .isInstanceOf(ReconstructionRejectedException.class)
                .satisfies(thrown -> assertThat(
                        ((ReconstructionRejectedException) thrown).reason())
                        .isEqualTo(ReconstructionRejectedException.Reason.NOT_FOUND));
    }

    @Test
    @DisplayName("el resultado descargado es un ZIP con un PLY adentro")
    void downloadedResultIsAZipContainingAPly() throws IOException {
        MockReconstructionService service =
                service(0, MockReconstructionService.Outcome.SUCCESS);
        String reference = service.submitVideo(videoFile());
        Path destination = tempDir.resolve("resultado.zip");

        service.downloadResult(reference, destination);

        assertThat(destination).exists();
        assertThat(Files.size(destination)).isPositive();

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(destination))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }

        // Más de una entrada a propósito: la etapa siguiente debe buscar el PLY,
        // no tomar la primera entrada del archivo comprimido.
        assertThat(entries).contains("model.ply");
        assertThat(entries).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("no hay resultado descargable si la reconstrucción falló")
    void failedReconstructionHasNothingToDownload() throws IOException {
        MockReconstructionService service =
                service(0, MockReconstructionService.Outcome.FAILED);
        String reference = service.submitVideo(videoFile());

        assertThatThrownBy(() ->
                service.downloadResult(reference, tempDir.resolve("nada.zip")))
                .isInstanceOf(ReconstructionRejectedException.class);
    }
}