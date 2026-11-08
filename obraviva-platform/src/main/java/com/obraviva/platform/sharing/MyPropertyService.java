package com.obraviva.platform.sharing;

import com.obraviva.platform.developments.UnitAccessService;
import com.obraviva.platform.developments.UnitAccessService.UnitAccess;
import com.obraviva.platform.sharing.MyPropertyDtos.MyPropertyResponse;
import com.obraviva.platform.sharing.MyPropertyDtos.MyUnitSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * "Mi propiedad": la unidad vista por quien la compró, con sesión.
 *
 * <p>Las URLs de los recorridos se firman con vigencia larga, a diferencia de la vista
 * pública. El motivo de acortarlas allá era que una URL firmada sobrevive a la
 * revocación del enlace que la originó; acá no hay enlace que revocar, hay una sesión
 * que ya vence sola.
 */
@Service
public class MyPropertyService {

    private final UnitAccessService unitAccess;
    private final UnitViewAssembler assembler;

    public MyPropertyService(UnitAccessService unitAccess, UnitViewAssembler assembler) {
        this.unitAccess = unitAccess;
        this.assembler = assembler;
    }

    /** Vigencia de las URLs para una sesión autenticada. */
    private static final Duration SESSION_ASSET_TTL = Duration.ofHours(1);

    @Transactional(readOnly = true)
    public List<MyUnitSummary> myUnits(UUID userId) {
        return unitAccess.ownedUnits(userId).stream()
                .map(access -> new MyUnitSummary(
                        access.unit().id(),
                        access.unit().code(),
                        access.unit().description(),
                        access.unit().developmentName(),
                        access.unit().developmentAddress(),
                        access.unit().developmentKind().name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public MyPropertyResponse property(UUID userId, UUID unitId) {
        UnitAccess access = unitAccess.requireAccess(userId, unitId);
        return new MyPropertyResponse(
                access.unit().id(),
                assembler.assemble(access.unit(), SESSION_ASSET_TTL),
                access.isBuyer() ? null : new MyPropertyDtos.OwnerPreview(access.unit().developmentId()));
    }
}