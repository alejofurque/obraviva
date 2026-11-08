package com.obraviva.platform.activity;

import com.obraviva.platform.activity.ActivityDtos.ActivityItem;
import com.obraviva.platform.activity.ActivityDtos.ActivityPage;
import com.obraviva.platform.activity.ActivityDtos.ActivityPhoto;
import com.obraviva.platform.activity.ActivityDtos.CaptureActivity;
import com.obraviva.platform.activity.ActivityDtos.EntryActivity;
import com.obraviva.platform.activity.ActivityDtos.PendingAudio;
import com.obraviva.platform.activity.ActivityDtos.SessionActivity;
import com.obraviva.platform.activity.ActivityDtos.StageChangeView;
import com.obraviva.platform.activity.ActivityDtos.SupplyActivity;
import com.obraviva.platform.activity.ActivityDtos.UnseenCount;
import com.obraviva.platform.activity.ActivityQueries.Filter;
import com.obraviva.platform.activity.ActivityQueries.SessionKey;
import com.obraviva.platform.auth.User;
import com.obraviva.platform.auth.UserRepository;
import com.obraviva.platform.captures3d.Capture3d;
import com.obraviva.platform.captures3d.CaptureStatus;
import com.obraviva.platform.ai.extraction.AiExtraction;
import com.obraviva.platform.ai.extraction.ExtractionProposal;
import com.obraviva.platform.ai.extraction.SupplyAlert;
import com.obraviva.platform.ai.extraction.ProposalCodec;
import com.obraviva.platform.developments.DevelopmentAccessService;
import com.obraviva.platform.developments.StageCatalogService;
import com.obraviva.platform.developments.Unit;
import com.obraviva.platform.developments.UnitRepository;
import com.obraviva.platform.jobs.JobStatus;
import com.obraviva.platform.jobs.ProcessingJob;
import com.obraviva.platform.jobs.ProcessingJobService;
import com.obraviva.platform.media.MediaAsset;
import com.obraviva.platform.media.MediaKind;
import com.obraviva.platform.organizations.OrganizationMembershipService;
import com.obraviva.platform.progress.ProgressEntry;
import com.obraviva.platform.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Las novedades de un desarrollo: avances, fotos de sesión, recorridos 3D y, para el dueño, los
 * avisos de materiales de los audios aprobados, del más nuevo al más viejo, en páginas. Sólo
 * lectura más la marca de "visto hasta".
 *
 * <p>Cada fuente se consulta con el cursor y un tope, y se mezclan en memoria: la página tiene
 * a lo sumo {@code limit} ítems y nunca se trae la obra entera.
 *
 * <p>Permisos: el feed de todo el desarrollo, el contador, la marca y los audios en revisión
 * son del dueño. El feed de una casa ({@code unitId}) lo usa también quien trabaja en ella,
 * para "Últimos momentos". Otra organización recibe 404.
 */
@Service
public class ActivityService {

    static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 50;
    private static final int THUMBNAILS = 6;
    private static final int UNSEEN_CAP = 99;

    public enum Kind { ENTRY, AUDIO, PHOTOS, CAPTURE, SUPPLY }

    private final ActivityQueries queries;
    private final DevelopmentAccessService developmentAccess;
    private final UnitRepository unitRepository;
    private final UserRepository userRepository;
    private final StageCatalogService stageCatalog;
    private final ProcessingJobService jobService;
    private final StorageService storageService;
    private final OrganizationMembershipService membershipService;
    private final ProposalCodec proposalCodec;

    public ActivityService(
            ActivityQueries queries,
            DevelopmentAccessService developmentAccess,
            UnitRepository unitRepository,
            UserRepository userRepository,
            StageCatalogService stageCatalog,
            ProcessingJobService jobService,
            StorageService storageService,
            OrganizationMembershipService membershipService,
            ProposalCodec proposalCodec) {
        this.membershipService = membershipService;
        this.proposalCodec = proposalCodec;
        this.queries = queries;
        this.developmentAccess = developmentAccess;
        this.unitRepository = unitRepository;
        this.userRepository = userRepository;
        this.stageCatalog = stageCatalog;
        this.jobService = jobService;
        this.storageService = storageService;
    }

