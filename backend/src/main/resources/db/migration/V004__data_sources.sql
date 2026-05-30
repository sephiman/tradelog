-- A data source is a per-profile connector instance (1:N per profile). API sources
-- (Bitunix, BingX) store encrypted credentials and a sync cursor; the Quantfury PDF
-- source carries no credentials. Credentials are AES-GCM ciphertext and never leave
-- the server. Cursor holds a per-source incremental sync watermark (JSON).
CREATE TABLE data_sources (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id        UUID NOT NULL REFERENCES profiles(id) ON DELETE RESTRICT,
    kind              VARCHAR(16) NOT NULL CHECK (kind IN ('BITUNIX','BINGX','QUANTFURY')),
    label             VARCHAR(80) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ERROR','DISABLED')),
    status_detail     VARCHAR(64),
    credentials_enc   TEXT,
    cursor            TEXT,
    last_synced_at    TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_data_sources_profile ON data_sources(profile_id);
CREATE UNIQUE INDEX idx_data_sources_profile_kind_label ON data_sources (profile_id, kind, LOWER(label));

-- Audit row per sync attempt; drives UI status and observability.
CREATE TABLE sync_runs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    data_source_id    UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    trigger           VARCHAR(16) NOT NULL CHECK (trigger IN ('LOGIN','MANUAL','UPLOAD')),
    status            VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING','SUCCESS','ERROR')),
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    inserted          INTEGER NOT NULL DEFAULT 0,
    updated           INTEGER NOT NULL DEFAULT 0,
    error_code        VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sync_runs_data_source ON sync_runs(data_source_id, started_at DESC);
