-- V20: planos interactivos de la unidad.
--
-- Cada unidad tiene plantas (planta baja, planta alta, instalaciones) y cada planta
-- tiene ambientes que se pueden tocar para ver su superficie.
--
-- GEOMETRÍA. Las coordenadas están en metros, con origen en la esquina superior
-- izquierda de la planta, y la planta declara su ancho y su profundidad. Así el dibujo
-- queda a escala real y cualquier pantalla lo ajusta a su tamaño. Cada ambiente es un
-- polígono guardado como arreglo plano x1, y1, x2, y2...: sin tipos geométricos ni
-- extensiones, porque la aplicación sólo necesita dibujarlo y calcular su área.
--
-- SUPERFICIE. Se calcula del polígono. El dueño puede declarar la superficie oficial del
-- plano de arquitectura, que incluye muros y puede no coincidir con el dibujo: si está
-- declarada, se muestra ésa.

create table floor_plans (
    id              uuid primary key,
    organization_id uuid not null references organizations (id),
    unit_id         uuid not null references units (id),
    name            varchar(80) not null,
    order_index     integer not null check (order_index >= 0),
    width_m         numeric(7, 2) not null check (width_m > 0),
    depth_m         numeric(7, 2) not null check (depth_m > 0),
    created_at      timestamptz not null default now()
);

create index idx_floor_plans_unit on floor_plans (unit_id);
create index idx_floor_plans_organization on floor_plans (organization_id);

create table plan_rooms (
    id                uuid primary key,
    organization_id   uuid not null references organizations (id),
    floor_plan_id     uuid not null references floor_plans (id) on delete cascade,
    name              varchar(80) not null,
    order_index       integer not null check (order_index >= 0),
    polygon           double precision[] not null
        check (cardinality(polygon) >= 6 and cardinality(polygon) % 2 = 0),
    declared_area_m2  numeric(8, 2)
        check (declared_area_m2 is null or declared_area_m2 > 0),
    created_at        timestamptz not null default now()
);

create index idx_plan_rooms_plan on plan_rooms (floor_plan_id);
create index idx_plan_rooms_organization on plan_rooms (organization_id);
