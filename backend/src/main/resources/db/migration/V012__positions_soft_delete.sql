-- Soft delete for positions. A position the user removes is marked with deleted_at instead of being
-- physically dropped, so it disappears from every read path (list, analytics, detail, counts) while
-- staying findable by the sync dedup lookup (data_source_id, external_id). That lets re-sync see the
-- row is deleted and SKIP it, rather than re-inserting a duplicate from the exchange's history.
ALTER TABLE positions ADD COLUMN deleted_at TIMESTAMPTZ;

-- The hot read paths only ever want live rows; a partial index keeps them lean as deletions accumulate.
CREATE INDEX idx_positions_profile_active ON positions (profile_id, closed_at DESC) WHERE deleted_at IS NULL;
