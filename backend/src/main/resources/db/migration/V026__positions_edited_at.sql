-- Added edited_at column to mark positions manually edited by the user,
-- so subsequent re-syncs or file re-imports never overwrite the user's modifications.
ALTER TABLE positions ADD COLUMN edited_at TIMESTAMPTZ;
