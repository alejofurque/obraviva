-- V1: tabla raíz del multi-tenancy.
-- Toda tabla de negocio futura referencia organizations(id).
create table organizations (
    id         uuid primary key default gen_random_uuid(),
    name       varchar(200) not null,
    created_at timestamptz  not null default now()
);