-- SECTOR DE UNA FOTO O VIDEO. El mismo lugar de la casa que usan los recorridos 3D
-- (unit_capture_sectors), referenciado igual que captures_3d.zone: por nombre, sin clave
-- foranea. Con eso el comprador ve cada lugar de su casa mes a mes, con sus fotos, sus
-- videos y su recorrido.
--
-- Opcional: las fotos existentes quedan sin sector y se muestran en "General". Una foto
-- de obras comunes no lleva sector, porque los sectores son de una casa. Que el nombre
-- sea uno de los de la casa lo valida la aplicacion al escribirlo, igual que con las
-- capturas, y un renombre del sector se propaga a las dos tablas en la misma transaccion.

alter table media_assets add column sector varchar(80);

create index idx_media_unit_sector on media_assets (unit_id, sector);
create index idx_captures_unit_zone on captures_3d (unit_id, zone);
