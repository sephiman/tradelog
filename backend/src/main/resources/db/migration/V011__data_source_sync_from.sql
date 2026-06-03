-- Per-source sync start floor. Set once at creation, never updated. Null = backfill as far
-- as the exchange API serves (the prior global default).
ALTER TABLE data_sources ADD COLUMN sync_from TIMESTAMPTZ;
