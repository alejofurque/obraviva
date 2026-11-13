package com.obraviva.platform.media.variants;

import com.obraviva.platform.common.process.ProcessRunner;
import com.obraviva.platform.media.MediaKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las versiones livianas con el ffmpeg real, sobre archivos fabricados acá. Lo que importa
 * es lo que una librería de imágenes de Java no hace: una foto de celular en vertical (con
 * su orientación EXIF) sale derecha.
 */
class MediaVariantGeneratorTest {

    private final ProcessRunner runner = new ProcessRunner();
    private final MediaVariantGenerator generator = new MediaVariantGenerator("ffmpeg", runner);

    @TempDir
    Path dir;

    @Test
    @DisplayName("una foto con orientación EXIF de celular en vertical sale derecha, y ImageIO solo la vería de costado")
    void exifOrientationIsApplied() throws Exception {
        Path plain = synthetic("testsrc=size=400x200", "plain.jpg");
        Path rotated = dir.resolve("rotated.jpg");
        Files.write(rotated, withExifOrientation(Files.readAllBytes(plain), 6));
        // El punto de partida: leída sin corregir, la foto es apaisada.
        assertThat(ImageIO.read(rotated.toFile()).getWidth()).isEqualTo(400);

        MediaVariantGenerator.GeneratedVariants variants = generator.generate(rotated, MediaKind.PHOTO, dir);

        assertThat(size(variants.thumb())).isEqualTo("200x400");
        assertThat(size(variants.screen())).isEqualTo("200x400");
    }

    @Test
    @DisplayName("achica al lado mayor de cada versión sin deformar, y nunca agranda una foto chica")
    void scalesDownKeepingTheAspectRatio() throws Exception {
        Path large = synthetic("testsrc=size=2000x1000", "large.png");
        Path small = synthetic("testsrc=size=320x240", "small.png");

        MediaVariantGenerator.GeneratedVariants fromLarge = generator.generate(large, MediaKind.PHOTO, dir.resolve(mkdir("a")));
        assertThat(size(fromLarge.thumb())).isEqualTo("480x240");
        assertThat(size(fromLarge.screen())).isEqualTo("1600x800");

        MediaVariantGenerator.GeneratedVariants fromSmall = generator.generate(small, MediaKind.PHOTO, dir.resolve(mkdir("b")));
        assertThat(size(fromSmall.thumb())).isEqualTo("320x240");
        assertThat(size(fromSmall.screen())).isEqualTo("320x240");
    }

    @Test
    @DisplayName("de un video saca un fotograma de portada, también de uno de menos de un segundo")
    void videoPosterFrame() throws Exception {
        Path video = dir.resolve("corto.mp4");
        ffmpeg(List.of("-f", "lavfi", "-i", "testsrc=size=640x360:duration=0.4", video.toString()));

        MediaVariantGenerator.GeneratedVariants poster = generator.generate(video, MediaKind.VIDEO, dir);

        assertThat(size(poster.thumb())).isEqualTo("480x270");
        assertThat(size(poster.screen())).isEqualTo("640x360");
    }

    @Test
    @DisplayName("un archivo que no es imagen falla con un motivo, no con un archivo vacío")
    void unreadableFileFails() throws IOException {
        Path garbage = Files.writeString(dir.resolve("roto.jpg"), "esto no es una imagen");

        assertThatThrownBy(() -> generator.generate(garbage, MediaKind.PHOTO, dir))
                .isInstanceOf(MediaVariantException.class);
    }

    // --- Armado ---

    private String mkdir(String name) throws IOException {
        return Files.createDirectories(dir.resolve(name)).getFileName().toString();
    }

    private Path synthetic(String source, String name) {
        Path output = dir.resolve(name);
        ffmpeg(List.of("-f", "lavfi", "-i", source, "-frames:v", "1", output.toString()));
        return output;
    }

    private void ffmpeg(List<String> args) {
        List<String> command = new java.util.ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y"));
        command.addAll(args);
        assertThat(runner.run(command, Duration.ofMinutes(1)).failed()).isFalse();
    }

    private static String size(Path image) throws IOException {
        BufferedImage read = ImageIO.read(image.toFile());
        return read.getWidth() + "x" + read.getHeight();
    }

    /** Un segmento APP1 Exif mínimo con la orientación, justo después del SOI, como lo deja un celular. */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) throws IOException {
        ByteBuffer tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        byte[] exif = "Exif\0\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int length = exif.length + tiff.capacity() + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(new byte[] {(byte) 0xff, (byte) 0xe1, (byte) (length >> 8), (byte) length});
        out.write(exif);
        out.write(tiff.array());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
