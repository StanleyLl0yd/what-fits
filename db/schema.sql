-- What Fits? PostgreSQL schema v0.1
-- Target: PostgreSQL 15+

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS brands (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT NOT NULL UNIQUE,
    normalized_name TEXT NOT NULL UNIQUE,
    website         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS markets (
    code            TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    is_target       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS device_categories (
    code            TEXT PRIMARY KEY,
    name            TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS device_models (
    id              BIGSERIAL PRIMARY KEY,
    brand_id        BIGINT NOT NULL REFERENCES brands(id),
    category_code   TEXT NOT NULL REFERENCES device_categories(code),
    canonical_name  TEXT NOT NULL,
    model_code      TEXT NOT NULL,
    family          TEXT,
    search_text     TEXT NOT NULL,
    lifecycle_status TEXT NOT NULL DEFAULT 'unknown'
        CHECK (lifecycle_status IN ('current','legacy','unknown')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (brand_id, model_code)
);

CREATE INDEX IF NOT EXISTS idx_device_models_search_trgm
    ON device_models USING gin (search_text gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_device_models_model_code_trgm
    ON device_models USING gin (model_code gin_trgm_ops);

CREATE TABLE IF NOT EXISTS device_identifiers (
    id               BIGSERIAL PRIMARY KEY,
    device_model_id  BIGINT NOT NULL REFERENCES device_models(id) ON DELETE CASCADE,
    identifier_type  TEXT NOT NULL
        CHECK (identifier_type IN ('model','alias','product_number','sku','gtin','ean','upc','other')),
    identifier_value TEXT NOT NULL,
    normalized_value TEXT NOT NULL,
    UNIQUE (device_model_id, identifier_type, normalized_value)
);
CREATE INDEX IF NOT EXISTS idx_device_identifiers_normalized_trgm
    ON device_identifiers USING gin (normalized_value gin_trgm_ops);

CREATE TABLE IF NOT EXISTS replacement_types (
    code        TEXT PRIMARY KEY,
    name        TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS parts (
    id              BIGSERIAL PRIMARY KEY,
    brand_id        BIGINT REFERENCES brands(id),
    canonical_name  TEXT NOT NULL,
    part_number     TEXT NOT NULL,
    part_kind       TEXT NOT NULL,
    color           TEXT,
    yield_pages     INTEGER CHECK (yield_pages IS NULL OR yield_pages > 0),
    search_text     TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (brand_id, part_number)
);
CREATE INDEX IF NOT EXISTS idx_parts_search_trgm
    ON parts USING gin (search_text gin_trgm_ops);

CREATE TABLE IF NOT EXISTS part_identifiers (
    id               BIGSERIAL PRIMARY KEY,
    part_id          BIGINT NOT NULL REFERENCES parts(id) ON DELETE CASCADE,
    identifier_type  TEXT NOT NULL
        CHECK (identifier_type IN ('part_number','alias','sku','gtin','ean','upc','other')),
    identifier_value TEXT NOT NULL,
    normalized_value TEXT NOT NULL,
    UNIQUE (part_id, identifier_type, normalized_value)
);

CREATE TABLE IF NOT EXISTS source_documents (
    id           BIGSERIAL PRIMARY KEY,
    source_type  TEXT NOT NULL
        CHECK (source_type IN ('manufacturer_official','manufacturer_manual','licensed_data','distributor','marketplace','community','other')),
    publisher    TEXT NOT NULL,
    title        TEXT,
    url          TEXT NOT NULL UNIQUE,
    market_code  TEXT REFERENCES markets(code),
    checked_at   DATE NOT NULL,
    notes        TEXT
);

CREATE TABLE IF NOT EXISTS compatibility_edges (
    id                  BIGSERIAL PRIMARY KEY,
    device_model_id     BIGINT NOT NULL REFERENCES device_models(id) ON DELETE CASCADE,
    replacement_type    TEXT NOT NULL REFERENCES replacement_types(code),
    part_id              BIGINT NOT NULL REFERENCES parts(id) ON DELETE CASCADE,
    market_code          TEXT NOT NULL REFERENCES markets(code),
    status               TEXT NOT NULL
        CHECK (status IN ('VERIFIED','VERIFIED_THIRD_PARTY','SPEC_MATCH','UNVERIFIED','UNDER_REVIEW')),
    confidence           NUMERIC(4,3) NOT NULL DEFAULT 1.000 CHECK (confidence >= 0 AND confidence <= 1),
    conditions           JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (device_model_id, replacement_type, part_id, market_code)
);
CREATE INDEX IF NOT EXISTS idx_fit_device_type_market
    ON compatibility_edges (device_model_id, replacement_type, market_code);

CREATE TABLE IF NOT EXISTS compatibility_evidence (
    id                  BIGSERIAL PRIMARY KEY,
    compatibility_id    BIGINT NOT NULL REFERENCES compatibility_edges(id) ON DELETE CASCADE,
    source_document_id  BIGINT NOT NULL REFERENCES source_documents(id),
    evidence_note       TEXT,
    evidence_locator    TEXT,
    verified_at         DATE NOT NULL,
    UNIQUE (compatibility_id, source_document_id)
);

-- Minimal personal-memory layer. Not required for the first API demo,
-- but included now so that device saving does not require redesigning the core.
CREATE TABLE IF NOT EXISTS app_installations (
    id              UUID PRIMARY KEY,
    market_code     TEXT REFERENCES markets(code),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_devices (
    id                UUID PRIMARY KEY,
    installation_id   UUID NOT NULL REFERENCES app_installations(id) ON DELETE CASCADE,
    device_model_id   BIGINT NOT NULL REFERENCES device_models(id),
    nickname          TEXT,
    device_market     TEXT REFERENCES markets(code),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (installation_id, device_model_id)
);

CREATE TABLE IF NOT EXISTS replacement_events (
    id                UUID PRIMARY KEY,
    user_device_id    UUID NOT NULL REFERENCES user_devices(id) ON DELETE CASCADE,
    part_id           BIGINT NOT NULL REFERENCES parts(id),
    replacement_type  TEXT NOT NULL REFERENCES replacement_types(code),
    event_date        DATE NOT NULL,
    confirmed_fit     BOOLEAN,
    price_minor       BIGINT,
    currency          CHAR(3),
    retailer          TEXT,
    notes             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reference data
INSERT INTO markets(code, name, is_target) VALUES
    ('RU', 'Россия', TRUE),
    ('EU', 'Европа', FALSE),
    ('CIS', 'СНГ', FALSE),
    ('CN', 'Китай', FALSE),
    ('US', 'США', FALSE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO device_categories(code, name) VALUES
    ('printer', 'Принтер / МФУ')
ON CONFLICT (code) DO NOTHING;

INSERT INTO replacement_types(code, name) VALUES
    ('toner_cartridge', 'Тонер-картридж'),
    ('ink', 'Чернила / чернильный картридж'),
    ('drum_unit', 'Фотобарабан')
ON CONFLICT (code) DO NOTHING;
