package com.obraviva.platform.activity;

import com.obraviva.platform.ai.extraction.AiExtraction;
import com.obraviva.platform.ai.extraction.ReviewStatus;
import com.obraviva.platform.captures3d.Capture3d;
import com.obraviva.platform.captures3d.CaptureStatus;
import com.obraviva.platform.media.MediaAsset;
import com.obraviva.platform.media.MediaStatus;
import com.obraviva.platform.progress.ProgressEntry;
import com.obraviva.platform.progress.ProgressSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Las consultas del feed, juntas para no repartir búsquedas de un solo uso por cinco
 * repositorios. Todas van acotadas por desarrollo y organización, y todas paginan por el
 * cursor ({@code before}) con un tope: una obra de un año nunca se trae entera.
 */
@Repository
class ActivityQueries {

    /** Lo que en el feed es una sesión de fotos: con lugar, sin avance y fuera de la galería. */
    private static final String SESSION_MEDIA = """
            m.developmentId = :development and m.organizationId = :organization and m.unitId is not null
            and m.sector is not null and m.progressEntryId is null and m.showcase = false and m.status = :available""";

    /** Un recorrido se cuenta desde que se confirmó la subida (o ya está disponible). */
    private static final String CONFIRMED_CAPTURE = """
            c.developmentId = :development and c.organizationId = :organization and c.unitId is not null
            and (c.confirmedAt is not null or c.status = :ready)""";

    /**
     * Si la propuesta guardada de un audio trae avisos de materiales. La propuesta es texto JSON
     * (V13); un texto que no sea JSON cuenta como sin avisos en vez de romper el feed. Los CASE
     * anidados fijan el orden: Postgres no garantiza en qué orden evalúa un AND.
     */
    private static final String HAS_SUPPLY_ALERTS = """
            case when not pg_input_is_valid(a.proposal, 'jsonb') then false
                 when jsonb_typeof(cast(a.proposal as jsonb) -> 'supplyAlerts') <> 'array' then false
                 else jsonb_array_length(cast(a.proposal as jsonb) -> 'supplyAlerts') > 0 end""";

    /** Audios aprobados del desarrollo con avisos de materiales; se ubican en el momento de la aprobación. */
    private static final String APPROVED_WITH_ALERTS = """
            a.development_id = :development and a.organization_id = :organization and a.review_status = 'APPROVED'
            and a.reviewed_at is not null and a.proposal is not null""" + " and " + HAS_SUPPLY_ALERTS;

    private final EntityManager entityManager;

    ActivityQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Filtros comunes a todas las fuentes. */
    record Filter(UUID developmentId, UUID organizationId, UUID unitId, UUID authorId, Instant before, int limit) {
    }

    /** Una sesión: una casa, quien subió y el día, con el momento de su última foto. */
    record SessionKey(UUID unitId, UUID authorId, LocalDate day, Instant lastAt, long count) {
    }

    // --- Avances ---

    /**
     * Primeras versiones de avances, de la más nueva a la más vieja: el avance se ubica en el
     * momento de su primera publicación, aunque después se corrija. La carga inicial no es
     * novedad: es lo que pasó antes de empezar el seguimiento, y aparecería toda junta el día
     * en que se cargó.
     */
    List<ProgressEntry> entryRoots(Filter filter, boolean onlyAudio) {
        StringBuilder jpql = new StringBuilder("""
                select e from ProgressEntry e
                where e.developmentId = :development and e.organizationId = :organization and e.version = 1
                and e.source <> :initialLoad""");
        if (filter.unitId() != null) jpql.append(" and e.unitId = :unit");
        if (filter.authorId() != null) jpql.append(" and e.createdBy = :author");
        if (filter.before() != null) jpql.append(" and e.createdAt < :before");
        if (onlyAudio) jpql.append(" and e.source = :audio");
        jpql.append(" order by e.createdAt desc");
        TypedQuery<ProgressEntry> query = bind(entityManager.createQuery(jpql.toString(), ProgressEntry.class), filter);
        if (onlyAudio) query.setParameter("audio", ProgressSource.AUDIO);
        query.setParameter("initialLoad", ProgressSource.INITIAL_LOAD);
        return query.setMaxResults(filter.limit()).getResultList();
    }

    /** Todas las versiones de esos avances, para saber la vigente, quién corrigió y sus fotos. */
    List<ProgressEntry> versionsOf(UUID organizationId, Collection<UUID> rootIds) {
        if (rootIds.isEmpty()) return List.of();
        return entityManager.createQuery("""
                        select e from ProgressEntry e
                        where e.organizationId = :organization and e.rootId in :roots order by e.version asc""",
                        ProgressEntry.class)
                .setParameter("organization", organizationId)
                .setParameter("roots", rootIds)
                .getResultList();
    }

