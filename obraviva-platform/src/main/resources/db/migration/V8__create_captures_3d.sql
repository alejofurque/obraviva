-- V8: capturas 3D (Gaussian Splatting).
-- Cada captura almacena su transformación espacial contra el marco de
-- coordenadas común del desarrollo: requisito de arquitectura confirmado
-- por la prueba de concepto, base del viaje en el tiempo.

create table captures_3d (
    id                  uuid primary key,
    organization_id     uuid not null references organizations (id),
    development_id      uuid not null references developments (id),
    unit_id             uuid references units (id),
    stage_id            uuid references stages (id),
    capture_date        date not null,
    description         varchar(500),
    storage_key         varchar(500) not null unique,
    backup_storage_key  varchar(500),
    status              varchar(20) not null check (status in ('PENDING', 'AVAILABLE')),
    transform_position_x double precision not null default 0,
    transform_position_y double precision not null default 0,
    transform_position_z double precision not null default 0,
    transform_rotation_x double precision not null default 0,
    transform_rotation_y double precision not null default 0,
    transform_rotation_z double precision not null default 0,
    transform_scale      double precision not null default 1,
    created_by          uuid not null references users (id),
    created_at          timestamptz not null default now(),
    confirmed_at        timestamptz
);

create index idx_captures_organization on captures_3d (organization_id);
create index idx_captures_development on captures_3d (development_id);
create index idx_captures_date on captures_3d (development_id, capture_date);