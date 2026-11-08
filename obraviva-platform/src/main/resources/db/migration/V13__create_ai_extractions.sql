-- V13: interpretaciones de audio pendientes de confirmación humana.
--
-- Nada de lo que produce el modelo de lenguaje se publica: queda acá hasta que
-- una persona lo revisa y aprueba. Es la implementación del principio rector del
-- documento maestro: la IA extrae, interpreta y redacta; el humano confirma.
--
-- La propuesta se guarda como texto JSON y no en columnas. A diferencia de la
-- transformación espacial de las capturas —que se consulta y aplica en cada
-- renderizado— la propuesta es un borrador de longitud variable que se lee entero
-- o no se lee: nunca se filtra ni se ordena por sus campos. Modelarla en columnas
-- exigiría tres tablas hijas para algo efímero.

create table ai_extractions (
    id                  uuid primary key,
    organization_id     uuid not null references organizations (id),
    development_id      uuid not null references developments (id),

    -- Audio de origen, subido por URL firmada igual que el resto de los archivos.
    audio_storage_key   varchar(500) not null,

    -- Transcripción literal. Se conserva y se muestra junto a la propuesta para
    -- que el revisor pueda contrastar lo que dijo el encargado con lo que el
    -- modelo interpretó.
    transcript          text,

    -- Trazabilidad: ante una propuesta discutible hay que poder reconstruir con
    -- qué versión de prompt y de modelo se generó, y qué devolvió exactamente.
    prompt_version      varchar(40),
    model_id            varchar(80),
    raw_output          text,

    -- Propuesta estructurada ya validada contra el dominio.
    proposal            text,

    review_status       varchar(20) not null
        check (review_status in ('PENDING', 'READY_FOR_REVIEW', 'APPROVED', 'REJECTED', 'FAILED')),
    reviewed_by         uuid references users (id),
    reviewed_at         timestamptz,
    review_notes        varchar(1000),

    created_by          uuid not null references users (id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create index idx_ai_extractions_organization on ai_extractions (organization_id);
create index idx_ai_extractions_development on ai_extractions (development_id);
create index idx_ai_extractions_review_status on ai_extractions (review_status);

-- La cola incorpora el trabajo de interpretación de audio. Las restricciones se
-- reemplazan porque una restricción check no se amplía: se elimina y se recrea.
alter table processing_jobs
    drop constraint processing_jobs_job_type_check;

alter table processing_jobs
    add constraint processing_jobs_job_type_check
    check (job_type in ('CAPTURE_3D_RECONSTRUCTION', 'AUDIO_EXTRACTION'));

alter table processing_jobs
    drop constraint chk_jobs_pipeline_step;

alter table processing_jobs
    add constraint chk_jobs_pipeline_step
    check (pipeline_step in (
        -- Pipeline de reconstrucción 3D
        'PREPROCESS', 'SUBMIT', 'AWAIT_EXTERNAL', 'DOWNLOAD', 'CONVERT',
        -- Pipeline de interpretación de audio
        'TRANSCRIBE', 'INTERPRET', 'AWAIT_REVIEW',
        -- Común a ambos
        'DONE'));