    @Transactional(readOnly = true)
    public ActivityPage feed(UUID userId, UUID developmentId, UUID unitId, UUID authorId, Kind kind,
                             Instant before, Integer limit) {
        UUID organizationId = developmentAccess.requireAccess(userId, developmentId);
        boolean owner;
        if (unitId == null) {
            developmentAccess.requireOwnerOf(userId, organizationId);
            owner = true;
        } else {
            requireUnitOf(unitId, developmentId, organizationId);
            if (!developmentAccess.canWorkOnUnit(userId, developmentId, organizationId, unitId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No estás asignado a esta casa");
            }
            owner = isOwner(userId, organizationId);
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        // Una de más por fuente: así se sabe si hay otra página sin pedirla.
        Filter filter = new Filter(developmentId, organizationId, unitId, authorId, before, size + 1);
        Instant seen = owner ? queries.seenUntil(userId, developmentId).orElse(null) : null;

        List<ActivityItem> items = new ArrayList<>();
        if (kind == null || kind == Kind.ENTRY || kind == Kind.AUDIO) {
            items.addAll(entryItems(filter, kind == Kind.AUDIO, userId, owner, seen));
        }
        if (kind == null || kind == Kind.PHOTOS) {
            items.addAll(sessionItems(filter, userId, owner, seen));
        }
        if (kind == null || kind == Kind.CAPTURE) {
            items.addAll(captureItems(filter, userId, owner, seen));
        }
        // Los avisos de materiales son para el dueño: no se publican ni los ve el encargado.
        if (owner && (kind == null || kind == Kind.SUPPLY)) {
            items.addAll(supplyItems(filter, userId, seen));
        }
        items.sort(Comparator.comparing(ActivityItem::at).reversed());
        List<ActivityItem> page = items.size() > size ? items.subList(0, size) : items;
        Instant next = items.size() > size ? page.getLast().at() : null;
        return new ActivityPage(List.copyOf(page), next, seen);
    }

    /** Cuántas novedades sin ver hay, con tope: para el punto de la pestaña. */
    @Transactional(readOnly = true)
    public UnseenCount unseenCount(UUID userId, UUID developmentId) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);
        Instant seen = queries.seenUntil(userId, developmentId).orElse(Instant.EPOCH);
        long count = queries.unseenEntries(developmentId, organizationId, userId, seen)
                + queries.unseenSessions(developmentId, organizationId, userId, seen)
                + queries.unseenCaptures(developmentId, organizationId, userId, seen)
                + queries.unseenSupplyAlerts(developmentId, organizationId, userId, seen);
        return new UnseenCount((int) Math.min(count, UNSEEN_CAP), count > UNSEEN_CAP);
    }

