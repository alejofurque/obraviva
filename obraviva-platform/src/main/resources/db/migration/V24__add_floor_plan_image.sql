-- V24: imagen del plano.
--
-- En la práctica el plano lo hace el estudio de arquitectura y llega como PDF o imagen
-- exportada, o como foto de un plano impreso. La planta guarda esa imagen y los
-- ambientes se dibujan encima: siguen siendo polígonos en metros, y la imagen se estira
-- sobre el ancho y la profundidad de la planta. Un PDF se convierte a PNG en el servidor
-- al confirmar la subida, así la vista nunca depende de un visor de PDF.
alter table floor_plans
    add column image_storage_key         varchar(300),
    add column image_pending_storage_key varchar(300),
    add column image_width_px            integer check (image_width_px is null or image_width_px > 0),
    add column image_height_px           integer check (image_height_px is null or image_height_px > 0);
