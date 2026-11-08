package com.obraviva.platform.reconstruction;

import com.obraviva.platform.reconstruction.mock.MockReconstructionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReconstructionCreditsTest {

    /** Un proveedor que cuenta cuántas veces le preguntaron y contesta lo que se le diga. */
    private static final class CountingProvider implements ReconstructionService {
        final AtomicInteger calls = new AtomicInteger();
        OptionalInt answer = OptionalInt.of(9);

        @Override
        public String submitVideo(Path videoFile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReconstructionStatus checkStatus(String externalReference) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void downloadResult(String externalReference, Path destination) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OptionalInt availableCredits() {
            calls.incrementAndGet();
            return answer;
        }
    }

    /** Un reloj que se adelanta a mano. */
    private static final class ManualClock extends Clock {
        Instant now = Instant.parse("2026-10-23T12:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("dentro del minuto no se vuelve a preguntar al proveedor; después, sí")
    void cachesForAMinute() {
        CountingProvider provider = new CountingProvider();
        ManualClock clock = new ManualClock();
        ReconstructionCredits credits = new ReconstructionCredits(provider, clock);

        assertThat(credits.available()).hasValue(9);
        clock.now = clock.now.plusSeconds(59);
        provider.answer = OptionalInt.of(8);
        assertThat(credits.available()).hasValue(9);
        assertThat(provider.calls).hasValue(1);

        clock.now = clock.now.plus(Duration.ofSeconds(1));
        assertThat(credits.available()).hasValue(8);
        assertThat(provider.calls).hasValue(2);
    }

    @Test
    @DisplayName("la falta de respuesta también se cachea: no se insiste contra un servicio caído")
    void cachesTheMissingAnswer() {
        CountingProvider provider = new CountingProvider();
        provider.answer = OptionalInt.empty();
        ReconstructionCredits credits = new ReconstructionCredits(provider, new ManualClock());

        assertThat(credits.available()).isEmpty();
        assertThat(credits.available()).isEmpty();
        assertThat(provider.calls).hasValue(1);
    }

    @Test
    @DisplayName("el simulado no trabaja con créditos: no hay número")
    void mockHasNoCredits() {
        assertThat(new MockReconstructionService(1, MockReconstructionService.Outcome.SUCCESS).availableCredits()).isEmpty();
    }
}
