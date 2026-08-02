-- Market benchmarks: a data-driven registry of reference indices/assets plus their stored daily
-- closes, used to overlay buy-and-hold monthly returns on the Monthly ROI chart. Global reference
-- data — not profile-scoped, because a benchmark's price history is the same for every user.
--
-- Every close is stored and compared in USD. TradeLog's capital is USDT-denominated, so no FX
-- conversion happens anywhere in this feature: converting to EUR would contaminate the return with
-- FX movement instead of reflecting the asset's own performance.
CREATE TABLE benchmarks (
    key             VARCHAR(32) PRIMARY KEY,
    -- Keyless source the background job fetches closes from ('yahoo' for indices/ETFs/commodities,
    -- 'binance' for crypto). Both are public market-data endpoints requiring no credentials.
    source_provider VARCHAR(24) NOT NULL,
    source_symbol   VARCHAR(120) NOT NULL,
    -- Currency the source quotes the benchmark in. Always USD today; the column exists so a
    -- non-USD benchmark is rejected loudly at seed time rather than silently mis-compared.
    currency        VARCHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    -- How to fetch: 'equity' (Yahoo chart) or 'crypto' (Binance USDT klines).
    kind            VARCHAR(16) NOT NULL CHECK (kind IN ('equity','crypto')),
    -- Off benchmarks are hidden from the chart legend and skipped by the refresh job.
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order      INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One close per (benchmark, date), in USD. Non-trading days are never stored; reads forward-fill
-- from the last row <= date, which is what lets a Mon-Fri index be compared over a month that ends
-- on a weekend. The background job upserts by the unique key below, so repeat runs self-heal.
CREATE TABLE benchmark_prices (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    benchmark_key VARCHAR(32) NOT NULL REFERENCES benchmarks(key) ON DELETE CASCADE,
    price_date    DATE NOT NULL,
    close         NUMERIC(28,12) NOT NULL CHECK (close >= 0),
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_benchmark_prices_unique
    ON benchmark_prices (benchmark_key, price_date);

-- Initial, extensible set — all quoted natively in USD, so nothing is converted. The S&P 500
-- (^GSPC) and Gold futures (GC=F) come from Yahoo, MSCI World via the iShares URTH ETF, and
-- Bitcoin as Binance BTCUSDT (USDT treated as USD). Adding a benchmark is one row here plus one
-- i18n label — no chart or service code changes.
--
-- The crypto index is NCIQ, the Hashdex ETF tracking the Nasdaq Crypto Index. Two alternatives with
-- far longer history were rejected on purpose: GDLC and BITW spent most of their lives as
-- closed-end trusts trading at wide premiums/discounts to NAV, so their pre-2025 monthly moves are
-- fund sentiment rather than the basket's return. NCIQ only starts 2025-02-14 and earlier months
-- therefore render as gaps — the honest outcome, and the convention this whole feature follows.
-- (A true index, CMC Crypto 200 / ^CMC200, was the first choice until it proved dead: Yahoo still
-- serves its trading calendar but every close after 2024-08-02 is null.)
INSERT INTO benchmarks (key, source_provider, source_symbol, currency, kind, sort_order) VALUES
    ('bitcoin',      'binance', 'BTCUSDT', 'USD', 'crypto', 10),
    ('crypto_index', 'yahoo',   'NCIQ',    'USD', 'equity', 20),
    ('msci_world',   'yahoo',   'URTH',    'USD', 'equity', 30),
    ('sp500',        'yahoo',   '^GSPC',   'USD', 'equity', 40),
    ('gold',         'yahoo',   'GC=F',    'USD', 'equity', 50);
