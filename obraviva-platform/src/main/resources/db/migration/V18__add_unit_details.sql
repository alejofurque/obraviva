-- V18: ficha de la unidad para "Mi propiedad".
--
-- Todo es opcional: una unidad recién creada no tiene ficha, y la vista muestra sólo
-- lo que el dueño cargó. Nada se completa con valores por defecto que parezcan datos.
--
-- ENTREGA ESTIMADA. Se promete por mes, no por día: se guarda el primer día del mes y
-- la restricción impide cargar una precisión que nadie tiene.
--
-- PORTADA. Imagen propia de la unidad, fuera de media_assets: un render mezclado con
-- las fotos de avance de la obra haría pasar lo proyectado por lo construido. La clave
-- pendiente guarda la subida emitida hasta que se confirma que el archivo llegó.

alter table units
    add column area_m2 numeric(8, 2)
        check (area_m2 is null or area_m2 > 0);

alter table units
    add column bedrooms integer
        check (bedrooms is null or bedrooms between 0 and 50);

alter table units
    add column bathrooms integer
        check (bathrooms is null or bathrooms between 0 and 50);

alter table units
    add column features text[] not null default '{}';

alter table units
    add column estimated_delivery date
        check (estimated_delivery is null or extract(day from estimated_delivery) = 1);

alter table units
    add column cover_storage_key varchar(500);

alter table units
    add column cover_pending_storage_key varchar(500);
