-- V6: entradas de avance publicadas (línea de tiempo).
-- Contenido inmutable con versiones: una corrección NO actualiza la
-- fila; inserta una versión nueva y marca la anterior como superada.
-- La línea de tiempo muestra solo las vigentes (superseded_at is null).

create table progress_entries (
    id              uuid primary key,
    organization_id uuid not null references organizations (id),
    development_id  uuid not null references developments (id),
    stage_id        uuid references stages (id),
    entry_date      date not null,
    description     varchar(2000) not null,
    source          varchar(20) not null
        check (source in ('MANUAL', 'AUDIO')),
    version         integer not null,
    root_id         uuid not null,
    superseded_at   timestamptz,
    created_by      uuid not null references users (id),
    created_at      timestamptz not null default now()
);

create index idx_progress_organization on progress_entries (organization_id);
create index idx_progress_development on progress_entries (development_id);
create index idx_progress_root on progress_entries (root_id);