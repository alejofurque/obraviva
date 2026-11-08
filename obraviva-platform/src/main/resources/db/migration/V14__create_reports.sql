-- V14: reportes de período generados por el motor de datos y redactados por el
-- modelo de lenguaje.
--
-- El campo source_data es la pieza central: congela los datos del período al
-- momento de generarse el reporte. La narrativa se contrasta contra ese snapshot
-- y no contra el estado actual de la base, que sigue cambiando. Es lo que hace
-- verificable la exigencia del documento maestro de que cada afirmación del texto
-- sea trazable al dato que la origina.
--
-- Se guarda como texto JSON por el mismo criterio que la propuesta de las
-- interpretaciones de audio (ver V13): es un documento de longitud variable que se
-- lee entero o no se lee, nunca se filtra ni se ordena por sus campos.

create table reports (
    id                  uuid primary key,
    organization_id     uuid not null references organizations (id),
    development_id      uuid not null references developments (id),

    -- Período cubierto. El rango es libre: "mensual" es presentación, no
    -- restricción del modelo.
    period_start        date not null,
    period_end          date not null,

    -- Snapshot de los datos del período, compuesto por el motor de datos.
    source_data         text not null,

    -- Texto redactado por el modelo de lenguaje, pendiente de aprobación.
    narrative           text,

    -- Trazabilidad, mismo criterio que ai_extractions.
    prompt_version      varchar(40),
    model_id            varchar(80),
    raw_output          text,

    status              varchar(20) not null
        check (status in ('PENDING', 'READY_FOR_REVIEW', 'APPROVED', 'REJECTED', 'FAILED')),
    reviewed_by         uuid references users (id),
    reviewed_at         timestamptz,
    review_notes        varchar(1000),

    created_by          uuid not null references users (id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),

    constraint chk_reports_period check (period_end >= period_start)
);

create index idx_reports_organization on reports (organization_id);
create index idx_reports_development on reports (development_id);
create index idx_reports_status on reports (status);

-- La cola incorpora el trabajo de generación de reportes. Las restricciones se
-- reemplazan porque una restricción check no se amplía: se elimina y se recrea.
alter table processing_jobs
    drop constraint processing_jobs_job_type_check;

alter table processing_jobs
    add constraint processing_jobs_job_type_check
    check (job_type in ('CAPTURE_3D_RECONSTRUCTION', 'AUDIO_EXTRACTION', 'REPORT_GENERATION'));

alter table processing_jobs
    drop constraint chk_jobs_pipeline_step;

alter table processing_jobs
    add constraint chk_jobs_pipeline_step
    check (pipeline_step in (
        -- Pipeline de reconstrucción 3D
        'PREPROCESS', 'SUBMIT', 'AWAIT_EXTERNAL', 'DOWNLOAD', 'CONVERT',
        -- Pipeline de interpretación de audio
        'TRANSCRIBE', 'INTERPRET',
        -- Pipeline de generación de reportes
        'NARRATE',
        -- Común a los pipelines con revisión humana
        'AWAIT_REVIEW',
        -- Común a todos
        'DONE'));