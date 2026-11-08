-- V4: obras (developments) y unidades vendibles.
-- organization_id en toda tabla de negocio: aislamiento estructural
-- entre organizaciones desde el día uno.

create table developments (
    id              uuid primary key,
    organization_id uuid not null references organizations (id),
    name            varchar(200) not null,
    address         varchar(300),
    created_at      timestamptz not null default now()
);

create table units (
    id              uuid primary key,
    organization_id uuid not null references organizations (id),
    development_id  uuid not null references developments (id),
    code            varchar(50)  not null,
    description     varchar(300),
    created_at      timestamptz not null default now(),
    unique (development_id, code)
);

create index idx_developments_organization on developments (organization_id);
create index idx_units_organization on units (organization_id);
create index idx_units_development on units (development_id);