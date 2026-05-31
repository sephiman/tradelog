-- Journal CSV becomes a valid position source, and positions gain an `exchange` (venue) column.
-- `source` is HOW the data was ingested (Bitunix/BingX/Quantfury API/PDF, or a manual CSV);
-- `exchange` is WHERE the trade actually happened. For the live connectors the venue equals the
-- source; for a Journal CSV the venue comes from the file (a dead exchange like FTX) or, when the
-- row leaves it blank, the data source's label.
ALTER TABLE data_sources DROP CONSTRAINT IF EXISTS data_sources_kind_check;
ALTER TABLE data_sources ADD CONSTRAINT data_sources_kind_check
    CHECK (kind IN ('BITUNIX','BINGX','QUANTFURY','JOURNAL_CSV'));

ALTER TABLE positions DROP CONSTRAINT IF EXISTS positions_source_check;
ALTER TABLE positions ADD CONSTRAINT positions_source_check
    CHECK (source IN ('BITUNIX','BINGX','QUANTFURY','JOURNAL_CSV'));

ALTER TABLE positions ADD COLUMN exchange VARCHAR(64);

-- Backfill existing rows from their source (no JOURNAL_CSV rows exist yet).
UPDATE positions SET exchange = CASE source
    WHEN 'BITUNIX'   THEN 'Bitunix'
    WHEN 'BINGX'     THEN 'BingX'
    WHEN 'QUANTFURY' THEN 'Quantfury'
    ELSE exchange
END;

CREATE INDEX idx_positions_profile_exchange ON positions (profile_id, exchange);
