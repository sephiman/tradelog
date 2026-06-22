-- Allow BITMART as a data source kind. BitMart USDT-M futures is an API source
-- (KEYED reads of /contract/private/trades), reconstructed into closed positions
-- like BingX. Mirrors V007: the SAME value list is enforced twice — on data_sources.kind
-- AND on positions.source — so both must be updated or reconstructed BITMART rows insert-fail.
ALTER TABLE data_sources DROP CONSTRAINT IF EXISTS data_sources_kind_check;
ALTER TABLE data_sources ADD CONSTRAINT data_sources_kind_check
    CHECK (kind IN ('BITUNIX','BINGX','BITMART','QUANTFURY','JOURNAL_CSV'));

ALTER TABLE positions DROP CONSTRAINT IF EXISTS positions_source_check;
ALTER TABLE positions ADD CONSTRAINT positions_source_check
    CHECK (source IN ('BITUNIX','BINGX','BITMART','QUANTFURY','JOURNAL_CSV'));
