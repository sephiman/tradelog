-- The SourceKind enum is the single source of truth for data_sources.kind and positions.source
ALTER TABLE data_sources DROP CONSTRAINT IF EXISTS data_sources_kind_check;
ALTER TABLE positions DROP CONSTRAINT IF EXISTS positions_source_check;
