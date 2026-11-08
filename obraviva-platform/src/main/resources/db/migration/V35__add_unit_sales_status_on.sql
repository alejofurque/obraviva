-- El día de la reserva o de la venta de una casa, según su estado comercial. Nulo si está
-- disponible o si no se cargó: las casas que ya estaban reservadas o vendidas quedan sin
-- fecha, porque no se sabe cuándo pasó. Es una fecha, no un historial: el CRM y los
-- montos de seña quedan fuera del alcance.
alter table units
    add column sales_status_on date;
