package com.obraviva.platform.sharing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Lo que ve quien abre un enlace público.
 *
 * <p>Estos records se construyen campo por campo y <strong>no contienen ningún
 * identificador interno</strong>: ni de unidad, ni de desarrollo, ni de organización,
 * ni de etapa, ni de captura. Quien tiene el enlace no los necesita, y no dárselos
 * elimina la posibilidad de usarlos para probar contra los endpoints autenticados.
 *
 * <p>Tampoco contienen precios, cuotas, contratos, datos personales ni información de
 * otras unidades. La lista de lo que se excluye es tan importante como la de lo que se
 * incluye: es una decisión de producto del documento maestro, no una omisión.
 *
 * <p>La proyección se arma a mano en vez de recortarse desde una entidad porque el
 * recorte falla en silencio: si alguien agrega un campo sensible a la entidad, aparece
 * solo en la respuesta. Acá hay que agregarlo a propósito.
 */
public final class PublicViewDtos {

    private PublicViewDtos() {
    }

    /**
     * {@code overallProgressPercent} es el avance ponderado de la obra, el mismo que
     * calcula el panel del desarrollista, redondeado a entero. {@code currentStage} es
     * nulo si la obra no tiene etapas o ya las terminó todas.
     */
    public record PublicUnitView(
            String unitCode,
            String unitDescription,
            String developmentName,
            String developmentAddress,
            String developmentKind,
            PublicUnitDetails details,
            int overallProgressPercent,
            PublicStage currentStage,
            List<PublicStage> stages,
            List<PublicProgressEntry> timeline,
            List<PublicCapture> captures,
            List<PublicMedia> gallery,
            List<PublicFloorPlan> plans,
            List<PublicComparison> comparisons,
            List<PublicMedia> showcase,
            PublicDevelopment development,
            /** Sectores del recorrido 3D definidos para la casa, en orden. */
            List<String> sectors,
            /**
             * La casa lugar por lugar: cada sector con material, en el orden de la casa, y
             * "General" al final para lo que no tiene sector. Sólo lo de la casa: las obras
             * comunes tienen su lugar en la historia.
             */
            List<PublicPlace> places,
            /** El frente de la casa: portada viva y "Tu casa, de frente". Siempre presente. */
            PublicFront front) {
    }

    /**
     * El frente de la casa y lo que se arma con él. {@code sector} es el lugar marcado como
     * frente, nunca adivinado por el nombre, y es nulo si no hay. {@code latest} es su foto
     * más reciente y {@code first}, la primera si es de otro mes: las dos salen sólo de fotos
     * de sesión y de avances, nunca de la galería, que es material de venta y no tiene fecha
     * de toma. {@code renderUrl} es el render del frente que cargó el dueño, nulo si no hay, con
     * su miniatura y su versión de pantalla, nulas mientras no estén: se usa el original.
     * {@code project} es lo que compara "El proyecto": ese render contra la foto de hoy o, si
     * la casa no tiene render del frente, su comparación más reciente de Proyecto y realidad.
     */
    public record PublicFront(String sector, PublicFrontPhoto latest, PublicFrontPhoto first, String renderUrl,
                              String renderThumbUrl, String renderScreenUrl, PublicFrontProject project) {
    }

    /**
     * Un render y la foto contra la que se compara, con el día que muestra esa foto.
     * {@code renderScreenUrl} y {@code photoScreenUrl} son las versiones de pantalla, nulas si
     * no están listas.
     */
    public record PublicFrontProject(String renderUrl, String renderScreenUrl, String photoUrl, String photoScreenUrl,
                                     LocalDate photoDate) {
    }

    /**
     * Una foto del frente con el día que muestra: el del avance, el de la sesión o el de la
     * subida. Miniatura y pantalla nulas mientras no estén: se usa el original.
     */
    public record PublicFrontPhoto(String url, String thumbUrl, String screenUrl, LocalDate date) {
    }

    /** Un lugar de la casa y sus meses con material, del más viejo al más nuevo. */
    public record PublicPlace(String name, List<PublicPlaceMonth> months) {
    }

    /**
     * Lo de un lugar en un mes ({@code 2026-10}): sus fotos y videos, en orden, si ese mes
     * tiene recorrido 3D, que se abre con el mes y el lugar, y el texto de los avances que
     * aportaron fotos o videos a ese lugar ese mes.
     */
    public record PublicPlaceMonth(String month, List<PublicMedia> photos, List<PublicMedia> videos, boolean tour,
                                   List<PublicPlaceNote> notes) {
    }

    /** El texto de un avance, contado desde el lugar al que aportó material. */
    public record PublicPlaceNote(LocalDate date, String stageName, String description) {
    }

    /**
     * El desarrollo, visto desde la casa: lo que el comprador quiere saber del barrio. Sin
     * datos comerciales. La portada llega firmada, con su miniatura y su versión de pantalla
     * nulas mientras no estén.
     */
    public record PublicDevelopment(
            String description,
            String locality,
            String province,
            String developmentType,
            BigDecimal landAreaM2,
            List<String> amenities,
            List<String> services,
            String architect,
            String builder,
            YearMonth estimatedDelivery,
            String coverUrl,
            String coverThumbUrl,
            String coverScreenUrl) {
    }

