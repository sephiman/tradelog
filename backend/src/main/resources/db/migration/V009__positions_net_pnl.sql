-- Make PnL semantics consistent and unambiguous across sources.
--   realized_pnl = GROSS realized PnL (price movement only, before costs)
--   fees         = total fees, stored as a non-negative cost
--   funding      = net funding (signed cost)
--   net_pnl      = realized_pnl - fees - funding   (the bottom line the user actually kept)
--
-- Until now realized_pnl was inconsistent: BingX/Quantfury/CSV stored GROSS, but Bitunix stored its
-- realizedPNL which is already NET (fees + funding deducted). That made cross-source sums mix gross
-- and net. We add net_pnl and normalize Bitunix's historical rows; the corrected connector keeps new
-- syncs consistent.
ALTER TABLE positions ADD COLUMN net_pnl NUMERIC(38,8) NOT NULL DEFAULT 0;

-- Bitunix: realized_pnl held the NET figure. Recover gross, keep the true net, store fees as a cost.
UPDATE positions SET
    net_pnl      = realized_pnl,
    fees         = abs(fees),
    realized_pnl = realized_pnl + abs(fees) + funding
WHERE source = 'BITUNIX';

-- Every other source already stores gross realized_pnl.
UPDATE positions SET net_pnl = realized_pnl - fees - funding
WHERE source <> 'BITUNIX';
