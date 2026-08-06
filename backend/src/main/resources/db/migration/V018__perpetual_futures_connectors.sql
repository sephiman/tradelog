-- Eight more perpetual-futures venues become valid data sources. Mirrors V007/V013: the SAME value
-- list is enforced TWICE — on data_sources.kind AND on positions.source — so both must be updated
-- together or every synced row of a new venue insert-fails.
--
-- BINANCE_FUTURES is 15 characters and the columns are VARCHAR(16), so the names below are at the
-- limit; a longer venue name needs the column widened first.
--
-- Nothing about BitMart changes here. It closes on 2026-08-26 and is handled as a freeze: its kind
-- stays valid, its rows stay, and the data source simply stops syncing itself on that date.
ALTER TABLE data_sources DROP CONSTRAINT IF EXISTS data_sources_kind_check;
ALTER TABLE data_sources ADD CONSTRAINT data_sources_kind_check
    CHECK (kind IN (
        'BITUNIX','BINGX','BITMART','QUANTFURY','JOURNAL_CSV',
        'BINANCE_FUTURES','BYBIT','OKX','BITGET','KRAKEN_FUTURES','GATEIO_FUTURES','MEXC_FUTURES','KUCOIN_FUTURES'
    ));

ALTER TABLE positions DROP CONSTRAINT IF EXISTS positions_source_check;
ALTER TABLE positions ADD CONSTRAINT positions_source_check
    CHECK (source IN (
        'BITUNIX','BINGX','BITMART','QUANTFURY','JOURNAL_CSV',
        'BINANCE_FUTURES','BYBIT','OKX','BITGET','KRAKEN_FUTURES','GATEIO_FUTURES','MEXC_FUTURES','KUCOIN_FUTURES'
    ));
