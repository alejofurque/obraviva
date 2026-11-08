# Validación técnica inicial del componente 3D de ObraViva

**Informe de prueba de concepto (POC) — Versión 1.1 (corregida y actualizada)**

*Cambios respecto de la versión 1.0: se corrige el estado de KIRI Engine (su API de desarrolladores no está descartada), se documenta la resolución del problema de rendimiento en escritorio (causa: aceleración por hardware desactivada en el navegador) y se actualizan las listas de problemas y riesgos en consecuencia.*

## Objetivo de la prueba
La primera validación técnica de ObraViva tuvo como objetivo comprobar si era realmente
posible capturar un espacio físico con un teléfono celular, generar una reconstrucción
tridimensional basada en Gaussian Splatting, exportarla fuera de la aplicación de captura y
visualizarla de forma independiente en un navegador web.

La pregunta que se buscó responder fue:
> ¿Es técnicamente viable que un usuario capture un ambiente real con un teléfono y que posteriormente esa reconstrucción pueda visualizarse dentro de una plataforma web como ObraViva?

Esta prueba se realizó antes de comenzar el desarrollo del sistema completo, para atacar
primero uno de los mayores riesgos técnicos del proyecto.

## Dispositivo utilizado
La captura se realizó con un:

iPhone 15

El dispositivo no posee sensor LiDAR, por lo que la prueba también permitió validar que la
propuesta no depende de teléfonos con hardware especializado.

Esto es importante para ObraViva, ya que el objetivo es que la captura pueda realizarse con
teléfonos convencionales utilizados por encargados de obra, desarrollistas o personal del
proyecto.

## Primera prueba: KIRI Engine
Inicialmente se evaluó KIRI Engine como posible herramienta para generar reconstrucciones
mediante 3D Gaussian Splatting.

Se descubrieron dos limitaciones de la aplicación móvil:

- algunos modos de escaneo dependen de LiDAR y no estaban disponibles en el iPhone
        15;
- el modo específico de Gaussian Splatting dentro de la app requiere una suscripción Pro.

Por esta razón la app de KIRI no se utilizó para la prueba manual inicial.

Corrección importante respecto de la versión anterior de este informe: la API de
desarrolladores de KIRI Engine es un producto separado de la aplicación móvil, con su
propio esquema de acceso. La API ofrece un endpoint específico de 3DGS por carga de video
(con límites de 1920×1080 de resolución y 3 minutos de duración), consulta de estado, y
descarga del resultado, bajo un modelo de pago por uso (1 crédito = 1 escaneo = 1 USD) que
incluye 10 créditos gratuitos al registrarse.

Dado que el prototipo de la tesis requiere una cantidad reducida de reconstrucciones definitivas
(estimadas entre 3 y 6), los créditos gratuitos cubren la necesidad completa del proyecto.

Conclusión corregida: KIRI Engine no está descartado; por el contrario, su API es el
candidato principal para la integración automatizada del pipeline 3D de ObraViva (flujo: el
usuario sube el video desde la web → el backend lo envía a la API → se consulta el estado →
se descarga el modelo). Lo que quedó descartado es únicamente el uso manual de su
aplicación móvil para las pruebas.

Regla operativa derivada: ningún crédito se consume hasta disponer de la toma definitiva;
el desarrollo diario se realiza contra archivos ya procesados y simulaciones (mocks) del
adaptador de integración.

## Segunda prueba: Polycam
Posteriormente se utilizó Polycam para escanear una habitación.

### Resultado visual
La reconstrucción obtenida presentó una calidad visual muy buena.

Fue posible:

- reconocer claramente el ambiente;
- visualizar muebles y objetos;
- recorrer la reconstrucción;
- percibir correctamente la distribución espacial;
- obtener una representación suficientemente atractiva para una experiencia orientada al
        comprador.

Esta prueba permitió validar que la experiencia visual propuesta por ObraViva es técnicamente
posible utilizando únicamente un teléfono.

### Limitación encontrada
Polycam permitió exportar el modelo en formatos como OBJ, GLTF, FBX, DAE, STL y USDZ.

