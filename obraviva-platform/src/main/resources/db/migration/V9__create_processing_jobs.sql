-- V9: trabajos asincrónicos. Infraestructura común para todos los
-- procesos pesados (reconstrucción 3D, y en el futuro interpretación
-- de audio y generación de reportes).
-- Máquina de estados: UPLOADED → PENDING → PROCESSING → COMPLETED/FAILED.
-- NEEDS_REVIEW se usará en los trabajos de IA, que requieren confirmación humana.

alter table captures_3d
    add column video_storage_key varchar(500);

create table processing_jobs (
    id                  uuid primary key,
    organization_id     uuid not null references organizations (id),
    job_type            varchar(40) not null
        check (job_type in ('CAPTURE_3D_RECONSTRUCTION')),
    status              varchar(20) not null
        check (status in ('UPLOADED', 'PENDING', 'PROCESSING', 'NEEDS_REVIEW', 'COMPLETED', 'FAILED')),
    resource_id         uuid not null,
    external_reference  varchar(200),
    attempts            integer not null default 0,
    last_error          varchar(1000),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create index idx_jobs_status on processing_jobs (status);
create index idx_jobs_resource on processing_jobs (resource_id);
create index idx_jobs_organization on processing_jobs (organization_id);