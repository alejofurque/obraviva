-- V11: sub-estados del pipeline dentro de un trabajo.
-- JobStatus responde "¿está trabajando?"; pipeline_step responde "¿en qué paso?".
-- Sin esta distinción, un trabajo en PROCESSING no dice si le falta enviar el video,
-- esperar al servicio externo o descargar el resultado.
--
-- step_started_at es distinto de updated_at a propósito: la espera del servicio
-- externo se resuelve con muchas consultas, cada una de las cuales toca updated_at.
-- Para medir hace cuánto estamos en un paso hace falta una marca que no se mueva.

alter table processing_jobs
    add column pipeline_step   varchar(40) not null default 'PREPROCESS',
    add column step_started_at timestamptz not null default now();

-- Los trabajos cuyo video ya fue normalizado no deben reprocesarse: se los posiciona
-- en el paso siguiente. Sólo los no terminales; los fallidos quedan como están.
update processing_jobs j
set pipeline_step = 'SUBMIT'
from captures_3d c
where c.id = j.resource_id
  and c.normalized_video_storage_key is not null
  and j.status not in ('COMPLETED', 'FAILED');

-- Los valores por defecto sólo existían para poder rellenar las filas ya presentes.
alter table processing_jobs
    alter column pipeline_step drop default,
    alter column step_started_at drop default;

alter table processing_jobs
    add constraint chk_jobs_pipeline_step
    check (pipeline_step in
        ('PREPROCESS', 'SUBMIT', 'AWAIT_EXTERNAL', 'DOWNLOAD', 'CONVERT', 'DONE'));

-- La consulta de la cola filtra por los tres campos a la vez.
create index idx_jobs_queue on processing_jobs (status, job_type, pipeline_step, created_at);