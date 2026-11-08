-- V22: estado comercial de la unidad.
--
-- El desarrollista publica unidades en venta y las asigna a compradores (documento
-- maestro, sección 5). El estado es interno de la organización: no aparece en la vista
-- del comprador ni en el enlace público. No es un CRM: no hay precios, cuotas ni
-- interesados, que quedan fuera de alcance (13.2).
alter table units
    add column sales_status varchar(20) not null default 'AVAILABLE'
        check (sales_status in ('AVAILABLE', 'RESERVED', 'SOLD'));

-- Una unidad que ya tiene comprador vinculado está vendida.
update units u
set sales_status = 'SOLD'
where exists (select 1 from unit_members m where m.unit_id = u.id);