Estos formatos corresponden principalmente a modelos tridimensionales tradicionales basados
en mallas. Para la prueba de integración con un visor de Gaussian Splats se necesitaba
exportar la representación específica del splat, por lo que Polycam no resultó conveniente para
esta etapa bajo la modalidad utilizada.

Sin embargo, Polycam quedó identificado como una referencia importante de calidad visual
para futuras comparaciones.

## Tercera prueba: Scaniverse
Se utilizó Scaniverse debido a que permite trabajar directamente con dos modos diferentes:
Mesh y Splat.

Inicialmente se realizó accidentalmente una captura mediante Mesh. Al exportar el archivo
.PLY y abrirlo en un visor de Gaussian Splats, únicamente se visualizaron puntos.

Esto permitió identificar una distinción técnica importante:

### Mesh
Representa principalmente geometría tradicional y puede exportarse en formatos como FBX,
OBJ, GLB, USDZ, STL, LAS y PLY. Es útil para geometría, superficies, mediciones y flujos
CAD.

### Splat
Está orientado principalmente a reconstrucción visual fotorrealista mediante Gaussian Splatting.

Para ObraViva, el modo Splat resulta más adecuado porque el objetivo principal no es realizar
mediciones técnicas de la obra, sino proporcionar al comprador una experiencia visual
inmersiva y realista.

## Captura correcta mediante Splat
Se realizó posteriormente una nueva captura seleccionando específicamente el modo Splat.

Scaniverse no requiere seleccionar opciones como "Large Object" o "Area" en este modo.

La captura se realizó recorriendo el ambiente desde diferentes posiciones y ángulos, en lugar
de girar únicamente alrededor de un objeto.

El resultado fue una reconstrucción satisfactoria de la habitación.

## Exportación
La reconstrucción fue exportada inicialmente en formato:

PLY — tamaño: aproximadamente 97 MB

Posteriormente se exportó también en formato:

SPZ — tamaño: aproximadamente 9,6 MB

Esto representa una reducción aproximada del 90% en el tamaño de almacenamiento. El
archivo SPZ mantuvo una calidad visual similar a la versión PLY.

Esta reducción es especialmente importante para ObraViva porque las reconstrucciones
deberán posteriormente:

- almacenarse en la nube;
- descargarse desde internet;
- servirse mediante CDN;
- utilizarse desde teléfonos;
- coexistir con múltiples capturas históricas de una misma obra.

Por esta razón, SPZ queda definido como el formato de almacenamiento y distribución del
producto, conservando el PLY original como respaldo.

## Visualización independiente
Los archivos generados fueron abiertos en un visor web independiente compatible con
Gaussian Splatting.

La reconstrucción pudo visualizarse correctamente, validando el flujo tecnológico fundamental:

```text
Teléfono
↓
Captura de un espacio real
↓
Gaussian Splatting
↓
Exportación SPZ / PLY
↓
Visor Web
↓
Navegación tridimensional
```

Este resultado es importante porque demuestra que la reconstrucción no necesita permanecer
dentro de Scaniverse. El archivo puede extraerse y posteriormente integrarse dentro de un
visor propio desarrollado para ObraViva.

## Rendimiento observado
iPhone 15
La reconstrucción se visualizó de forma fluida. La navegación fue satisfactoria y el modelo pudo
recorrerse sin problemas significativos.

Este resultado es especialmente positivo debido a que una de las experiencias centrales de
ObraViva está diseñada para utilizarse desde teléfonos.

### Computadora — problema detectado y RESUELTO
En la computadora utilizada durante la prueba, tanto el archivo PLY como el archivo SPZ
presentaron inicialmente una navegación con bajo rendimiento: al mover la cámara, la escena
se veía trabada y con pocos cuadros por segundo. Se observó además que la reducción de
tamaño de archivo (97 MB → 9,6 MB) no producía mejora en los FPS.

Diagnóstico y resolución: se investigó la causa antes de asumir que se trataba de una
limitación de la tecnología. Se verificó la configuración del navegador y se detectó que la
aceleración por hardware estaba desactivada, por lo que el renderizado se estaba
ejecutando por software (CPU) en lugar de utilizar la GPU.

Tras activar la aceleración por hardware, tanto el archivo PLY como el SPZ se navegan
con total fluidez en la computadora, incluso el PLY sin comprimir.

