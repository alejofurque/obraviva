-- V10: clave del video normalizado por el preprocesamiento con ffmpeg.
-- El video original queda intacto como respaldo, mismo criterio que el PLY
-- frente al SPZ. La clave se asigna al tomar el trabajo, antes de que el
-- archivo exista, igual que storage_key se asigna al crear la captura.

alter table captures_3d
    add column normalized_video_storage_key varchar(500);