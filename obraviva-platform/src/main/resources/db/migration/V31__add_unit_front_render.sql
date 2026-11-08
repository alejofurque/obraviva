-- Render del frente de la casa: cómo se proyectó, para compararlo con la foto de hoy del
-- lugar marcado como frente. Es un dato de la casa, como la portada, y no un medio de obra:
-- un render en media_assets se mezclaría con las fotos reales. Igual que la portada, la
-- subida queda pendiente hasta confirmar que el archivo llegó.
alter table units
    add column front_render_storage_key         varchar(500),
    add column front_render_pending_storage_key varchar(500);