    List<MediaAsset> photosOfEntries(UUID organizationId, Collection<UUID> entryIds) {
        if (entryIds.isEmpty()) return List.of();
        return entityManager.createQuery("""
                        select m from MediaAsset m
                        where m.organizationId = :organization and m.progressEntryId in :entries and m.status = :available
                        order by m.createdAt asc""", MediaAsset.class)
                .setParameter("organization", organizationId)
                .setParameter("entries", entryIds)
                .setParameter("available", MediaStatus.AVAILABLE)
                .getResultList();
    }

    // --- Fotos de sesión ---

    /** Las sesiones agrupadas por casa, autor y día, de la más nueva a la más vieja. */
    List<SessionKey> sessions(Filter filter) {
        StringBuilder jpql = new StringBuilder("select m.unitId, m.createdBy, m.takenOn, max(m.createdAt), count(m) from MediaAsset m where ")
                .append(SESSION_MEDIA);
        if (filter.unitId() != null) jpql.append(" and m.unitId = :unit");
        if (filter.authorId() != null) jpql.append(" and m.createdBy = :author");
        jpql.append(" group by m.unitId, m.createdBy, m.takenOn");
        if (filter.before() != null) jpql.append(" having max(m.createdAt) < :before");
        jpql.append(" order by max(m.createdAt) desc");
        TypedQuery<Object[]> query = bind(entityManager.createQuery(jpql.toString(), Object[].class), filter);
        query.setParameter("available", MediaStatus.AVAILABLE);
        return query.setMaxResults(filter.limit()).getResultList().stream()
                .map(row -> new SessionKey((UUID) row[0], (UUID) row[1], (LocalDate) row[2], (Instant) row[3], (Long) row[4]))
                .toList();
    }

    /** Las fotos y videos de una sesión, en el orden en que se subieron. */
    List<MediaAsset> sessionMedia(UUID developmentId, UUID organizationId, SessionKey session) {
        String day = session.day() == null ? "m.takenOn is null" : "m.takenOn = :day";
        TypedQuery<MediaAsset> query = entityManager.createQuery(
                        "select m from MediaAsset m where " + SESSION_MEDIA
                                + " and m.unitId = :unit and m.createdBy = :author and " + day + " order by m.createdAt asc",
                        MediaAsset.class)
                .setParameter("development", developmentId)
                .setParameter("organization", organizationId)
                .setParameter("available", MediaStatus.AVAILABLE)
                .setParameter("unit", session.unitId())
                .setParameter("author", session.authorId());
        if (session.day() != null) query.setParameter("day", session.day());
        return query.getResultList();
    }

    // --- Recorridos 3D ---

    List<Capture3d> captures(Filter filter) {
        StringBuilder jpql = new StringBuilder("select c from Capture3d c where ").append(CONFIRMED_CAPTURE);
        if (filter.unitId() != null) jpql.append(" and c.unitId = :unit");
        if (filter.authorId() != null) jpql.append(" and c.createdBy = :author");
        if (filter.before() != null) jpql.append(" and c.createdAt < :before");
        jpql.append(" order by c.createdAt desc");
        TypedQuery<Capture3d> query = bind(entityManager.createQuery(jpql.toString(), Capture3d.class), filter);
        query.setParameter("ready", CaptureStatus.AVAILABLE);
        return query.setMaxResults(filter.limit()).getResultList();
    }

    // --- Avisos de materiales ---

    /** Los audios aprobados con avisos de materiales, del más nuevo al más viejo por su aprobación. */
    List<AiExtraction> supplyAlerts(Filter filter) {
        StringBuilder sql = new StringBuilder("select cast(a.id as varchar) from ai_extractions a where ").append(APPROVED_WITH_ALERTS);
        if (filter.unitId() != null) sql.append(" and a.unit_id = :unit");
        if (filter.authorId() != null) sql.append(" and a.created_by = :author");
        if (filter.before() != null) sql.append(" and a.reviewed_at < :before");
        sql.append(" order by a.reviewed_at desc");
        var query = entityManager.createNativeQuery(sql.toString())
                .setParameter("development", filter.developmentId())
                .setParameter("organization", filter.organizationId());
        if (filter.unitId() != null) query.setParameter("unit", filter.unitId());
        if (filter.authorId() != null) query.setParameter("author", filter.authorId());
        if (filter.before() != null) query.setParameter("before", filter.before());
        List<UUID> ids = query.setMaxResults(filter.limit()).getResultList().stream()
                .map(id -> UUID.fromString(id.toString())).toList();
        if (ids.isEmpty()) return List.of();
        return entityManager.createQuery("""
                        select a from AiExtraction a where a.organizationId = :organization and a.id in :ids
                        order by a.reviewedAt desc""", AiExtraction.class)
                .setParameter("organization", filter.organizationId())
                .setParameter("ids", ids)
                .getResultList();
    }

