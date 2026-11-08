-- Versiones livianas de cada foto y de un fotograma de cada video: miniatura (480 px del
-- lado mayor) y pantalla (1600 px), en JPEG. El original se conserva. Las genera un worker
-- fuera de la publicación, con el estado en la propia foto: una que no las tiene se sigue
-- viendo con el original.
alter table media_assets
    add column thumb_storage_key  varchar(500),
    add column screen_storage_key varchar(500),
    add column variants_status    varchar(12),
    add column variants_attempts  integer not null default 0;

-- Lo que ya está publicado entra a la cola; los SVG son vectoriales y livianos: no se achican.
update media_assets
set variants_status = case when content_type = 'image/svg+xml' then 'SKIPPED' else 'PENDING' end
where status = 'AVAILABLE';

-- La cola del worker: sólo lo pendiente, de lo más viejo a lo más nuevo.
create index idx_media_assets_variants_pending on media_assets (created_at) where variants_status = 'PENDING';