### Aprendizajes documentados
1. Compresión de archivo y rendimiento gráfico son problemas diferentes. SPZ
        reduce almacenamiento, transferencia y tiempo de descarga; la carga de renderizado
        depende de la cantidad de splats y de la GPU disponible. Esta distinción se confirmó
       empíricamente y guía el diseño del pipeline (SPZ para distribución) y del visor (calidad
       configurable).
2. Ante un resultado de rendimiento anómalo, diagnosticar el entorno antes de
       rediseñar el sistema. El comportamiento observado (móvil fluido, escritorio trabado)
       era inverso al esperable, lo que motivó la revisión de configuración que reveló la causa
       real. De haberse asumido el problema como propio de la tecnología, se habría diseñado
       innecesariamente una arquitectura de optimización compleja.
3. El rendimiento del visor en escritorio queda descartado como riesgo del proyecto.
       Permanece pendiente, como verificación futura de menor prioridad, la prueba en
       equipos de gama baja con gráfica integrada — escenario realista de parte de los
       usuarios finales — lo que refuerza la decisión de ofrecer calidad configurable en el visor
       propio. Adicionalmente, el renderer seleccionado para el visor (Spark) incorpora en su
       versión 2 un sistema de nivel de detalle (LOD) con streaming, disponible como recurso
       si alguna escena futura lo requiriera.

## Orientación de la escena
Durante la visualización del archivo SPZ, la reconstrucción apareció invertida.

Esto se considera un problema menor y esperable: los distintos formatos y motores gráficos
pueden utilizar diferentes sistemas de coordenadas y orientaciones.

ObraViva deberá almacenar para cada captura una transformación espacial (posición,
rotación, escala). De esta manera, una reconstrucción que llegue invertida puede corregirse
antes de mostrarse al comprador.

Este hallazgo confirma que el sistema de transformaciones por captura es un requisito de la
arquitectura desde el primer día — no solo una conveniencia para el viaje temporal — y será
también la base para organizar y alinear las capturas históricas de una misma obra contra un
marco de coordenadas común del desarrollo.

## Viaje temporal
Inicialmente se consideró que sería necesario realizar una prueba adicional compleja para
validar el "viaje en el tiempo". Posteriormente se concluyó que esta funcionalidad no presenta
un riesgo tecnológico importante.

Cada estado de la obra puede almacenarse como una reconstrucción independiente:

Captura 1 → estado-01.spz

Captura 2 → estado-02.spz

Captura 3 → estado-03.spz

Cada captura se asocia a: fecha, etapa, unidad, descripción, transformación espacial y archivo
SPZ.

Cuando el comprador selecciona una fecha diferente, el visor descarga o activa la
reconstrucción correspondiente, manteniendo la posición de cámara (el usuario permanece
quieto y la escena cambia a su alrededor).

No es necesario implementar morphing entre reconstrucciones ni alineación tridimensional
automática para que la propuesta funcione. La alineación aproximada se realiza manualmente
desde un panel de administración, ajustando la transformación de cada captura contra el marco
de coordenadas común del desarrollo (no contra la captura anterior, para evitar acumulación de
errores), y se facilita con disciplina de captura: iniciar siempre desde un punto de referencia
físico marcado en la obra, en el mismo sentido de recorrido.

Como mejora visual opcional, el renderer seleccionado admite múltiples escenas splat
simultáneas con opacidad ajustable, lo que habilita un fundido cruzado entre estados como
transición.

Por lo tanto, el viaje temporal pasa a considerarse una funcionalidad de software convencional
y no uno de los principales riesgos técnicos.

## Uso de un ambiente doméstico en lugar de una obra real
Actualmente no se dispone de acceso continuo al interior de una obra real para realizar las
capturas del prototipo. Esto no impide validar técnicamente el sistema: para la prueba inicial se
utilizó una habitación doméstica, y la misma estrategia puede utilizarse durante el desarrollo
para validar el flujo completo en un entorno controlado.

Estrategia de capturas para el prototipo (dos vías complementarias):

1. Entorno controlado (interior doméstico): para validar el flujo técnico completo —
       captura, procesamiento, transformaciones, visor, alternancia de estados — con acceso
       ilimitado y repetible.
