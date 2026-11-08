-- V23: encargados asignados por obra.
--
-- El documento maestro (sección 5) pide que un encargado acceda sólo a las obras que
-- tiene asignadas. El dueño de la organización sigue viendo todas: la asignación es sólo
-- para los miembros con rol MEMBER.
create table development_members (
    id              uuid primary key,
    organization_id uuid        not null,
    development_id  uuid        not null references developments (id) on delete cascade,
    user_id         uuid        not null references users (id),
    -- Nulo en las asignaciones que creó esta migración: nadie las hizo a mano.
    created_by      uuid        references users (id),
    created_at      timestamptz not null default now(),
    unique (development_id, user_id),
    -- Quien deja la organización deja también sus obras, sin pasos que se puedan olvidar.
    foreign key (organization_id, user_id)
        references organization_members (organization_id, user_id) on delete cascade
);

create index idx_development_members_user on development_members (user_id);

-- Hasta ahora un encargado veía todas las obras de su organización: se conserva ese
-- acceso para no dejar a nadie afuera de golpe. Desde acá, el dueño lo ajusta obra por obra.
insert into development_members (id, organization_id, development_id, user_id, created_by, created_at)
select gen_random_uuid(), d.organization_id, d.id, m.user_id, null, now()
from developments d
join organization_members m on m.organization_id = d.organization_id and m.role = 'MEMBER';
