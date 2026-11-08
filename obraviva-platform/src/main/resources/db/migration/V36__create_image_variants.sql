-- Versiones livianas de las imágenes que no son medios de obra: la portada y el render del
-- frente de una casa, el render de una comparación y la portada del desarrollo. Viven en las
-- tablas de sus módulos, fuera de media_assets, así que el estado de sus versiones va en una
-- tabla propia del módulo media, identificada por la clave del original: cada subida usa una
-- clave nueva, así que la clave es la imagen. Mismos estados e intentos que en media_assets.
create table image_variants (
    storage_key        varchar(500) primary key,
    organization_id    uuid         not null references organizations (id),
    thumb_storage_key  varchar(500),
    screen_storage_key varchar(500),
    status             varchar(12)  not null,
    attempts           integer      not null default 0,
    created_at         timestamptz  not null default now()
);

create index idx_image_variants_organization on image_variants (organization_id);
-- La cola del worker: sólo lo pendiente, de lo más viejo a lo más nuevo.
create index idx_image_variants_pending on image_variants (created_at) where status = 'PENDING';

-- Lo que ya está cargado entra a la cola; los SVG son vectoriales y livianos: no se achican.
insert into image_variants (storage_key, organization_id, status)
select key, organization_id, case when lower(key) like '%.svg' then 'SKIPPED' else 'PENDING' end
from (
    select cover_storage_key as key, organization_id from units where cover_storage_key is not null
    union all
    select front_render_storage_key, organization_id from units where front_render_storage_key is not null
    union all
    select render_storage_key, organization_id from project_comparisons where render_storage_key is not null
    union all
    select cover_storage_key, organization_id from developments where cover_storage_key is not null
) as existing
on conflict (storage_key) do nothing;
