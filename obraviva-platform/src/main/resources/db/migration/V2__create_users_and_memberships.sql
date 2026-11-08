-- V2: identidad. Usuarios y membresía de organizaciones.
-- Los compradores NO son miembros de organización: se vincularán
-- por unidad (unit_members) en una migración futura.

create table users (
    id            uuid primary key,
    email         varchar(320) not null unique,
    password_hash varchar(100) not null,
    full_name     varchar(200) not null,
    created_at    timestamptz  not null default now()
);

create table organization_members (
    id              uuid primary key,
    organization_id uuid not null references organizations (id),
    user_id         uuid not null references users (id),
    role            varchar(30) not null check (role in ('OWNER', 'MEMBER')),
    created_at      timestamptz not null default now(),
    unique (organization_id, user_id)
);

create index idx_org_members_organization on organization_members (organization_id);
create index idx_org_members_user on organization_members (user_id);