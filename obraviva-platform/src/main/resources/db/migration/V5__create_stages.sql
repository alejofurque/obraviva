-- V5: etapas de obra por desarrollo, con orden y estado.
-- DELAYED es un estado de primera clase: la transparencia sobre
-- demoras es decisión de producto, no una excepción.

create table stages (
    id              uuid primary key,
    organization_id uuid not null references organizations (id),
    development_id  uuid not null references developments (id),
    name            varchar(200) not null,
    order_index     integer      not null,
    status          varchar(20)  not null
        check (status in ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'DELAYED')),
    created_at      timestamptz  not null default now(),
    unique (development_id, order_index)
);

create index idx_stages_organization on stages (organization_id);
create index idx_stages_development on stages (development_id);