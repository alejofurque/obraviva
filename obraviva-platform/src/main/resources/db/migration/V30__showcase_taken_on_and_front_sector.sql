-- GALERIA EXPLICITA Y FECHA DE LA FOTO. Hasta aca, "foto de la casa sin avance" era
-- galeria: lo que se muestra para vender. Desde que el encargado sube fotos por sector en
-- la sesion de recorrido, sin avance, esa regla las confundiria con la galeria. showcase
-- marca la galeria de forma explicita, y taken_on es el dia de la sesion, que puede no ser
-- el de la subida: el comprador ve cada lugar en el mes en que se saco la foto.
--
-- Se marca como galeria exactamente lo que hoy se muestra como galeria: foto disponible,
-- de una casa y sin avance. Las pendientes de "Publicar avance" quedan afuera, porque se
-- confirman colgadas de su avance.

alter table media_assets add column showcase boolean not null default false;
alter table media_assets add column taken_on date;

update media_assets
   set showcase = true
 where progress_entry_id is null
   and unit_id is not null
   and kind = 'PHOTO'
   and status = 'AVAILABLE';

-- EL FRENTE DE LA CASA. La portada viva, "Cuando empezo" y la comparacion del proyecto
-- se apoyan en el sector que muestra el frente. Se marca, no se adivina por el nombre:
-- renombrarlo no rompe nada. A lo sumo uno por casa.

alter table unit_capture_sectors add column is_front boolean not null default false;

create unique index uq_unit_capture_sectors_front on unit_capture_sectors (unit_id) where is_front;

-- Si una casa tiene mas de un sector que se llama "Frente" sin distinguir mayusculas (no
-- deberia: la carga no lo permite), se marca el primero en el orden de la casa.
update unit_capture_sectors s
   set is_front = true
 where s.id in (
       select distinct on (unit_id) id
         from unit_capture_sectors
        where lower(trim(name)) = 'frente'
        order by unit_id, order_index);
