-- Archiving a tag retires it from NEW assignments while every existing position_tags row stays
-- intact — freeze, never delete (same principle as the BitMart shutdown). Additive and nullable:
-- null = active, so every existing tag stays active without a backfill.
ALTER TABLE tags ADD COLUMN archived_at TIMESTAMPTZ NULL;
