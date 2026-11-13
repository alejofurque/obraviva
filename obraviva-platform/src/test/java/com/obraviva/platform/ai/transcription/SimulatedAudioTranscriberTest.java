package com.obraviva.platform.ai.transcription;

import com.obraviva.platform.ai.AiConfig;
import com.obraviva.platform.ai.extraction.AudioExtraction;
import com.obraviva.platform.ai.extraction.ExtractionRequest;
import com.obraviva.platform.ai.extraction.StageContext;
import com.obraviva.platform.ai.extraction.mock.MockProgressExtractor;
import com.obraviva.platform.common.process.ProcessRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Transcripción simulada")
class SimulatedAudioTranscriberTest {

    private static AudioTranscriber configured(String provider) {
        return new AiConfig().audioTranscriber(new ProcessRunner(), "ffmpeg", "", 300, provider);
    }

    @Test
    @DisplayName("devuelve un texto que avisa que es simulado, sin leer el archivo")
    void returnsLabeledTextWithoutReadingTheFile() {
        Transcription transcription = new SimulatedAudioTranscriber().transcribe(Path.of("no-existe.ogg"));

        assertThat(transcription.text()).startsWith("Transcripción simulada");
    }

    @Test
    @DisplayName("le da al simulador de interpretación algo que proponer")
    void feedsTheSimulatedExtractor() {
        StageContext masonry = new StageContext(UUID.randomUUID(), "Mampostería", 4, "IN_PROGRESS");

        AudioExtraction extraction = new MockProgressExtractor().extract(
                new ExtractionRequest(SimulatedAudioTranscriber.TEXT, List.of(masonry)));

        assertThat(extraction.stageProposals()).hasSize(1);
        assertThat(extraction.stageProposals().getFirst().proposedStatus()).isEqualTo("IN_PROGRESS");
        assertThat(extraction.supplyAlerts()).hasSize(1);
    }

    @Test
    @DisplayName("el proveedor elige el adaptador y uno desconocido no arranca")
    void providerSelectsTheAdapter() {
        assertThat(configured("mock")).isInstanceOf(SimulatedAudioTranscriber.class);
        assertThat(configured("whisper")).isInstanceOf(WhisperAudioTranscriber.class);
        assertThatThrownBy(() -> configured("otro"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mock, whisper");
    }
}
