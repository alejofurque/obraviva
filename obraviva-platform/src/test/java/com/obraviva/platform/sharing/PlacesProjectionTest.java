package com.obraviva.platform.sharing;

import com.obraviva.platform.sharing.PlacesProjection.PlaceMedia;
import com.obraviva.platform.sharing.PlacesProjection.PlaceNote;
import com.obraviva.platform.sharing.PlacesProjection.PlaceTour;
import com.obraviva.platform.media.variants.ImageVariants.SignedImage;
import com.obraviva.platform.sharing.PublicViewDtos.PublicFront;
import com.obraviva.platform.sharing.PublicViewDtos.PublicFrontProject;
import com.obraviva.platform.sharing.PublicViewDtos.PublicMedia;
import com.obraviva.platform.sharing.PublicViewDtos.PublicPlace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** La casa lugar por lugar, mes a mes, armada a mano sobre datos conocidos. */
class PlacesProjectionTest {

    private static final List<String> SECTORS = List.of("Frente", "Living y cocina", "Baño");

    private static PlaceMedia photo(String sector, String entryDate, String uploadedAt, String url) {
        return new PlaceMedia(sector, entryDate == null ? null : LocalDate.parse(entryDate), null, Instant.parse(uploadedAt), false,
                new PublicMedia(url, "image/jpeg", "PHOTO", Instant.parse(uploadedAt), null, null, null, null, null));
    }

    private static PlaceMedia photo(String sector, String day, String url, PlaceNote note, boolean showcase) {
        Instant uploadedAt = Instant.parse(day + "T15:00:00Z");
        return new PlaceMedia(sector, note == null ? null : note.date(), note == null && !showcase ? LocalDate.parse(day) : null,
                uploadedAt, false, new PublicMedia(url, "image/jpeg", "PHOTO", uploadedAt, null, null, null, "miniatura-" + url, "pantalla-" + url), note, showcase);
    }

    private static PlaceMedia video(String sector, String uploadedAt, String url) {
        return new PlaceMedia(sector, null, null, Instant.parse(uploadedAt), true,
                new PublicMedia(url, "video/mp4", "VIDEO", Instant.parse(uploadedAt), null, null, null, null, null));
    }

    @Test
    @DisplayName("cada lugar en el orden de la casa, con sus meses en orden y General al final")
    void placesInHouseOrderWithGeneralLast() {
        List<PublicPlace> places = PlacesProjection.places(SECTORS,
                List.of(
                        photo(null, null, "2026-09-01T15:00:00Z", "general"),
                        photo("Living y cocina", "2026-08-20", "2026-08-21T12:00:00Z", "living-agosto"),
                        photo("Frente", "2026-10-02", "2026-10-02T12:00:00Z", "frente-octubre"),
                        photo("Frente", "2026-08-05", "2026-08-05T12:00:00Z", "frente-agosto")),
                List.of(new PlaceTour("Frente", LocalDate.parse("2026-09-12"))));

        assertThat(places).extracting(PublicPlace::name).containsExactly("Frente", "Living y cocina", "General");
        PublicPlace frente = places.getFirst();
        assertThat(frente.months()).extracting(month -> month.month()).containsExactly("2026-08", "2026-09", "2026-10");
        assertThat(frente.months().get(0).photos()).extracting(PublicMedia::url).containsExactly("frente-agosto");
        assertThat(frente.months().get(1).photos()).isEmpty();
        assertThat(frente.months().get(1).tour()).isTrue();
        assertThat(frente.months().get(2).tour()).isFalse();
    }

    @Test
    @DisplayName("una foto de galería toma el mes de su subida en hora de Argentina, y el de su avance si lo tiene")
    void monthUsesTheEntryOrTheUploadInArgentina() {
        // 1 de noviembre a las 01:30 UTC es 31 de octubre a las 22:30 en Argentina.
        List<PublicPlace> places = PlacesProjection.places(SECTORS,
                List.of(photo("Baño", null, "2026-11-01T01:30:00Z", "galeria"),
                        photo("Frente", "2026-07-31", "2026-08-03T12:00:00Z", "avance")),
                List.of());

        assertThat(places.get(0).months().getFirst().month()).isEqualTo("2026-07");
        assertThat(places.get(1).months().getFirst().month()).isEqualTo("2026-10");
    }

    @Test
    @DisplayName("fotos y videos por separado, en orden; sólo los lugares con material; un nombre viejo no se pierde")
    void photosAndVideosOnlyPlacesWithMaterial() {
        List<PublicPlace> places = PlacesProjection.places(SECTORS,
                List.of(video("Living y cocina", "2026-09-10T12:00:00Z", "video"),
                        photo("Living y cocina", null, "2026-09-12T12:00:00Z", "segunda"),
                        photo("Living y cocina", null, "2026-09-02T12:00:00Z", "primera")),
                List.of(new PlaceTour("Quincho", LocalDate.parse("2026-09-01")), new PlaceTour(null, LocalDate.parse("2026-09-01"))));

        assertThat(places).extracting(PublicPlace::name).containsExactly("Living y cocina", "Quincho", "General");
        assertThat(places.getFirst().months().getFirst().photos()).extracting(PublicMedia::url).containsExactly("primera", "segunda");
        assertThat(places.getFirst().months().getFirst().videos()).extracting(PublicMedia::url).containsExactly("video");
        assertThat(places.get(2).months().getFirst().tour()).isTrue();
    }

