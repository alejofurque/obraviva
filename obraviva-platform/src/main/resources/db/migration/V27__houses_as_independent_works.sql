-- V27: cada casa es una obra independiente.
--
-- El producto se enfoca en desarrollos de casas: un desarrollo (barrio, complejo o loteo)
-- tiene N casas, y cada casa se construye, se muestra y se vende por separado. Hasta acá
-- las etapas, los avances, las fotos y los gastos eran del desarrollo entero; desde acá
-- son de cada casa.
--
-- OBRAS COMUNES. Lo que no es de una casa (calles, redes, portería, espacios comunes)
-- sigue siendo del desarrollo: es una etapa, un avance o un gasto con unit_id nulo. Así
-- se modela lo que pasa en un barrio real, y los datos anteriores de un edificio quedan
-- como obras comunes sin perder nada.

-- --- Datos del desarrollo ---
alter table developments
    add column description               text,
    add column locality                  varchar(120),
    add column province                  varchar(80),
    add column development_type          varchar(30)
        check (development_type is null or development_type in ('BARRIO_CERRADO', 'BARRIO_ABIERTO', 'COMPLEJO', 'LOTEO')),
    add column land_area_m2              numeric(12, 2) check (land_area_m2 is null or land_area_m2 > 0),
    add column started_on                date,
    add column estimated_delivery        date,
    add column amenities                 text[] not null default '{}',
    add column services                  text[] not null default '{}',
    add column architect                 varchar(160),
    add column builder                   varchar(160),
    add column permit_number             varchar(80),
    add column cover_storage_key         varchar(300),
    add column cover_pending_storage_key varchar(300);

-- --- Ficha de venta de la casa ---
-- area_m2 (V18) queda como superficie total construida. Las demás superficies son las que
-- publica cualquier ficha de casa en un barrio: lote, cubierta y semicubierta.
alter table units
    add column model_name           varchar(80),
    add column lot_area_m2          numeric(10, 2) check (lot_area_m2 is null or lot_area_m2 > 0),
    add column covered_area_m2      numeric(10, 2) check (covered_area_m2 is null or covered_area_m2 > 0),
    add column semi_covered_area_m2 numeric(10, 2) check (semi_covered_area_m2 is null or semi_covered_area_m2 > 0),
    add column rooms                integer check (rooms is null or rooms between 0 and 50),
    add column toilettes            integer check (toilettes is null or toilettes between 0 and 50),
    add column floors               integer check (floors is null or floors between 1 and 10),
    add column parking_spaces       integer check (parking_spaces is null or parking_spaces between 0 and 20),
    add column orientation          varchar(20),
    add column long_description     text,
    add column finishes             text,
    -- Precio de lista: dato comercial interno. No sale hacia el comprador ni el enlace
    -- público, y no hay cuotas ni pagos (13.2).
    add column list_price           numeric(15, 2) check (list_price is null or list_price > 0),
    add column price_currency       varchar(3) check (price_currency is null or price_currency in ('ARS', 'USD'));

-- --- Etapas por casa, con su detalle ---
alter table stages
    add column unit_id       uuid references units (id) on delete cascade,
    add column description   text,
    add column planned_start date,
    add column planned_end   date,
    add column started_on    date,
    add column completed_on  date;

alter table stages drop constraint stages_development_id_order_index_key;
create unique index uq_stages_unit_order on stages (unit_id, order_index) where unit_id is not null;
create unique index uq_stages_common_order on stages (development_id, order_index) where unit_id is null;
create index idx_stages_unit on stages (unit_id);

alter table progress_entries add column unit_id uuid references units (id) on delete cascade;
alter table media_assets add column unit_id uuid references units (id) on delete cascade;
alter table expenses add column unit_id uuid references units (id) on delete cascade;
alter table ai_extractions add column unit_id uuid references units (id) on delete cascade;

create index idx_progress_entries_unit on progress_entries (unit_id);
create index idx_media_assets_unit on media_assets (unit_id);
create index idx_expenses_unit on expenses (unit_id);

-- --- Datos existentes ---
-- Una obra de una sola unidad era, de hecho, una casa: todo lo suyo pasa a esa casa.
-- Las de varias unidades conservan lo cargado como obras comunes del desarrollo.
with single_unit as (
    select u.development_id, min(u.id::text)::uuid as unit_id
    from units u
    group by u.development_id
    having count(*) = 1
)
update stages s set unit_id = su.unit_id from single_unit su where s.development_id = su.development_id;

with single_unit as (
    select u.development_id, min(u.id::text)::uuid as unit_id
    from units u group by u.development_id having count(*) = 1
)
update progress_entries p set unit_id = su.unit_id from single_unit su where p.development_id = su.development_id;

with single_unit as (
    select u.development_id, min(u.id::text)::uuid as unit_id
    from units u group by u.development_id having count(*) = 1
)
update media_assets m set unit_id = su.unit_id from single_unit su where m.development_id = su.development_id;

with single_unit as (
    select u.development_id, min(u.id::text)::uuid as unit_id
    from units u group by u.development_id having count(*) = 1
)
update expenses e set unit_id = su.unit_id from single_unit su where e.development_id = su.development_id;

with single_unit as (
    select u.development_id, min(u.id::text)::uuid as unit_id
    from units u group by u.development_id having count(*) = 1
)
update ai_extractions a set unit_id = su.unit_id from single_unit su where a.development_id = su.development_id;

with single_unit as (
    select u.development_id, min(u.id::text)::uuid as unit_id
    from units u group by u.development_id having count(*) = 1
)
update captures_3d c set unit_id = su.unit_id from single_unit su where c.development_id = su.development_id and c.unit_id is null;

-- La unidad interna de una obra única pasa a ser una casa con nombre.
update units set code = 'Casa 1' where code = 'Principal';
