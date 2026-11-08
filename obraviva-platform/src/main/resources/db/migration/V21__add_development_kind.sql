-- V21: tipo de desarrollo.
--
-- Un desarrollo es una construcción con su propio avance, gasto e historia. Hay dos
-- tipos:
--
-- SINGLE: obra única, una casa o un galpón. Tiene exactamente una unidad, que se crea
-- con la obra. La interfaz no habla de unidades: la obra es lo que se entrega.
--
-- MULTI_UNIT: edificio. Varias unidades comparten la obra; cada una tiene sus
-- compradores, ficha, planos y recorridos, y ve además los recorridos de áreas comunes.
--
-- Un barrio de casas no es un desarrollo con unidades: son varias obras únicas, así cada
-- casa conserva su avance y su gasto separados.
--
-- Los desarrollos existentes con más de una unidad quedan como MULTI_UNIT; los que tienen
-- una o ninguna pasan a obra única, y a los que no tienen unidad se les crea la suya.

alter table developments
    add column kind varchar(20) not null default 'MULTI_UNIT'
        check (kind in ('SINGLE', 'MULTI_UNIT'));

update developments d
set kind = 'SINGLE'
where (select count(*) from units u where u.development_id = d.id) <= 1;

insert into units (id, organization_id, development_id, code, description, created_at)
select gen_random_uuid(), d.organization_id, d.id, 'Principal', null, now()
from developments d
where d.kind = 'SINGLE'
  and not exists (select 1 from units u where u.development_id = d.id);
