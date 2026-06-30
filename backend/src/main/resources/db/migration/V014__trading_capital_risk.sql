-- Trading capital & risk. Per-profile, manually entered for now.
--   trading_capital      : one CURRENT capital value per exchange (USDT). No history — overwrite on change.
--   capital_risk_settings: the two configurable risk percentages used to derive the max loss per trade.
-- entry_mode is a forward-compat hook: a later "fetch balance from the exchange API" feature writes
-- rows with entry_mode = 'API'; the read path (dashboard/settings) is indifferent to how the value was
-- produced. Allowed values are enforced in Kotlin, not by a DB CHECK constraint.
CREATE TABLE trading_capital (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id  UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    exchange    VARCHAR(80) NOT NULL,
    amount      NUMERIC(38,8) NOT NULL,
    entry_mode  VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_trading_capital_profile_exchange ON trading_capital (profile_id, exchange);

CREATE TABLE capital_risk_settings (
    profile_id  UUID PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
    risk_pct_1  NUMERIC(6,3) NOT NULL DEFAULT 1,
    risk_pct_2  NUMERIC(6,3) NOT NULL DEFAULT 2,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
