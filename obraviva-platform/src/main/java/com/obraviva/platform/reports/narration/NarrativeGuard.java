package com.obraviva.platform.reports.narration;

import com.obraviva.platform.reports.ReportData;
import com.obraviva.platform.reports.ReportDataCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifica que el texto redactado no contenga cifras ajenas al snapshot.
 *
 * <p>Es la implementación programática del principio rector: la IA redacta, el motor
 * de datos calcula. El prompt ya prohíbe inventar números, pero un prompt es una
 * promesa y esto es una comprobación. Con esta salvaguarda, la garantía de que
 * ninguna cifra proviene de un modelo de lenguaje deja de depender de que el modelo
 * obedezca.
 *
 * <p><strong>La comparación es deliberadamente permisiva:</strong> cada secuencia de
 * dígitos del texto se busca dentro del snapshot serializado completo, de modo que un
 * "1" pasa por aparecer dentro de alguna fecha. La red está para atrapar invenciones
 * evidentes —un porcentaje de avance estimado, un plazo calculado, una cantidad de
 * unidades que nadie declaró— y no para hacer verificación semántica. Un texto con
 * cifras correctas nunca debería ser rechazado; ése es el error que importa evitar,
 * porque la última línea de control sigue siendo el revisor humano.
 */
@Component
public class NarrativeGuard {

    private static final Logger log = LoggerFactory.getLogger(NarrativeGuard.class);

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final ReportDataCodec codec;

    public NarrativeGuard(ReportDataCodec codec) {
        this.codec = codec;
    }

    /**
     * @throws NarrationException si el texto contiene un número que no está en los datos
     */
    public void verify(String narrative, ReportData data) {
        if (narrative == null || narrative.isBlank()) {
            throw new NarrationException("El texto redactado está vacío");
        }

        String snapshot = codec.encode(data);
        Set<String> foreign = new LinkedHashSet<>();

        Matcher matcher = DIGITS.matcher(narrative);
        while (matcher.find()) {
            String number = matcher.group();
            if (!snapshot.contains(number)) {
                foreign.add(number);
            }
        }

        if (foreign.isEmpty()) {
            return;
        }

        log.warn("La redacción contiene cifras ajenas al snapshot: {}", foreign);
        throw new NarrationException(
                "El texto contiene cifras que no están en los datos del período: " + foreign,
                narrative,
                null);
    }
}