    /**
     * Proyecto y realidad del mismo lugar. {@code common} marca, en un edificio, una
     * comparación de la obra y no de la unidad. {@code photoDate} es la fecha del avance
     * al que pertenece la foto, o nula si es una foto suelta. El render trae su miniatura y su
     * versión de pantalla, nulas mientras no estén.
     */
    public record PublicComparison(
            String title,
            String renderUrl,
            String renderThumbUrl,
            String renderScreenUrl,
            String photoUrl,
            LocalDate photoDate,
            String photoStageName,
            boolean common) {
    }

    /**
     * Ficha de la unidad. Cada dato es nulo si el dueño no lo cargó: la vista muestra lo
     * que hay, sin completar con valores que parezcan reales. La portada llega firmada, con su
     * miniatura y su versión de pantalla nulas mientras no estén.
     */
    public record PublicUnitDetails(
            BigDecimal areaM2,
            Integer bedrooms,
            Integer bathrooms,
            List<String> features,
            YearMonth estimatedDelivery,
            String coverUrl,
            String coverThumbUrl,
            String coverScreenUrl,
            String modelName,
            BigDecimal lotAreaM2,
            BigDecimal coveredAreaM2,
            BigDecimal semiCoveredAreaM2,
            Integer rooms,
            Integer toilettes,
            Integer floors,
            Integer parkingSpaces,
            String orientation,
            String longDescription,
            String finishes) {
    }

    /** Planta de la unidad a escala real: dimensiones en metros y ambientes dentro. */
    /** {@code imageUrl} es el plano de arquitectura, firmado; nulo si sólo hay ambientes dibujados. */
    public record PublicFloorPlan(
            String name,
            BigDecimal widthM,
            BigDecimal depthM,
            List<PublicRoom> rooms,
            String imageUrl) {
    }

    /**
     * Ambiente tocable. {@code polygon} es x1, y1, x2, y2... en metros; {@code areaM2} es
     * la superficie declarada por el dueño si la cargó, y si no la del dibujo, lo que
     * {@code declaredArea} permite aclarar en pantalla.
     */
    public record PublicRoom(String name, List<Double> polygon, BigDecimal areaM2, boolean declaredArea) {
    }

    /** Etapa con su avance declarado. Nunca con su presupuesto. */
    /** Fechas previstas y reales: el comprador ve cuándo empezó, cuándo terminó y cuándo se espera. */
    public record PublicStage(
            String name,
            int orderIndex,
            String status,
            int progressPercent,
            String description,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            LocalDate startedOn,
            LocalDate completedOn) {
    }

    /**
     * Avance vigente. {@code corrected} dice si tuvo correcciones: la transparencia
     * incluye mostrar que algo se corrigió. {@code source} distingue lo cargado a mano
     * de lo publicado desde un audio confirmado.
     */
    public record PublicProgressEntry(
            LocalDate date,
            String stageName,
            String description,
            String source,
            boolean corrected,
            List<PublicMedia> photos,
            /** Novedad de las obras comunes del desarrollo, no de la casa. */
            boolean common,
            /** Lo que el avance le hizo a su etapa al publicarse; nulo si no la tocó. */
            PublicStageChange stageChange) {
    }

    /** "35% → 50%", "En ejecución → Demorado": porcentajes y estados, nada económico. */
    public record PublicStageChange(Integer percentBefore, Integer percentAfter, String statusBefore, String statusAfter) {
    }

    /**
     * Captura navegable. Sin identificador: el visor las ordena por fecha, y la fecha
     * alcanza para el selector temporal.
     *
     * <p>La URL viene firmada con vigencia corta. Una URL firmada sobrevive a la
     * revocación del enlace, así que su duración acota esa ventana.
     *
     * <p>{@code common} marca los recorridos de áreas comunes de un edificio, que ven todas
     * sus unidades. En una obra única no hay áreas comunes: todo es de la casa.
     */
    public record PublicCapture(
            LocalDate date,
            String description,
            String zone,
            String stageName,
            boolean common,
            String splatUrl,
            PublicTransform transform) {
    }

    /** Transformación espacial de la captura, necesaria para alinearla en el visor. */
    public record PublicTransform(
            double positionX,
            double positionY,
            double positionZ,
            double rotationX,
            double rotationY,
            double rotationZ,
            double scale) {
    }

    /**
     * Foto o video de la obra.
     *
     * <p>Son los medios del desarrollo y no de la unidad: el modelo vincula los medios
     * a la obra, no a cada unidad. Correcto para el caso de uso —el comprador quiere
     * ver cómo avanza el edificio— pero significa que acá no puede haber nunca
     * material privado de una unidad concreta sin separar antes el modelo.
     *
     * <p>{@code entryDate} y {@code stageName} vienen del avance al que la foto
     * pertenece, y son nulos si es una foto suelta. {@code takenOn} es el día de la sesión de
     * recorrido en que se sacó, nulo si no es de una. La fecha de subida viene como
     * instante: la zona horaria para mostrarla la decide quien la ve.
     *
     * <p>{@code url} es el original. {@code thumbUrl} (480 px) y {@code screenUrl} (1600 px)
     * son las versiones livianas en JPEG; en un video, su fotograma de portada. Nulas
     * mientras no estén: quien muestra usa el original. Ninguna lleva la clave interna: son
     * URLs firmadas.
     */
    public record PublicMedia(
            String url,
            String contentType,
            String kind,
            Instant uploadedAt,
            LocalDate entryDate,
            String stageName,
            LocalDate takenOn,
            String thumbUrl,
            String screenUrl) {
    }
}