-- V16: vínculo entre un comprador y su unidad.
--
-- Es el segundo camino de acceso del sistema. Hasta acá, todo permiso venía de
-- pertenecer a la organización dueña de la obra; un comprador NO pertenece a esa
-- organización y llega a su unidad por este vínculo. Las dos rutas conviven: un
-- mismo usuario puede ser del equipo en un desarrollo y comprador en otro.
--
-- Sin columna de rol: estar acá significa una sola cosa, que la unidad es suya. La
-- familia que mira el recorrido lo hace con un enlace público, no con un vínculo.
--
-- organization_id se desnormaliza como en toda tabla de negocio, aunque sea
-- derivable desde la unidad.

create table unit_members (
    id                  uuid primary key,
    organization_id     uuid not null references organizations (id),
    unit_id             uuid not null references units (id),
    user_id             uuid not null references users (id),

    created_by          uuid not null references users (id),
    created_at          timestamptz not null default now(),

    -- Un usuario se vincula una sola vez a la misma unidad. Sin esto, un alta
    -- repetida generaría duplicados y el conteo de miembros mentiría.
    constraint uq_unit_members_unit_user unique (unit_id, user_id)
);

-- "Mis unidades" entra por acá: es la consulta del comprador en cada sesión.
create index idx_unit_members_user on unit_members (user_id);
create index idx_unit_members_unit on unit_members (unit_id);
create index idx_unit_members_organization on unit_members (organization_id);