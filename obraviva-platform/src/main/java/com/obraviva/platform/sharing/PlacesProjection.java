package com.obraviva.platform.sharing;

import com.obraviva.platform.media.variants.ImageVariants.SignedImage;
import com.obraviva.platform.sharing.PublicViewDtos.PublicFront;
import com.obraviva.platform.sharing.PublicViewDtos.PublicFrontPhoto;
import com.obraviva.platform.sharing.PublicViewDtos.PublicFrontProject;
import com.obraviva.platform.sharing.PublicViewDtos.PublicMedia;
import com.obraviva.platform.sharing.PublicViewDtos.PublicPlace;
import com.obraviva.platform.sharing.PublicViewDtos.PublicPlaceMonth;
import com.obraviva.platform.sharing.PublicViewDtos.PublicPlaceNote;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * La casa lugar por lugar, mes a mes: lo que el comprador recorre en "Tu casa, lugar por
 * lugar". Cada sector junta sus fotos, sus videos y sus recorridos 3D por mes.
 *
 * <p>El mes de una foto es el de su avance; si no tiene, el de la sesión en que se sacó, y
 * si tampoco, el de su subida en hora de Argentina, que es donde está la obra y quien la mira. Lo que no tiene
 * sector va en "General", al final. Los sectores van en el orden de la casa; un nombre que
 * ya no es sector (material de antes de que existieran los renombres) va después, para no
 * perderlo. Sólo aparecen los lugares con material.
 */
final class PlacesProjection {

    /** El mismo nombre que usa el recorrido para lo que no tiene sector. */
    static final String GENERAL = "General";

    private static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");

    /**
     * Una foto o video de la casa. {@code entryDate} es la del avance; {@code takenOn}, la de
     * la sesión de recorrido. Sin ninguna de las dos, cuenta el día de la subida. {@code note}
     * es el avance al que pertenece, nulo si no es de uno; {@code showcase}, si es de la galería.
     */
    record PlaceMedia(String sector, LocalDate entryDate, LocalDate takenOn, Instant uploadedAt, boolean video,
                      PublicMedia media, PlaceNote note, boolean showcase) {

        PlaceMedia(String sector, LocalDate entryDate, LocalDate takenOn, Instant uploadedAt, boolean video,
                   PublicMedia media) {
            this(sector, entryDate, takenOn, uploadedAt, video, media, null, false);
        }

        LocalDate day() {
            if (entryDate != null) {
                return entryDate;
            }
            return takenOn != null ? takenOn : uploadedAt.atZone(ARGENTINA).toLocalDate();
        }
    }

    /** El avance de una foto, identificado por su raíz para no repetirlo si aportó varias. */
    record PlaceNote(UUID root, LocalDate date, String stageName, String description) {
    }

    /** Un recorrido 3D de la casa en un lugar y una fecha. */
    record PlaceTour(String zone, LocalDate date) {
    }

    private PlacesProjection() {
    }

    static List<PublicPlace> places(List<String> sectors, List<PlaceMedia> media, List<PlaceTour> tours) {
        Map<String, Map<String, List<PlaceMedia>>> mediaByPlace = new LinkedHashMap<>();
        Map<String, Set<String>> tourMonths = new LinkedHashMap<>();
        for (PlaceMedia item : media) {
            mediaByPlace.computeIfAbsent(nameOf(item.sector()), name -> new TreeMap<>())
                    .computeIfAbsent(monthOf(item.day()), month -> new ArrayList<>())
                    .add(item);
        }
        for (PlaceTour tour : tours) {
            tourMonths.computeIfAbsent(nameOf(tour.zone()), name -> new TreeSet<>()).add(monthOf(tour.date()));
        }

        List<PublicPlace> places = new ArrayList<>();
        for (String name : orderedNames(sectors, mediaByPlace.keySet(), tourMonths.keySet())) {
            Map<String, List<PlaceMedia>> byMonth = mediaByPlace.getOrDefault(name, Map.of());
            Set<String> withTour = tourMonths.getOrDefault(name, Set.of());
            Set<String> months = new TreeSet<>(byMonth.keySet());
            months.addAll(withTour);
            List<PublicPlaceMonth> placeMonths = months.stream()
                    .map(month -> {
                        List<PlaceMedia> items = byMonth.getOrDefault(month, List.of()).stream()
                                .sorted(Comparator.comparing(PlaceMedia::day).thenComparing(PlaceMedia::uploadedAt))
                                .toList();
                        return new PublicPlaceMonth(
                                month,
                                items.stream().filter(item -> !item.video()).map(PlaceMedia::media).toList(),
                                items.stream().filter(PlaceMedia::video).map(PlaceMedia::media).toList(),
                                withTour.contains(month),
                                notesOf(items));
                    })
                    .toList();
            places.add(new PublicPlace(name, placeMonths));
        }
        return places;
    }

