package com.obraviva.platform.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Lo que hace la recuperación con los trabajos lo cubre {@code AudioExtractionWorkerTest}; acá,
 * que el arranque la dispare con los workers prendidos y no la dispare con los workers apagados,
 * porque sin ellos no hay quién retome el trabajo.
 */
class AudioJobRecoveryTest {

    @Test
    @DisplayName("con los workers prendidos, al arrancar devuelve a la cola los trabajos interrumpidos")
    void recoversWhenWorkersAreEnabled() {
        AudioExtractionJobService extractionJobs = mock(AudioExtractionJobService.class);

        new AudioJobRecovery(extractionJobs, true).recoverInterrupted();

        verify(extractionJobs).recoverInterruptedJobs();
    }

    @Test
    @DisplayName("con los workers apagados, no toca la cola")
    void doesNothingWhenWorkersAreDisabled() {
        AudioExtractionJobService extractionJobs = mock(AudioExtractionJobService.class);

        new AudioJobRecovery(extractionJobs, false).recoverInterrupted();

        verify(extractionJobs, never()).recoverInterruptedJobs();
    }
}
