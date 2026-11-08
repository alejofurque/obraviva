-- V15: enlaces públicos para compartir una unidad.
--
-- El token NO se guarda en claro: se almacena su hash SHA-256, igual que los
-- refresh tokens (ver V3). El valor completo existe una sola vez, en la respuesta
-- de creación. Quien lea esta tabla no obtiene enlaces utilizables.
--
-- El alcance se congela al emitir: el enlace apunta a una unidad concreta y no a
-- lo que su creador pueda ver hoy. Un enlace compartido con una familia no debe
-- dejar de funcionar porque cambió un permiso interno; deja de funcionar cuando
-- alguien lo revoca.

create table share_links (
    id                  uuid primary key,
    organization_id     uuid not null references organizations (id),
    development_id      uuid not null references developments (id),
    unit_id             uuid not null references units (id),

    -- Hash del token, no el token. Único: la búsqueda pública entra por acá.
    token_hash          varchar(64) not null unique,

    -- Etiqueta opcional que le pone el emisor para reconocer el enlace
    -- ("familia", "banco"), sin ningún efecto sobre el acceso.
    label               varchar(120),

    -- Vencimiento opcional. Nulo significa que no vence por tiempo.
    expires_at          timestamptz,

    -- Revocación: distinta del vencimiento. Es un acto deliberado y guarda quién
    -- y cuándo, que es lo que se pregunta cuando algo se filtró.
    revoked_at          timestamptz,
    revoked_by          uuid references users (id),

    -- Uso, para que el emisor sepa si el enlace circuló.
    last_accessed_at    timestamptz,
    access_count        integer not null default 0,

    created_by          uuid not null references users (id),
    created_at          timestamptz not null default now()
);

-- La vista pública busca por hash en cada petición: es el índice caliente.
create unique index idx_share_links_token on share_links (token_hash);
create index idx_share_links_unit on share_links (unit_id);
create index idx_share_links_organization on share_links (organization_id);