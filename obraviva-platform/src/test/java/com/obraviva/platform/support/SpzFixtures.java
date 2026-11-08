package com.obraviva.platform.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/**
 * Armado de tests: SPZ como los exporta Scaniverse, un gzip con el encabezado {@code NGSP}
 * (versión, puntos, armónicos, bits fraccionarios) y datos detrás.
 *
 * <p>Desde que la confirmación de un SPZ mide el archivo, los tests que mockean el
 * almacenamiento tienen que devolver un tamaño y un comienzo de archivo válidos.
 */
public final class SpzFixtures {

    /** Tamaño típico de un sector exportado de Scaniverse. */
    public static final long TYPICAL_SIZE = 7_000_000L;

    private SpzFixtures() {
    }

    public static byte[] spz(String magic, int version, int points, int payloadBytes) {
        ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        header.put(magic.getBytes(StandardCharsets.US_ASCII)).putInt(version).putInt(points)
                .put((byte) 3).put((byte) 12).put((byte) 0).put((byte) 0);
        // Aleatorio con semilla fija: como los datos reales, casi no se comprime.
        byte[] payload = new byte[payloadBytes];
        new Random(7).nextBytes(payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(header.array());
            gzip.write(payload);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** Un SPZ v3 válido y chico. */
    public static byte[] valid() {
        return spz("NGSP", 3, 358_538, 2_000);
    }
}
