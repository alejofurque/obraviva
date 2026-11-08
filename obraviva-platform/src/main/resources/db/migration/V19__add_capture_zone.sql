-- V19: ambiente de cada captura 3D.
--
-- Una obra se recorre por partes: la cocina, un dormitorio, la fachada. El recorrido
-- ofrece elegir el ambiente y viajar en el tiempo dentro de él, y para eso cada captura
-- tiene que saber qué registra. Es texto libre y opcional: los nombres de ambientes
-- cambian de una obra a otra, y una captura sin ambiente sigue siendo navegable.

alter table captures_3d
    add column zone varchar(80);