    /**
     * El frente de la casa. Su foto más reciente y la primera salen sólo de fotos de sesión y
     * de avances del lugar marcado: la galería queda afuera aunque tenga ese sector. "Cuando
     * empezó" necesita dos meses distintos: dos fotos del mismo mes no muestran avance.
     *
     * <p>"El proyecto" compara el render del frente contra la foto de hoy. Si la casa no tiene
     * render del frente, usa {@code fallback}, su comparación más reciente de Proyecto y
     * realidad (render contra su propia foto), para no perder lo que ya mostraba.
     */
    static PublicFront front(String sector, List<PlaceMedia> media, SignedImage render, PublicFrontProject fallback) {
        List<PlaceMedia> photos = sector == null ? List.of() : media.stream()
                .filter(item -> sector.equals(item.sector()) && !item.video() && !item.showcase())
                .sorted(Comparator.comparing(PlaceMedia::day).thenComparing(PlaceMedia::uploadedAt))
                .toList();
        PublicFrontPhoto latest = photos.isEmpty() ? null : frontPhoto(photos.getLast());
        PublicFrontPhoto first = photos.isEmpty() || monthOf(photos.getFirst().day()).equals(monthOf(photos.getLast().day()))
                ? null
                : frontPhoto(photos.getFirst());
        PublicFrontProject project;
        if (render != null) {
            project = latest == null ? null
                    : new PublicFrontProject(render.url(), render.screenUrl(), latest.url(), latest.screenUrl(), latest.date());
        } else {
            project = fallback;
        }
        return render == null
                ? new PublicFront(sector, latest, first, null, null, null, project)
                : new PublicFront(sector, latest, first, render.url(), render.thumbUrl(), render.screenUrl(), project);
    }

    /** El día que muestra una foto del catálogo: el del avance, el de la sesión o el de la subida en Argentina. */
    static LocalDate dayOf(PublicMedia media) {
        if (media.entryDate() != null) {
            return media.entryDate();
        }
        return media.takenOn() != null ? media.takenOn() : media.uploadedAt().atZone(ARGENTINA).toLocalDate();
    }

    private static PublicFrontPhoto frontPhoto(PlaceMedia item) {
        return new PublicFrontPhoto(item.media().url(), item.media().thumbUrl(), item.media().screenUrl(), item.day());
    }

    /** Los avances que aportaron material, cada uno una vez, del más viejo al más nuevo. */
    private static List<PublicPlaceNote> notesOf(List<PlaceMedia> items) {
        Map<UUID, PlaceNote> notes = new LinkedHashMap<>();
        items.stream().map(PlaceMedia::note).filter(note -> note != null).forEach(note -> notes.putIfAbsent(note.root(), note));
        return notes.values().stream()
                .sorted(Comparator.comparing(PlaceNote::date))
                .map(note -> new PublicPlaceNote(note.date(), note.stageName(), note.description()))
                .toList();
    }

    /** Los sectores de la casa con material, en su orden; después otros nombres; "General" al final. */
    private static List<String> orderedNames(List<String> sectors, Set<String> withMedia, Set<String> withTours) {
        Set<String> present = new LinkedHashSet<>(withMedia);
        present.addAll(withTours);
        List<String> ordered = new ArrayList<>();
        for (String sector : sectors) {
            if (present.contains(sector)) {
                ordered.add(sector);
            }
        }
        present.stream()
                .filter(name -> !sectors.contains(name) && !name.equals(GENERAL))
                .sorted()
                .forEach(ordered::add);
        if (present.contains(GENERAL) && !ordered.contains(GENERAL)) {
            ordered.add(GENERAL);
        }
        return ordered;
    }

    private static String nameOf(String sector) {
        return sector == null || sector.isBlank() ? GENERAL : sector;
    }

    private static String monthOf(LocalDate day) {
        return day.toString().substring(0, 7);
    }
}
