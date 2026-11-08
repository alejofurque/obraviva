-- V3: tokens de refresco revocables. Se almacena el hash SHA-256
-- del token, nunca el token en claro.
create table refresh_tokens (
    id         uuid primary key,
    user_id    uuid not null references users (id),
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);

create index idx_refresh_tokens_user on refresh_tokens (user_id);