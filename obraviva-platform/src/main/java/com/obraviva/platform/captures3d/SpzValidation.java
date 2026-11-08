package com.obraviva.platform.captures3d;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Qué se le pide a un SPZ subido para publicarlo como recorrido.
 *
 * <p>Un SPZ es un gzip; adentro, un encabezado de 16 bytes en little endian: la marca
 * {@code NGSP}, la versión, la cantidad de puntos, el grado de los armónicos, los bits
 * fraccionarios y los flags. Scaniverse exporta la versión 3 y gsbox la 2; Spark lee las
 * dos. Se valida sólo el encabezado, descomprimiendo el comienzo del archivo: comprobar
 * que el visor lo dibuje exigiría leerlo entero, y se descartó por costo.
 *
 * <p>Medido con los SPZ reales de la obra (octubre de 2026): entre 6 y 9 MB, con unos
 * 350.000 puntos. El máximo de 50 MB deja margen para un sector grande sin que cargarlo
 * en un teléfono sea un problema.
 */
final class SpzValidation {

    static final long MAX_BYTES = 50L * 1024 * 1024;
    static final int PREFIX_BYTES = 64 * 1024;

    private static final int HEADER_BYTES = 16;
    private static final String MAGIC = "NGSP";

    private SpzValidation() {
    }

    /** Lo que se puede saber antes de subir: el nombre y el tamaño que declara el cliente. */
    static Optional<String> problemOfDeclared(String filename, Long sizeBytes) {
        if (filename != null && !filename.toLowerCase(Locale.ROOT).endsWith(".spz")) {
            return Optional.of("El archivo tiene que ser un .spz. En Scaniverse: Compartir → Exportar → SPZ.");
        }
        return sizeBytes == null ? Optional.empty() : problemOfSize(sizeBytes);
    }

    static Optional<String> problemOfSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            return Optional.of("El archivo está vacío.");
        }
        if (sizeBytes > MAX_BYTES) {
            return Optional.of("El archivo pesa %d MB y el máximo es %d MB."
                    .formatted(Math.ceilDiv(sizeBytes, 1024 * 1024), MAX_BYTES / (1024 * 1024)));
        }
        return Optional.empty();
    }

    /** El comienzo del archivo subido: tiene que ser un gzip con un encabezado SPZ legible adentro. */
    static Optional<String> problemOfPrefix(byte[] prefix) {
        String notSpz = "El archivo no es un SPZ válido. En Scaniverse: Compartir → Exportar → SPZ.";
        if (prefix.length < 2 || prefix[0] != (byte) 0x1f || prefix[1] != (byte) 0x8b) {
            return Optional.of(notSpz);
        }
        byte[] header;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(prefix))) {
            header = gzip.readNBytes(HEADER_BYTES);
        } catch (IOException e) {
            return Optional.of(notSpz);
        }
        if (header.length < HEADER_BYTES || !MAGIC.equals(new String(header, 0, 4, StandardCharsets.US_ASCII))) {
            return Optional.of(notSpz);
        }
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        long version = Integer.toUnsignedLong(buffer.getInt(4));
        long points = Integer.toUnsignedLong(buffer.getInt(8));
        if (version != 2 && version != 3) {
            return Optional.of("El SPZ es de la versión %d y el visor lee las versiones 2 y 3.".formatted(version));
        }
        if (points == 0) {
            return Optional.of("El SPZ no tiene puntos: exportá el sector de nuevo desde Scaniverse.");
        }
        return Optional.empty();
    }
}
