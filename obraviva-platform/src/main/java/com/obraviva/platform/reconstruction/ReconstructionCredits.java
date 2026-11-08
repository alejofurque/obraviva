package com.obraviva.platform.reconstruction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;

/**
 * Los créditos que le quedan a la cuenta del servicio de reconstrucción, con un caché corto.
 *
 * <p>La pestaña "Lugares y 3D" los muestra cada vez que se abre: sin caché, cada visita sería
 * una llamada al proveedor. Un minuto alcanza para no molestarlo y para que, después de
 * mandar un video, el número baje pronto. Se cachea también la falta de respuesta, para no
 * reintentar en cada carga contra un servicio caído.
 */
@Service
public class ReconstructionCredits {

    static final Duration TTL = Duration.ofMinutes(1);

    private final ReconstructionService reconstruction;
    private final Clock clock;

    private OptionalInt cached;
    private Instant cachedAt;

    @Autowired
    public ReconstructionCredits(ReconstructionService reconstruction) {
        this(reconstruction, Clock.systemUTC());
    }

    ReconstructionCredits(ReconstructionService reconstruction, Clock clock) {
        this.reconstruction = reconstruction;
        this.clock = clock;
    }

    public synchronized OptionalInt available() {
        Instant now = clock.instant();
        if (cached == null || !now.isBefore(cachedAt.plus(TTL))) {
            cached = reconstruction.availableCredits();
            cachedAt = now;
        }
        return cached;
    }
}