    @Test
    @DisplayName("cada mes de un lugar trae el texto de los avances que le aportaron fotos, una vez cada uno y en orden")
    void notesOfTheEntriesThatContributed() {
        PlaceNote revoque = new PlaceNote(UUID.randomUUID(), LocalDate.parse("2026-10-14"), "Revoques", "Revoque del frente");
        PlaceNote aberturas = new PlaceNote(UUID.randomUUID(), LocalDate.parse("2026-10-02"), null, "Llegaron las aberturas");
        List<PublicPlace> places = PlacesProjection.places(SECTORS,
                List.of(photo("Frente", "2026-10-14", "a", revoque, false),
                        photo("Frente", "2026-10-14", "b", revoque, false),
                        photo("Frente", "2026-10-02", "c", aberturas, false),
                        photo("Frente", "2026-10-20", "sesion", null, false)),
                List.of());

        assertThat(places.getFirst().months().getFirst().notes())
                .extracting(note -> note.description())
                .containsExactly("Llegaron las aberturas", "Revoque del frente");
    }

    /** El render del frente firmado, con sus versiones livianas listas. */
    private static final SignedImage RENDER = new SignedImage("render-frente", "render-miniatura", "render-pantalla");

    private static final PublicFrontProject OLD_COMPARISON =
            new PublicFrontProject("render-viejo", null, "foto-vieja", null, LocalDate.parse("2026-10-09"));

    @Test
    @DisplayName("el frente sale de la sesión y de los avances, nunca de la galería, aunque la galería tenga su sector")
    void frontNeverComesFromTheGallery() {
        PlaceNote avance = new PlaceNote(UUID.randomUUID(), LocalDate.parse("2026-08-10"), null, "Frente revocado");
        PublicFront front = PlacesProjection.front("Frente",
                List.of(photo("Frente", "2026-06-01", "galeria-vieja", null, true),
                        photo("Frente", "2026-08-10", "avance", avance, false),
                        photo("Frente", "2026-10-14", "sesion", null, false),
                        photo("Frente", "2026-11-01", "galeria-nueva", null, true),
                        photo("Baño", "2026-11-02", "otro-lugar", null, false)),
                null, null);

        assertThat(front.sector()).isEqualTo("Frente");
        assertThat(front.latest().url()).isEqualTo("sesion");
        assertThat(front.latest().date()).isEqualTo(LocalDate.parse("2026-10-14"));
        assertThat(front.first().url()).isEqualTo("avance");
    }

    @Test
    @DisplayName("El proyecto: el render del frente contra la foto de hoy, aunque haya una comparación vieja")
    void projectComparesTheFrontRenderWithToday() {
        PublicFront front = PlacesProjection.front("Frente",
                List.of(photo("Frente", "2026-10-14", "sesion", null, false)), RENDER, OLD_COMPARISON);

        assertThat(front.renderUrl()).isEqualTo("render-frente");
        assertThat(front.project()).isEqualTo(
                new PublicFrontProject("render-frente", "render-pantalla", "sesion", "pantalla-sesion", LocalDate.parse("2026-10-14")));
        assertThat(front.renderThumbUrl()).isEqualTo("render-miniatura");
        assertThat(front.renderScreenUrl()).isEqualTo("render-pantalla");
    }

    @Test
    @DisplayName("sin render del frente, El proyecto es la comparación más reciente de la casa, con su propia foto")
    void projectFallsBackToTheLatestComparison() {
        PublicFront withoutFront = PlacesProjection.front(null, List.of(), null, OLD_COMPARISON);
        assertThat(withoutFront.sector()).isNull();
        assertThat(withoutFront.project()).isEqualTo(OLD_COMPARISON);

        PublicFront withPhoto = PlacesProjection.front("Frente",
                List.of(photo("Frente", "2026-10-20", "sesion", null, false)), null, OLD_COMPARISON);
        assertThat(withPhoto.renderUrl()).isNull();
        assertThat(withPhoto.project()).isEqualTo(OLD_COMPARISON);
    }

    @Test
    @DisplayName("con render y sin fotos del frente no hay El proyecto: no se compara contra nada")
    void renderWithoutPhotosHasNoProject() {
        PublicFront front = PlacesProjection.front("Frente", List.of(), RENDER, OLD_COMPARISON);

        assertThat(front.renderUrl()).isEqualTo("render-frente");
        assertThat(front.latest()).isNull();
        assertThat(front.project()).isNull();
    }

    @Test
    @DisplayName("Cuando empezó necesita dos meses distintos; la galería sola no da fotos; sin nada, el frente queda vacío")
    void startNeedsTwoMonths() {
        PublicFront sameMonth = PlacesProjection.front("Frente",
                List.of(photo("Frente", "2026-10-02", "primera", null, false), photo("Frente", "2026-10-20", "hoy", null, false)),
                null, null);
        assertThat(sameMonth.latest().url()).isEqualTo("hoy");
        assertThat(sameMonth.first()).isNull();

        PublicFront onlyGallery = PlacesProjection.front("Frente", List.of(photo("Frente", "2026-10-14", "galeria", null, true)), null, null);
        assertThat(onlyGallery.latest()).isNull();
        assertThat(onlyGallery.project()).isNull();

        PublicFront nothing = PlacesProjection.front(null, List.of(), null, null);
        assertThat(nothing.sector()).isNull();
        assertThat(nothing.latest()).isNull();
        assertThat(nothing.project()).isNull();
    }
}
