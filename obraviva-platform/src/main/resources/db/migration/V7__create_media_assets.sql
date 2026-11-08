-- V7: fotos y videos. Los binarios viven en el almacenamiento S3;
-- la base guarda solo metadatos y la clave del objeto.
-- PENDING: URL de subida emitida. AVAILABLE: subida verificada.

create table media_assets (
    id                 uuid primary key,
    organization_id    uuid not null references organizations (id),
    development_id     uuid not null references developments (id),
    progress_entry_id  uuid references progress_entries (id),
    storage_key        varchar(500) not null unique,
    original_filename  varchar(300) not null,
    content_type       varchar(100) not null,
    kind               varchar(20) not null check (kind in ('PHOTO', 'VIDEO')),
    status             varchar(20) not null check (status in ('PENDING', 'AVAILABLE')),
    created_by         uuid not null references users (id),
    created_at         timestamptz not null default now(),
    confirmed_at       timestamptz
);

create index idx_media_organization on media_assets (organization_id);
create index idx_media_development on media_assets (development_id);
create index idx_media_progress_entry on media_assets (progress_entry_id);