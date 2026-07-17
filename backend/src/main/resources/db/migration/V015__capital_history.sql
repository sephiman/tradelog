-- Capital history: dated anchor points ("adjustments") + daily capital snapshots, per exchange.
-- One table holds both kinds of row:
--   source = 'MANUAL': a user-entered balance (the adjustment history). It is an ANCHOR: the truth
--                      at that date and a hard cut — PnL closed before it no longer counts afterward.
--   source = 'AUTO'  : a job-computed estimate carried forward from the latest anchor.
-- A row dated D holds the capital at the START of day D in the owner's time zone, so a period
-- starting at D can use it as the ROI denominator without double-counting day-D trades.
CREATE TABLE capital_snapshots (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id    UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    exchange      VARCHAR(80) NOT NULL,
    snapshot_date DATE NOT NULL,
    amount        NUMERIC(38,8) NOT NULL,
    source        VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_capital_snapshots_profile_exchange_date
    ON capital_snapshots (profile_id, exchange, snapshot_date);

-- How often the background job materializes AUTO snapshots, per profile.
ALTER TABLE capital_risk_settings
    ADD COLUMN snapshot_frequency VARCHAR(16) NOT NULL DEFAULT 'DAILY';

-- Repurpose the old single "current capital" values as each profile's first adjustment, dated on
-- the day the value was last maintained (in the owner's time zone — day boundaries are the user's).
INSERT INTO capital_snapshots (profile_id, exchange, snapshot_date, amount, source)
SELECT tc.profile_id, tc.exchange, (tc.updated_at AT TIME ZONE u.time_zone)::date, tc.amount, 'MANUAL'
FROM trading_capital tc
JOIN profiles p ON p.id = tc.profile_id
JOIN users u ON u.id = p.user_id
WHERE tc.amount > 0;

-- The adjustment history supersedes the single current value.
DROP TABLE trading_capital;
