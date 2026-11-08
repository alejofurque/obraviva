-- V25: comparador proyecto/realidad.
--
-- Documento maestro, pilar 2: "el render o plano junto a la foto real actual, en la misma
-- perspectiva". El dueño sube el render y elige, entre las fotos de avance de la obra, la
-- que muestra ese mismo lugar hoy. Cuando la obra avanza, cambia la foto: la comparación
-- sigue siendo la misma.
--
-- ALCANCE. Sin unidad, la comparación es de la obra (fachada, áreas comunes) y la ven
-- todos sus compradores. Con unidad, sólo los de esa unidad.
--
-- La foto se referencia, no se copia: si se borra, la comparación queda incompleta y deja
-- de mostrarse hasta que se elija otra.
create table project_comparisons (
    id                         uuid primary key,
    organization_id            uuid         not null references organizations (id),
    development_id             uuid         not null references developments (id) on delete cascade,
    unit_id                    uuid         references units (id) on delete cascade,
    title                      varchar(120) not null,
    render_storage_key         varchar(300),
    render_pending_storage_key varchar(300),
    photo_media_id             uuid         references media_assets (id) on delete set null,
    created_by                 uuid         not null references users (id),
    created_at                 timestamptz  not null default now()
);

create index idx_project_comparisons_development on project_comparisons (development_id);
