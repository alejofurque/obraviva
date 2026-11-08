-- Hasta dónde vio cada persona las novedades de un desarrollo. Una marca por persona y
-- desarrollo, no por ítem: lo posterior a seen_until que publicó otra persona es "sin ver".
create table activity_seen (
    user_id         uuid        not null references users (id),
    development_id  uuid        not null references developments (id),
    organization_id uuid        not null references organizations (id),
    seen_until      timestamptz not null,
    updated_at      timestamptz not null,
    primary key (user_id, development_id)
);
