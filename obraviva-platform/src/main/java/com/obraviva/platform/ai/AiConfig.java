package com.obraviva.platform.ai;

import com.obraviva.platform.ai.extraction.ExtractionValidator;
import com.obraviva.platform.ai.extraction.ProgressExtractor;
import com.obraviva.platform.ai.extraction.ProposalCodec;
import com.obraviva.platform.ai.extraction.gemini.GeminiProgressExtractor;
import com.obraviva.platform.ai.extraction.mock.MockProgressExtractor;
import com.obraviva.platform.ai.transcription.AudioTranscriber;
import com.obraviva.platform.ai.transcription.SimulatedAudioTranscriber;
import com.obraviva.platform.ai.transcription.WhisperAudioTranscriber;
import com.obraviva.platform.video.ffmpeg.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Registro de los beans del módulo de inteligencia artificial.
 *
 * <p>La ruta del modelo de transcripción es propia de cada máquina, igual que la
 * clave del servicio de reconstrucción: se define por variable de entorno y nunca
 * en el archivo de configuración versionado.
 *
 * <p>La selección de proveedor usa un condicional explícito por el mismo motivo que
 * en el módulo de reconstrucción: la ubicación de las anotaciones condicionales
 * cambió entre versiones de Spring Boot, y un condicional sobre una propiedad no
 * depende de ninguna versión.
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public AudioTranscriber audioTranscriber(
            ProcessRunner processRunner,
            @Value("${obraviva.ai.transcription.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${obraviva.ai.transcription.model-path:}") String modelPath,
            @Value("${obraviva.ai.transcription.timeout-seconds:300}") long timeoutSeconds,
            @Value("${obraviva.ai.transcription.provider:whisper}") String provider) {

        if ("mock".equalsIgnoreCase(provider)) {
            log.warn("Adaptador de transcripción SIMULADO activo. "
                    + "Todos los audios dan el mismo texto de ejemplo.");
            return new SimulatedAudioTranscriber();
        }

        if (!"whisper".equalsIgnoreCase(provider)) {
            throw new IllegalStateException(
                    "Proveedor de transcripción desconocido: '" + provider
                            + "'. Valores admitidos: mock, whisper.");
        }

        log.info("Adaptador de transcripción REAL activo (whisper local).");

        Path model = (modelPath == null || modelPath.isBlank())
                ? Path.of("modelo-no-configurado.bin")
                : Path.of(modelPath);

        if (!Files.isRegularFile(model)) {
            log.warn("El modelo de transcripción no está disponible en '{}'. "
                    + "La transcripción de audio fallará hasta que se configure "
                    + "OBRAVIVA_WHISPER_MODEL con la ruta al archivo ggml.", model);
        } else {
            log.info("Modelo de transcripción local: {}", model);
        }

        return new WhisperAudioTranscriber(
                ffmpegPath, model, Duration.ofSeconds(timeoutSeconds), processRunner);
    }

    @Bean
    public ExtractionValidator extractionValidator() {
        return new ExtractionValidator();
    }

    @Bean
    public ProposalCodec proposalCodec() {
        return new ProposalCodec();
    }

    /**
     * Único punto de contacto con la API de Spring AI en todo el sistema. Si sus
     * nombres cambian en una versión futura, se corrige acá y en ningún otro lado.
     *
     * <p>{@code ChatModel} se recibe como {@link ObjectProvider} porque puede no
     * existir: con los adaptadores simulados activos, la autoconfiguración de Spring
     * AI queda deshabilitada y no hay ningún bean de modelo en el contexto. Exigirlo
     * impediría arrancar sin credenciales, que es el modo de trabajo diario.
     *
     * <p>Cuando no hay proveedor, el bean existe igual y falla al usarse con un
     * mensaje que explica qué configurar. Devolver algo que no sirve es peor que no
     * arrancar sólo si el fallo es silencioso; éste no lo es.
     */
    @Bean
    public ChatCompletion chatCompletion(ObjectProvider<ChatModel> chatModelProvider) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();

        if (chatModel == null) {
            return prompt -> {
                throw new IllegalStateException(
                        "No hay ningún modelo de chat en el contexto. Verificá que "
                                + "'spring.ai.model.chat' esté en 'google-genai' y que "
                                + "GEMINI_API_KEY esté definida en .env.");
            };
        }

        ChatClient chatClient = ChatClient.create(chatModel);
        return prompt -> chatClient.prompt().user(prompt).call().content();
    }

    /** Selecciona el adaptador de interpretación de audios. */
    @Bean
    public ProgressExtractor progressExtractor(
            ChatCompletion chatCompletion,
            @Value("${obraviva.ai.extraction.provider:mock}") String provider,
            @Value("${obraviva.ai.extraction.prompt-version:v1}") String promptVersion,
            @Value("${obraviva.ai.extraction.model-id:gemini-3.6-flash}") String modelId) {

        if ("mock".equalsIgnoreCase(provider)) {
            log.warn("Adaptador de interpretación SIMULADO activo. "
                    + "No se consume cuota del proveedor de modelo de lenguaje.");
            return new MockProgressExtractor();
        }

        if ("gemini".equalsIgnoreCase(provider)) {
            log.warn("Adaptador de interpretación REAL activo (modelo {}, prompt {}). "
                    + "Cada interpretación consume cuota del proveedor.", modelId, promptVersion);

            return new GeminiProgressExtractor(
                    chatCompletion, loadPrompt(promptVersion), promptVersion, modelId);
        }

        throw new IllegalStateException(
                "Proveedor de interpretación desconocido: '" + provider
                        + "'. Valores admitidos: mock, gemini.");
    }

    /**
     * Carga la plantilla de prompt desde los recursos. Los prompts se versionan como
     * artefactos, según exige el documento maestro: la versión forma parte del nombre
     * del archivo y queda registrada en cada interpretación.
     */
    private static String loadPrompt(String version) {
        String path = "prompts/progress-extraction-" + version + ".txt";
        try {
            return new String(
                    new ClassPathResource(path).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "No se pudo cargar la plantilla de prompt '" + path + "'", e);
        }
    }
}