-- "Carga inicial": el trabajo hecho antes de empezar el seguimiento de una obra, cargado de
-- una vez y con fechas aproximadas. Un tercer origen de avance, junto a MANUAL y AUDIO.
alter table progress_entries drop constraint progress_entries_source_check;
alter table progress_entries add constraint progress_entries_source_check
    check (source in ('MANUAL', 'AUDIO', 'INITIAL_LOAD'));