2. Obra real desde la vía pública: para el material de demostración con progreso
       constructivo genuino. Se seleccionará una construcción visible desde la vereda, se
       marcará un punto de filmación fijo y se realizarán capturas periódicas (semanales) del
       exterior. Esta vía no requiere permisos ni acceso al interior, y debe iniciarse lo antes
       posible dado que el progreso de obra es tiempo calendario no recuperable.

En la documentación académica se aclarará la naturaleza de cada captura. La arquitectura del
sistema es independiente del contenido de la escena, por lo que el mecanismo funciona sin
modificaciones con cualquier origen de captura.

## Conclusiones de la prueba
La prueba permitió validar satisfactoriamente los principales supuestos tecnológicos
relacionados con el componente tridimensional de ObraViva.

### Validado
- Captura desde un iPhone 15 sin LiDAR.
- Reconstrucción mediante Gaussian Splatting.
- Captura de un ambiente completo, no únicamente de objetos.
- Buena calidad visual.
- Exportación independiente de la plataforma de captura.
- Formato PLY compatible.
- Formato SPZ compatible.
- Compresión aproximada de 97 MB a 9,6 MB sin pérdida visual perceptible.
- Visualización de la reconstrucción desde un navegador.
- Navegación tridimensional.
- Funcionamiento fluido en iPhone 15.
- Funcionamiento fluido en computadora de escritorio (con aceleración por
        hardware activa), tanto en SPZ como en PLY.
- Posibilidad de integrar posteriormente el modelo dentro de una aplicación web propia.
- Existencia de una vía de procesamiento automatizado por API (KIRI Engine) con
        créditos gratuitos suficientes para el alcance de la tesis.

### Problemas detectados (estado actualizado)
- Bajo rendimiento en la computadora utilizada para la prueba → Resuelto: causado por
        la aceleración por hardware desactivada en el navegador; documentado como
        aprendizaje de diagnóstico.
- Diferencias de orientación entre formatos o visores → se resuelve con la transformación
        espacial por captura (requisito de arquitectura confirmado).
- Pendiente de menor prioridad: verificación en dispositivos de gama baja con gráfica
        integrada → argumenta la calidad configurable en el visor propio.

### Riesgos descartados o reducidos
Ya no se considera necesario:

- desarrollar un algoritmo propio de Gaussian Splatting;
- disponer obligatoriamente de LiDAR;
- construir un motor gráfico desde cero;
- disponer inmediatamente del interior de una obra real;
- realizar alineación 3D automática entre todas las capturas;
- almacenar archivos PLY originales para distribución al usuario final (quedan solo como
        respaldo);
- diseñar una arquitectura de optimización de rendimiento compleja para escritorio
        (el rendimiento quedó validado; las estrategias de optimización pasan a ser mejoras
        opcionales, con el LOD del renderer disponible de fábrica si se necesitara);
- resolver el procesamiento automatizado por medios propios (existe API externa
        viable y gratuita para el alcance del prototipo).

## Estado de la POC
POC 3D inicial: APROBADA.

La prueba demostró que el concepto tecnológico central de ObraViva es viable, con costo cero
para el alcance de la tesis.

El próximo paso no consiste en realizar nuevas capturas de validación, sino en desarrollar un
visor web propio mínimo utilizando:

- Vite
- TypeScript
- Three.js
- Spark

y cargar dentro de él el archivo SPZ generado durante esta prueba.

La siguiente validación deberá comprobar que ObraViva puede controlar directamente:

- carga de la reconstrucción;
- cámara;
- orientación (aplicación de la transformación almacenada);
- navegación (escritorio y táctil);
- interfaz;
- calidad;
- rendimiento;
- selección y alternancia de capturas históricas manteniendo la posición de cámara.

En ese momento se dejará de validar únicamente la tecnología de terceros y comenzará la
validación del componente tridimensional desarrollado específicamente para ObraViva.

En paralelo al desarrollo del visor, se inicia la captura periódica de una obra real desde la vía
pública, dado que el progreso constructivo es el único insumo del proyecto que no puede
acelerarse ni recuperarse.
