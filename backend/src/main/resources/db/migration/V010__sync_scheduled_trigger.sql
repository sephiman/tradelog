-- The daily background sweep records its runs with a new SCHEDULED trigger, alongside the
-- existing LOGIN/MANUAL/UPLOAD values. Widen the sync_runs trigger check to admit it.
ALTER TABLE sync_runs DROP CONSTRAINT IF EXISTS sync_runs_trigger_check;
ALTER TABLE sync_runs ADD CONSTRAINT sync_runs_trigger_check
    CHECK (trigger IN ('LOGIN','MANUAL','UPLOAD','SCHEDULED'));
