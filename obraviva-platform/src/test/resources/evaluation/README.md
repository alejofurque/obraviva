# Conjunto de evaluación de la extracción

Cada caso declara una transcripción de entrada y la interpretación esperada,
anotada a mano. El motor de evaluación (`ExtractionEvaluator`) compara la
propuesta del modelo contra lo anotado y calcula precisión y exhaustividad.

## Qué se mide y qué no

**Se mide con rigor: las propuestas de cambio de estado por etapa.** Son un
conjunto finito y verificable: la etapa existe o no, el estado es uno de cuatro
valores, la comparación es exacta.

**Se cuentan, sin comparar literalmente: novedades y alertas de insumos.** Son
texto libre. Comparar "Se terminó el revoque grueso" contra "Terminaron los
revoques gruesos del segundo piso" carácter por carácter daría cero y no
significaría nada. Se verifica que la cantidad alcance un mínimo y que aparezcan
ciertas palabras clave; el contenido queda registrado para inspección manual.

Esto es deliberado: el conjunto mide bien lo que se puede medir bien, en lugar de
fingir precisión sobre texto libre.

**No se evalúa la transcripción.** Los casos entran como texto, no como audio,
para medir una cosa por vez y poder correr la evaluación sin el modelo de whisper.
La calidad de la transcripción se documenta por separado.

## Asimetría entre alucinación y omisión

Una alucinación es peor que una omisión. Si el modelo inventa que una etapa está
completa, el revisor podría aprobarlo por distracción; si omite algo, el revisor
lo agrega. Por eso `forbiddenStages` es una verificación aparte y su
incumplimiento se reporta como falla grave, no como una precisión más baja.

## Cómo agregar un caso

1. Grabar el audio en condiciones realistas (ruido de obra, teléfono, WhatsApp).
2. Transcribirlo con el pipeline real y **copiar la transcripción tal cual sale**,
   con sus errores. Corregirla a mano invalidaría la evaluación.
3. Anotar la interpretación esperada, incluyendo el fundamento de cada propuesta
   en `rationale`. Ese texto es para quien lea el conjunto, no para el modelo.
4. Agregar el caso a `extraction-cases.json`.

El archivo se versiona con el proyecto: es un artefacto de la tesis, no material
descartable.