-- V26: documentos autorizados de la unidad.
--
-- Documento maestro, sección 4.3: "Mi propiedad" incluye los documentos autorizados. Son
-- privados: los ve el comprador de la unidad y los administra el dueño. Nunca aparecen en
-- el enlace público ni los ve el encargado.
--
-- ALCANCE. Con unidad, son de esa unidad (boleto, plano aprobado). Sin unidad, son de la
-- obra y los ven todos sus compradores (reglamento de copropiedad, memoria descriptiva).
create table unit_documents (
    id                uuid primary key,
    organization_id   uuid         not null references organizations (id),
    development_id    uuid         not null references developments (id) on delete cascade,
    unit_id           uuid         references units (id) on delete cascade,
    title             varchar(120) not null,
    category          varchar(30)  not null
        check (category in ('PLANO', 'CONTRATO', 'REGLAMENTO', 'MEMORIA', 'OTRO')),
    original_filename varchar(300) not null,
    content_type      varchar(100) not null,
    storage_key       varchar(300) not null,
    status            varchar(20)  not null check (status in ('PENDING', 'AVAILABLE')),
    created_by        uuid         not null references users (id),
    created_at        timestamptz  not null default now()
);

create index idx_unit_documents_development on unit_documents (development_id);