    /** Marca hasta el ítem más nuevo que se mostró; nunca más adelante que ahora. */
    @Transactional
    public void markSeen(UUID userId, UUID developmentId, Instant until) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);
        Instant now = Instant.now();
        queries.markSeen(userId, developmentId, organizationId, until.isAfter(now) ? now : until);
    }

    @Transactional(readOnly = true)
    public List<PendingAudio> pendingAudios(UUID userId, UUID developmentId) {
        UUID organizationId = developmentAccess.requireOwnerAccess(userId, developmentId);
        var audios = queries.audiosToReview(developmentId, organizationId);
        Map<UUID, String> units = unitCodes(developmentId, organizationId);
        Map<UUID, String> names = namesOf(audios.stream().map(audio -> audio.getCreatedBy()).toList());
        return audios.stream()
                .map(audio -> {
                    ExtractionProposal proposal = proposalCodec.decode(audio.getProposal());
                    return new PendingAudio(audio.getId(), audio.getUnitId(), units.get(audio.getUnitId()),
                            audio.getCreatedBy(), names.get(audio.getCreatedBy()), audio.getCreatedAt(),
                            proposal.progressNotes().size(), proposal.stageProposals().size());
                })
                .toList();
    }

    // --- Avances ---

    private List<ActivityItem> entryItems(Filter filter, boolean onlyAudio, UUID viewerId, boolean owner, Instant seen) {
        List<ProgressEntry> roots = queries.entryRoots(filter, onlyAudio);
        if (roots.isEmpty()) return List.of();
        List<ProgressEntry> versions = queries.versionsOf(filter.organizationId(), roots.stream().map(ProgressEntry::getRootId).toList());
        Map<UUID, List<ProgressEntry>> byRoot = versions.stream().collect(Collectors.groupingBy(ProgressEntry::getRootId));
        Map<UUID, UUID> rootOfVersion = versions.stream().collect(Collectors.toMap(ProgressEntry::getId, ProgressEntry::getRootId));
        Map<UUID, List<MediaAsset>> photosByRoot = queries
                .photosOfEntries(filter.organizationId(), rootOfVersion.keySet()).stream()
                .collect(Collectors.groupingBy(photo -> rootOfVersion.get(photo.getProgressEntryId())));
        Map<UUID, String> stageNames = stageNames(filter);
        Map<UUID, String> units = unitCodes(filter.developmentId(), filter.organizationId());
        Map<UUID, String> names = namesOf(versions.stream().map(ProgressEntry::getCreatedBy).toList());

        List<ActivityItem> items = new ArrayList<>();
        for (ProgressEntry root : roots) {
            List<ProgressEntry> all = byRoot.getOrDefault(root.getRootId(), List.of(root));
            ProgressEntry current = all.stream().filter(ProgressEntry::isCurrent).findFirst().orElse(all.getLast());
            boolean corrected = current.getVersion() > 1;
            // Sin ver: alguna versión posterior a la marca la hizo otra persona.
            Boolean unseen = owner ? all.stream().anyMatch(version -> after(version.getCreatedAt(), seen)
                    && !version.getCreatedBy().equals(viewerId)) : null;
            EntryActivity entry = new EntryActivity(
                    current.getId(),
                    root.getRootId(),
                    current.getEntryDate(),
                    current.getStageId() == null ? null : stageNames.get(current.getStageId()),
                    current.getSource().name(),
                    current.getDescription(),
                    corrected ? names.get(current.getCreatedBy()) : null,
                    corrected ? current.getCreatedAt() : null,
                    photosByRoot.getOrDefault(root.getRootId(), List.of()).stream()
                            .filter(photo -> photo.getKind() == MediaKind.PHOTO)
                            .map(this::photoOf).toList(),
                    root.getStagePercentAfter() == null && root.getStageStatusAfter() == null ? null : new StageChangeView(
                            root.getStagePercentBefore(), root.getStagePercentAfter(),
                            root.getStageStatusBefore() == null ? null : root.getStageStatusBefore().name(),
                            root.getStageStatusAfter() == null ? null : root.getStageStatusAfter().name()));
            items.add(new ActivityItem("ENTRY", "entry:" + root.getRootId(), root.getCreatedAt(), root.getUnitId(),
                    units.get(root.getUnitId()), root.getCreatedBy(), names.get(root.getCreatedBy()), unseen,
                    entry, null, null, null));
        }
        return items;
    }

    // --- Fotos de sesión ---

    private List<ActivityItem> sessionItems(Filter filter, UUID viewerId, boolean owner, Instant seen) {
        List<SessionKey> sessions = queries.sessions(filter);
        if (sessions.isEmpty()) return List.of();
        Map<UUID, String> units = unitCodes(filter.developmentId(), filter.organizationId());
        Map<UUID, String> names = namesOf(sessions.stream().map(SessionKey::authorId).toList());
        List<ActivityItem> items = new ArrayList<>();
        for (SessionKey session : sessions) {
            List<MediaAsset> media = queries.sessionMedia(filter.developmentId(), filter.organizationId(), session);
            Set<String> sectors = new LinkedHashSet<>();
            media.forEach(asset -> sectors.add(asset.getSector()));
            int photos = (int) media.stream().filter(asset -> asset.getKind() == MediaKind.PHOTO).count();
            SessionActivity activity = new SessionActivity(
                    session.day(), photos, media.size() - photos, List.copyOf(sectors),
                    media.stream().limit(THUMBNAILS).map(this::photoOf).toList());
            Boolean unseen = owner ? after(session.lastAt(), seen) && !session.authorId().equals(viewerId) : null;
            String id = "photos:%s:%s:%s".formatted(session.unitId(), session.authorId(), session.day());
            items.add(new ActivityItem("PHOTOS", id, session.lastAt(), session.unitId(), units.get(session.unitId()),
                    session.authorId(), names.get(session.authorId()), unseen, null, activity, null, null));
        }
        return items;
    }

    // --- Recorridos 3D ---

    private List<ActivityItem> captureItems(Filter filter, UUID viewerId, boolean owner, Instant seen) {
        List<Capture3d> captures = queries.captures(filter);
        if (captures.isEmpty()) return List.of();
        Map<UUID, ProcessingJob> jobs = jobService.findByResources(
                List.of(filter.organizationId()), captures.stream().map(Capture3d::getId).toList());
        Map<UUID, String> units = unitCodes(filter.developmentId(), filter.organizationId());
        Map<UUID, String> names = namesOf(captures.stream().map(Capture3d::getCreatedBy).toList());
        return captures.stream().map(capture -> {
            ProcessingJob job = jobs.get(capture.getId());
            String state;
            String reason = null;
            if (capture.getStatus() == CaptureStatus.AVAILABLE) {
                state = "READY";
            } else if (job != null && job.getStatus() == JobStatus.FAILED) {
                state = "FAILED";
                reason = job.getLastError();
            } else {
                state = "PROCESSING";
            }
            Boolean unseen = owner ? after(capture.getCreatedAt(), seen) && !capture.getCreatedBy().equals(viewerId) : null;
            return new ActivityItem("CAPTURE", "capture:" + capture.getId(), capture.getCreatedAt(), capture.getUnitId(),
                    units.get(capture.getUnitId()), capture.getCreatedBy(), names.get(capture.getCreatedBy()), unseen,
                    null, null, new CaptureActivity(capture.getId(), capture.getCaptureDate(), capture.getZone(), state, reason), null);
        }).toList();
    }

    // --- Avisos de materiales ---

    /** Un ítem por audio aprobado, con sus avisos tal como quedaron guardados y su autor: quien lo envió. */
    private List<ActivityItem> supplyItems(Filter filter, UUID viewerId, Instant seen) {
        List<AiExtraction> audios = queries.supplyAlerts(filter);
        if (audios.isEmpty()) return List.of();
        Map<UUID, String> units = unitCodes(filter.developmentId(), filter.organizationId());
        Map<UUID, String> names = namesOf(audios.stream().map(AiExtraction::getCreatedBy).toList());
        return audios.stream().map(audio -> {
            List<String> alerts = proposalCodec.decode(audio.getProposal()).supplyAlerts().stream()
                    .map(SupplyAlert::description).toList();
            Boolean unseen = after(audio.getReviewedAt(), seen) && !audio.getCreatedBy().equals(viewerId);
            return new ActivityItem("SUPPLY", "supply:" + audio.getId(), audio.getReviewedAt(), audio.getUnitId(),
                    units.get(audio.getUnitId()), audio.getCreatedBy(), names.get(audio.getCreatedBy()), unseen,
                    null, null, null, new SupplyActivity(audio.getId(), alerts));
        }).toList();
    }

    // --- Armado ---

    private ActivityPhoto photoOf(MediaAsset asset) {
        return new ActivityPhoto(
                storageService.presignDownload(asset.getStorageKey()),
                asset.getThumbStorageKey() == null ? null : storageService.presignDownload(asset.getThumbStorageKey()),
                asset.getScreenStorageKey() == null ? null : storageService.presignDownload(asset.getScreenStorageKey()),
                asset.getKind().name(), asset.getSector());
    }

    private static boolean after(Instant at, Instant seen) {
        return seen == null || at.isAfter(seen);
    }

    private boolean isOwner(UUID userId, UUID organizationId) {
        return membershipService.isOwnerOf(userId, organizationId);
    }

    /** Una casa de otro desarrollo, o de otra organización, no existe para este feed. */
    private void requireUnitOf(UUID unitId, UUID developmentId, UUID organizationId) {
        unitRepository.findByIdAndOrganizationIdIn(unitId, List.of(organizationId))
                .filter(unit -> unit.getDevelopment().getId().equals(developmentId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Casa no encontrada"));
    }

    private Map<UUID, String> unitCodes(UUID developmentId, UUID organizationId) {
        return unitRepository.findByDevelopmentIdAndOrganizationIdIn(developmentId, List.of(organizationId)).stream()
                .collect(Collectors.toMap(Unit::getId, Unit::getCode));
    }

    private Map<UUID, String> stageNames(Filter filter) {
        Map<UUID, String> names = new HashMap<>();
        stageCatalog.stageProgressOf(filter.developmentId(), List.of(filter.organizationId()))
                .forEach(stage -> names.put(stage.id(), stage.name()));
        return names;
    }

    private Map<UUID, String> namesOf(Collection<UUID> userIds) {
        Set<UUID> ids = new HashSet<>(userIds);
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, user -> user.getFullName() == null ? user.getEmail() : user.getFullName(),
                        (first, second) -> first, HashMap::new));
    }
}