    // --- Sin ver ---

    /** Avances con alguna versión posterior a la marca publicada por otra persona; la carga inicial no cuenta. */
    long unseenEntries(UUID developmentId, UUID organizationId, UUID viewerId, Instant seenUntil) {
        return entityManager.createQuery("""
                        select count(distinct e.rootId) from ProgressEntry e
                        where e.developmentId = :development and e.organizationId = :organization
                        and e.createdAt > :seen and e.createdBy <> :viewer and e.source <> :initialLoad""", Long.class)
                .setParameter("initialLoad", ProgressSource.INITIAL_LOAD)
                .setParameter("development", developmentId)
                .setParameter("organization", organizationId)
                .setParameter("seen", seenUntil)
                .setParameter("viewer", viewerId)
                .getSingleResult();
    }

    long unseenSessions(UUID developmentId, UUID organizationId, UUID viewerId, Instant seenUntil) {
        return entityManager.createQuery("select m.unitId, m.createdBy, m.takenOn from MediaAsset m where " + SESSION_MEDIA
                                + " and m.createdAt > :seen and m.createdBy <> :viewer group by m.unitId, m.createdBy, m.takenOn",
                        Object[].class)
                .setParameter("development", developmentId)
                .setParameter("organization", organizationId)
                .setParameter("available", MediaStatus.AVAILABLE)
                .setParameter("seen", seenUntil)
                .setParameter("viewer", viewerId)
                .getResultList().size();
    }

    long unseenCaptures(UUID developmentId, UUID organizationId, UUID viewerId, Instant seenUntil) {
        return entityManager.createQuery("select count(c) from Capture3d c where " + CONFIRMED_CAPTURE
                        + " and c.createdAt > :seen and c.createdBy <> :viewer", Long.class)
                .setParameter("development", developmentId)
                .setParameter("organization", organizationId)
                .setParameter("ready", CaptureStatus.AVAILABLE)
                .setParameter("seen", seenUntil)
                .setParameter("viewer", viewerId)
                .getSingleResult();
    }

    long unseenSupplyAlerts(UUID developmentId, UUID organizationId, UUID viewerId, Instant seenUntil) {
        Object count = entityManager.createNativeQuery("select count(*) from ai_extractions a where " + APPROVED_WITH_ALERTS
                        + " and a.reviewed_at > :seen and a.created_by <> :viewer")
                .setParameter("development", developmentId)
                .setParameter("organization", organizationId)
                .setParameter("seen", seenUntil)
                .setParameter("viewer", viewerId)
                .getSingleResult();
        return ((Number) count).longValue();
    }

    // --- Audios en revisión ---

    List<AiExtraction> audiosToReview(UUID developmentId, UUID organizationId) {
        return entityManager.createQuery("""
                        select a from AiExtraction a
                        where a.developmentId = :development and a.organizationId = :organization and a.reviewStatus = :ready
                        order by a.createdAt desc""", AiExtraction.class)
                .setParameter("development", developmentId)
                .setParameter("organization", organizationId)
                .setParameter("ready", ReviewStatus.READY_FOR_REVIEW)
                .getResultList();
    }

    // --- Marca de visto ---

    Optional<Instant> seenUntil(UUID userId, UUID developmentId) {
        List<?> rows = entityManager.createNativeQuery(
                        "select seen_until from activity_seen where user_id = :user and development_id = :development")
                .setParameter("user", userId)
                .setParameter("development", developmentId)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(toInstant(rows.getFirst()));
    }

    /** La marca nunca retrocede: si ya vio algo más nuevo, se queda donde estaba. */
    void markSeen(UUID userId, UUID developmentId, UUID organizationId, Instant until) {
        entityManager.createNativeQuery("""
                        insert into activity_seen (user_id, development_id, organization_id, seen_until, updated_at)
                        values (:user, :development, :organization, :until, now())
                        on conflict (user_id, development_id) do update
                        set seen_until = greatest(activity_seen.seen_until, excluded.seen_until), updated_at = now()""")
                .setParameter("user", userId)
                .setParameter("development", developmentId)
                .setParameter("organization", organizationId)
                .setParameter("until", until)
                .executeUpdate();
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalStateException("Fecha inesperada: " + value);
    }

    private static <T> TypedQuery<T> bind(TypedQuery<T> query, Filter filter) {
        query.setParameter("development", filter.developmentId());
        query.setParameter("organization", filter.organizationId());
        if (filter.unitId() != null) query.setParameter("unit", filter.unitId());
        if (filter.authorId() != null) query.setParameter("author", filter.authorId());
        if (filter.before() != null) query.setParameter("before", filter.before());
        return query;
    }
}
