-- Two more sources, each a distinct venue from the one it sits beside:
--   BITGET_CLASSIC — Bitget's pre-UTA account API (v2). A classic key cannot call the UTA endpoints
--                    and vice versa, so it is a separate source rather than a fallback path.
--   KRAKEN_SPOT    — Kraken's spot platform, a different account and balance from Kraken Futures.
--
-- Same double enforcement as V018: the value list is checked on data_sources.kind AND positions.source.
ALTER TABLE data_sources DROP CONSTRAINT IF EXISTS data_sources_kind_check;
ALTER TABLE data_sources ADD CONSTRAINT data_sources_kind_check
    CHECK (kind IN (
        'BITUNIX','BINGX','BITMART','QUANTFURY','JOURNAL_CSV',
        'BINANCE_FUTURES','BYBIT','OKX','BITGET','BITGET_CLASSIC',
        'KRAKEN_FUTURES','KRAKEN_SPOT','GATEIO_FUTURES','MEXC_FUTURES','KUCOIN_FUTURES'
    ));

ALTER TABLE positions DROP CONSTRAINT IF EXISTS positions_source_check;
ALTER TABLE positions ADD CONSTRAINT positions_source_check
    CHECK (source IN (
        'BITUNIX','BINGX','BITMART','QUANTFURY','JOURNAL_CSV',
        'BINANCE_FUTURES','BYBIT','OKX','BITGET','BITGET_CLASSIC',
        'KRAKEN_FUTURES','KRAKEN_SPOT','GATEIO_FUTURES','MEXC_FUTURES','KUCOIN_FUTURES'
    ));
