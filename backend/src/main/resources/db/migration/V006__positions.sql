-- Canonical, flat-to-flat closed positions (one net-exposure lifecycle = one row).
-- Realized PnL, fees and funding are kept SEPARATE and summable (USDT for now) — never
-- consolidated — to leave room for future currency conversion and breakdowns.
-- Idempotent sync upserts on (data_source_id, external_id).
CREATE TABLE positions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id        UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    data_source_id    UUID NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
    source            VARCHAR(16) NOT NULL CHECK (source IN ('BITUNIX','BINGX','QUANTFURY')),
    external_id       VARCHAR(160) NOT NULL,
    symbol_base       VARCHAR(32) NOT NULL,
    symbol_quote      VARCHAR(16) NOT NULL,
    side              VARCHAR(8) NOT NULL CHECK (side IN ('LONG','SHORT')),
    opened_at         TIMESTAMPTZ NOT NULL,
    closed_at         TIMESTAMPTZ NOT NULL,
    qty               NUMERIC(38,18) NOT NULL,
    entry_price       NUMERIC(38,18) NOT NULL,
    exit_price        NUMERIC(38,18) NOT NULL,
    realized_pnl      NUMERIC(38,8) NOT NULL DEFAULT 0,
    fees              NUMERIC(38,8) NOT NULL DEFAULT 0,
    funding           NUMERIC(38,8) NOT NULL DEFAULT 0,
    pnl_currency      VARCHAR(8) NOT NULL DEFAULT 'USDT',
    note              TEXT,
    raw               TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_positions_source_external ON positions (data_source_id, external_id);
CREATE INDEX idx_positions_profile_closed ON positions (profile_id, closed_at DESC);
CREATE INDEX idx_positions_profile_symbol ON positions (profile_id, symbol_base);

-- Individual legs of a position (open / add / reduce / close) so the UI can show the
-- list of operations. Populated from Quantfury PDF rows and exchange fills where available.
CREATE TABLE position_fills (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_id   UUID NOT NULL REFERENCES positions(id) ON DELETE CASCADE,
    seq           INTEGER NOT NULL,
    action        VARCHAR(8) NOT NULL CHECK (action IN ('OPEN','ADD','REDUCE','CLOSE')),
    side          VARCHAR(8) NOT NULL CHECK (side IN ('BUY','SELL')),
    ts            TIMESTAMPTZ NOT NULL,
    price         NUMERIC(38,18) NOT NULL,
    qty           NUMERIC(38,18) NOT NULL,
    value         NUMERIC(38,8),
    fee           NUMERIC(38,8),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_position_fills_position ON position_fills (position_id, seq);

-- Position ↔ tag link. Primary key (position_id, group_id) gives at most one tag per
-- group per position. tag_id must belong to group_id (enforced in the service layer).
CREATE TABLE position_tags (
    position_id   UUID NOT NULL REFERENCES positions(id) ON DELETE CASCADE,
    group_id      UUID NOT NULL REFERENCES tag_groups(id) ON DELETE CASCADE,
    tag_id        UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (position_id, group_id)
);
CREATE INDEX idx_position_tags_tag ON position_tags(tag_id);
