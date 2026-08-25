-- A grid-bot run is not a trade: hundreds of matched orders leave it with no single entry price,
-- exit price or quantity. Rather than synthesise those (fake prices would pollute every price-based
-- view), the three columns become nullable and the row says which kind of entry it is.
--
-- Additive and defaulted, so every existing row stays a TRADE with its prices intact.
ALTER TABLE positions
    ALTER COLUMN qty         DROP NOT NULL,
    ALTER COLUMN entry_price DROP NOT NULL,
    ALTER COLUMN exit_price  DROP NOT NULL;

ALTER TABLE positions ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'TRADE';
ALTER TABLE positions ADD CONSTRAINT positions_kind_check CHECK (kind IN ('TRADE','GRID_BOT'));

-- Traded notional (both legs, matching the Volume statistic's convention). Null means "derive it
-- from qty × (entry + exit)" — a grid run that left the volume calculator empty stays null and
-- contributes nothing to the statistic, which beats contributing a made-up number.
ALTER TABLE positions ADD COLUMN volume NUMERIC(38,8);

-- Informative only, mirroring what the exchange shows on a closed grid; never fed into analytics.
ALTER TABLE positions ADD COLUMN leverage   NUMERIC(10,2);
ALTER TABLE positions ADD COLUMN investment NUMERIC(38,8);
