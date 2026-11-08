package com.obraviva.platform.installation;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Archivos de ejemplo generados al vuelo: un plano en PDF, un documento en PDF y un render
 * en PNG. Todos llevan su rótulo de ejemplo a la vista, para que nunca pasen por material
 * real de una obra.
 *
 * <p>El plano se dibuja con las mismas medidas y ambientes de la planta: al estirarse sobre
 * ella, los ambientes marcados coinciden con el dibujo. Pasa por la misma conversión de PDF
 * que un plano subido por el dueño.
 */
final class DemoAssets {

    /** Puntos de PDF por metro: una planta de 12 m ocupa 600 puntos, bien por debajo del tope de render. */
    private static final float POINTS_PER_METER = 50f;

    private DemoAssets() {
    }

    /** Ambiente a dibujar: polígono x1, y1, x2, y2... en metros, con origen arriba a la izquierda. */
    record DemoRoom(String name, List<Double> polygon) {
    }

    static Path floorPlanPdf(double widthM, double depthM, List<DemoRoom> rooms) {
        float width = (float) widthM * POINTS_PER_METER;
        float height = (float) depthM * POINTS_PER_METER;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(new Color(252, 251, 247));
                content.addRect(0, 0, width, height);
                content.fill();

                // Muros: el contorno de cada ambiente, con trazo de muro.
                content.setStrokingColor(new Color(40, 40, 38));
                content.setLineWidth(4f);
                for (DemoRoom room : rooms) {
                    List<Double> points = room.polygon();
                    content.moveTo(x(points.get(0)), y(points.get(1), height));
                    for (int i = 2; i < points.size(); i += 2) {
                        content.lineTo(x(points.get(i)), y(points.get(i + 1), height));
                    }
                    content.closePath();
                    content.stroke();
                }

                content.setNonStrokingColor(new Color(60, 60, 58));
                for (DemoRoom room : rooms) {
                    double[] center = centroid(room.polygon());
                    text(content, bold, 11, room.name(), x(center[0]), y(center[1], height) + 4, Align.CENTER);
                    text(content, regular, 9, String.format("%.1f m²", area(room.polygon())).replace('.', ','),
                            x(center[0]), y(center[1], height) - 10, Align.CENTER);
                }

                content.setNonStrokingColor(new Color(140, 60, 40));
                text(content, bold, 9, "PLANO DE EJEMPLO - SIN VALIDEZ", width - 8, 8, Align.RIGHT);
            }
            return save(document, "obraviva-demo-plano-", ".pdf");
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el plano de ejemplo", e);
        }
    }

    static Path documentPdf(String title, List<String> paragraphs) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float top = PDRectangle.A4.getHeight() - 72;

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(new Color(140, 60, 40));
                text(content, bold, 10, "DOCUMENTO DE EJEMPLO - SIN VALIDEZ LEGAL", 72, top + 30, Align.LEFT);
                content.setNonStrokingColor(new Color(22, 22, 21));
                text(content, bold, 18, title, 72, top, Align.LEFT);
                float line = top - 36;
                for (String paragraph : paragraphs) {
                    for (String wrapped : wrap(paragraph, 88)) {
                        text(content, regular, 11, wrapped, 72, line, Align.LEFT);
                        line -= 16;
                    }
                    line -= 10;
                }
            }
            return save(document, "obraviva-demo-documento-", ".pdf");
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el documento de ejemplo", e);
        }
    }

    /** Fachada proyectada, dibujada: el render que un estudio le entrega al desarrollista. */
    static Path renderPng() {
        int width = 1600;
        int height = 1200;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(214, 228, 238), 0, 760, new Color(246, 240, 228)));
            g.fillRect(0, 0, width, 800);
            g.setColor(new Color(128, 160, 108));
            g.fillRect(0, 800, width, 400);
            g.setColor(new Color(196, 190, 180));
            g.fillRect(0, 930, width, 110);

            g.setColor(new Color(244, 242, 236));
            g.fillRect(300, 470, 1000, 340);
            g.fillRect(220, 300, 820, 190);
            g.setColor(new Color(150, 176, 188));
            g.fillRect(270, 335, 540, 120);
            g.fillRect(960, 540, 280, 190);
            g.setColor(new Color(150, 104, 70));
            for (int x = 830; x < 1000; x += 18) {
                g.fillRect(x, 335, 12, 120);
            }
            for (int x = 360; x < 700; x += 22) {
                g.fillRect(x, 540, 14, 270);
            }
            g.setColor(new Color(108, 72, 46));
            g.fillRect(740, 560, 150, 250);
            g.setColor(new Color(90, 110, 80));
            g.fillOval(1320, 520, 220, 220);
            g.fillOval(1370, 440, 170, 170);

            g.setColor(new Color(22, 22, 21, 185));
            g.fillRoundRect(width - 380, height - 90, 340, 54, 54, 54);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            g.setStroke(new BasicStroke(1));
            g.drawString("Render de ejemplo", width - 330, height - 53);
        } finally {
            g.dispose();
        }
        try {
            Path file = Files.createTempFile("obraviva-demo-render-", ".png");
            file.toFile().deleteOnExit();
            ImageIO.write(image, "png", file.toFile());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el render de ejemplo", e);
        }
    }

    // --- Piezas ---

    private static float x(double meters) {
        return (float) meters * POINTS_PER_METER;
    }

    private static float y(double meters, float pageHeight) {
        return pageHeight - (float) meters * POINTS_PER_METER;
    }

    private enum Align { LEFT, CENTER, RIGHT }

    private static void text(PDPageContentStream content, PDType1Font font, float size, String value,
                             float x, float y, Align align) throws IOException {
        float width = font.getStringWidth(value) / 1000 * size;
        float start = switch (align) {
            case LEFT -> x;
            case CENTER -> x - width / 2;
            case RIGHT -> x - width;
        };
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(start, y);
        content.showText(value);
        content.endText();
    }

    private static double[] centroid(List<Double> polygon) {
        double sumX = 0;
        double sumY = 0;
        int points = polygon.size() / 2;
        for (int i = 0; i < polygon.size(); i += 2) {
            sumX += polygon.get(i);
            sumY += polygon.get(i + 1);
        }
        return new double[] {sumX / points, sumY / points};
    }

    private static double area(List<Double> polygon) {
        double twice = 0;
        int points = polygon.size() / 2;
        for (int i = 0; i < points; i++) {
            int next = (i + 1) % points;
            twice += polygon.get(2 * i) * polygon.get(2 * next + 1) - polygon.get(2 * next) * polygon.get(2 * i + 1);
        }
        return Math.abs(twice) / 2;
    }

    private static List<String> wrap(String paragraph, int maxChars) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : paragraph.split(" ")) {
            if (current.length() + word.length() + 1 > maxChars && !current.isEmpty()) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static Path save(PDDocument document, String prefix, String suffix) throws IOException {
        Path file = Files.createTempFile(prefix, suffix);
        file.toFile().deleteOnExit();
        document.save(file.toFile());
        return file;
    }
}
