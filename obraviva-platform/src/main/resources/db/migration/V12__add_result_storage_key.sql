-- V12: clave del resultado descargado del servicio externo de reconstrucción.
-- El servicio entrega un archivo comprimido, no un PLY suelto, y lo conserva
-- sólo 3 días. Guardarlo en el almacenamiento propio apenas está disponible es
-- un requisito, no una optimización: pasado ese plazo el crédito quedó gastado
-- y el resultado no existe más.
--
-- La descompresión y la extracción del PLY son responsabilidad de la etapa
-- siguiente del pipeline (tarea 2.6d).

alter table captures_3d
    add column result_storage_key varchar(500);