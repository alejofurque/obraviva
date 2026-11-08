-- V28: encargados por casa y sectores del recorrido 3D.
--
-- Un encargado se asigna a un desarrollo entero (development_members, todas sus casas) o
-- a casas puntuales (unit_supervisors). Puede ser la misma persona en varias casas. Con
-- asignaciones por casa llega al desarrollo, pero carga avances, etapas, audios y
-- recorridos sólo de esas casas.
create table unit_supervisors (
    id              uuid primary key,
    organization_id uuid        not null,
    development_id  uuid        not null references developments (id) on delete cascade,
    unit_id         uuid        not null references units (id) on delete cascade,
    user_id         uuid        not null references users (id),
    created_by      uuid        references users (id),
    created_at      timestamptz not null default now(),
    unique (unit_id, user_id),
    -- Igual que en development_members: quien deja la organización deja sus casas.
    foreign key (organization_id, user_id)
        references organization_members (organization_id, user_id) on delete cascade
);

create index idx_unit_supervisors_user on unit_supervisors (user_id);
create index idx_unit_supervisors_development on unit_supervisors (development_id);

-- Sectores que se graban en 3D en cada casa: el frente, el living, un dormitorio. Son
-- los ambientes del viaje en el tiempo: cada fecha de recorrido debería traer un video de
-- cada sector, y el comprador viaja en el tiempo sector por sector.
create table unit_capture_sectors (
    id              uuid primary key,
    organization_id uuid        not null,
    unit_id         uuid        not null references units (id) on delete cascade,
    name            varchar(80) not null,
    order_index     integer     not null,
    created_at      timestamptz not null default now(),
    unique (unit_id, name)
);

create index idx_unit_capture_sectors_unit on unit_capture_sectors (unit_id, order_index);
