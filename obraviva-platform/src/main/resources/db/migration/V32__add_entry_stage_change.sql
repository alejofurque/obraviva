-- El cambio de etapa que trajo un avance: el porcentaje y el estado de antes y de después.
-- La etapa sólo guarda cómo está hoy, así que sin esto no hay forma de decir "35% → 50%".
-- Lo escribe la publicación cuando el avance trae stageUpdate; los avances anteriores a esta
-- migración, y los que no tocan la etapa, quedan con las cuatro columnas nulas.
alter table progress_entries
    add column stage_percent_before integer,
    add column stage_percent_after  integer,
    add column stage_status_before  varchar(20),
    add column stage_status_after   varchar(20);
