-- Safe on all supported Postgres versions; gen_random_uuid() is built into
-- core as of PG13, but this makes the migration self-sufficient regardless.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE app_users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_users_email UNIQUE (email)
);

CREATE TABLE documents (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id          UUID NOT NULL,
    original_filename VARCHAR(500) NOT NULL,
    storage_key       VARCHAR(500) NOT NULL,
    content_type      VARCHAR(255) NOT NULL,
    size_bytes        BIGINT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_documents_owner FOREIGN KEY (owner_id)
        REFERENCES app_users (id) ON DELETE CASCADE
);

CREATE INDEX idx_documents_owner_id ON documents (owner_id);
CREATE UNIQUE INDEX uq_documents_storage_key ON documents (storage_key);
