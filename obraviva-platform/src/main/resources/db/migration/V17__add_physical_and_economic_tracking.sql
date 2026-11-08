-- V17: seguimiento físico y económico para el panel del desarrollista.
--
-- AVANCE FÍSICO. Lo declara el responsable, no lo estima el sistema: la sección 6
-- del documento maestro descarta explícitamente la estimación por visión
-- computacional. El estado de la etapa (PENDING/IN_PROGRESS/COMPLETED/DELAYED) se
-- mantiene: responde "¿cómo va?", mientras que el porcentaje responde "¿cuánto
-- falta?". Son preguntas distintas y una no se deriva de la otra.
--
-- El peso permite que el avance global sea una suma ponderada: excavar no pesa lo
-- mismo que levantar diez pisos. NO se exige que los pesos sumen 100: el cálculo
-- divide por la suma real. Exigirlo obligaría a recalcular todas las etapas al
-- agregar una, y esa restricción termina esquivándose con una etapa de peso cero.

alter table stages
    add column progress_percent integer not null default 0
        check (progress_percent between 0 and 100);

alter table stages
    add column weight integer not null default 1
        check (weight > 0);

-- Presupuesto de la etapa. Nulo significa "sin presupuestar", distinto de cero.
alter table stages
    add column budget_amount numeric(15, 2)
        check (budget_amount is null or budget_amount >= 0);

-- GASTOS. Montos nominales, sin indexar: la indexación por series oficiales de
-- costos de la construcción (ICC-Córdoba, INDEC) queda declarada como evolución
-- posterior a la tesis. Con la inflación argentina, comparar un presupuesto viejo
-- contra gasto actual subestima el desvío; el panel lo muestra nominal y la
-- limitación se documenta, en vez de inventar un ajuste.
--
-- numeric y no punto flotante: un panel que muestra centavos inexistentes pierde
-- toda credibilidad.

create table expenses (
    id                  uuid primary key,
    organization_id     uuid not null references organizations (id),
    development_id      uuid not null references developments (id),

    -- Opcional: hay gastos generales de obra que no corresponden a ninguna etapa.
    -- El semáforo por etapa sólo considera los que sí la tienen; el total, todos.
    stage_id            uuid references stages (id),

    incurred_on         date not null,
    amount              numeric(15, 2) not null check (amount > 0),
    description         varchar(300),

    created_by          uuid not null references users (id),
    created_at          timestamptz not null default now()
);

create index idx_expenses_development on expenses (development_id);
create index idx_expenses_stage on expenses (stage_id);
create index idx_expenses_organization on expenses (organization_id);
create index idx_expenses_incurred_on on expenses (incurred_on);