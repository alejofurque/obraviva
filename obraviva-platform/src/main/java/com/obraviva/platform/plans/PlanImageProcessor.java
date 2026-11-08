package com.obraviva.platform.plans;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Deja el archivo de un plano listo para mostrar: una imagen JPG o PNG con sus
 * dimensiones conocidas.
 *
 * <p>Un PDF se rasteriza en su primera página. Es el formato en que un estudio exporta
 * el plano, pero mostrarlo exigiría un visor de PDF en el navegador y no permitiría
 * dibujar ambientes encima. La resolución se calcula para que el lado mayor no pase de
 * {@link #MAX_RENDER_SIDE_PX}: alcanza para leer cotas en pantalla sin generar imágenes
 * de decenas de megabytes.
 *
 * <p>DWG no se acepta: es un formato cerrado de CAD y cualquier programa de dibujo lo
 * exporta a PDF.
 */
@Component
public class PlanImageProcessor {

    static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    static final int MAX_RENDER_SIDE_PX = 3000;
    /** Una foto de celular ronda los 4000 px; más que esto es un archivo mal exportado. */
    static final int MAX_IMAGE_SIDE_PX = 10000;
    private static final float MAX_DPI = 300f;
    private static final float POINTS_PER_INCH = 72f;

    /** Imagen lista para publicar. {@code file} es nulo cuando se publica el original tal cual. */
    public record ProcessedImage(Path file, int widthPx, int heightPx) {
    }

    /** El archivo no sirve como plano. El mensaje se muestra a quien lo subió. */
    public static class InvalidPlanImageException extends Exception {
        public InvalidPlanImageException(String message) {
            super(message);
        }

        public InvalidPlanImageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ProcessedImage process(Path source, boolean pdf) throws InvalidPlanImageException {
        try {
            if (Files.size(source) > MAX_FILE_BYTES) {
                throw new InvalidPlanImageException("El plano pesa más de 25 MB. Exportalo con menos resolución.");
            }
        } catch (IOException e) {
            throw new InvalidPlanImageException("No se pudo leer el archivo del plano.", e);
        }
        return pdf ? renderFirstPage(source) : readImage(source);
    }

    private ProcessedImage renderFirstPage(Path source) throws InvalidPlanImageException {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            if (document.getNumberOfPages() == 0) {
                throw new InvalidPlanImageException("El PDF no tiene páginas.");
            }
            PDPage page = document.getPage(0);
            PDRectangle box = page.getCropBox();
            float longestSidePoints = Math.max(box.getWidth(), box.getHeight());
            if (longestSidePoints <= 0) {
                throw new InvalidPlanImageException("La primera página del PDF no tiene tamaño.");
            }
            float dpi = Math.min(MAX_DPI, MAX_RENDER_SIDE_PX * POINTS_PER_INCH / longestSidePoints);

            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, dpi, ImageType.RGB);
            Path output = Files.createTempFile("obraviva-plano-", ".png");
            ImageIO.write(image, "png", output.toFile());
            return new ProcessedImage(output, image.getWidth(), image.getHeight());
        } catch (IOException e) {
            // PDFBox lanza IOException tanto por un PDF roto como por uno protegido con contraseña.
            throw new InvalidPlanImageException(
                    "No se pudo leer el PDF. Revisá que no esté dañado ni protegido con contraseña.", e);
        }
    }

    /** Lee sólo el encabezado: las dimensiones se obtienen sin decodificar la imagen entera. */
    private ProcessedImage readImage(Path source) throws InvalidPlanImageException {
        try (ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                throw new InvalidPlanImageException("El archivo no es una imagen JPG o PNG válida.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || Math.max(width, height) > MAX_IMAGE_SIDE_PX) {
                    throw new InvalidPlanImageException(
                            "La imagen del plano tiene que medir hasta " + MAX_IMAGE_SIDE_PX + " píxeles por lado.");
                }
                return new ProcessedImage(null, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new InvalidPlanImageException("El archivo no es una imagen JPG o PNG válida.", e);
        }
    }
}
