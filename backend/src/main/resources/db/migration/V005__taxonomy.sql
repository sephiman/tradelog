-- Customizable annotation taxonomy, per-user and shared across that user's profiles.
-- Data-driven so new dimensions are new rows, never a schema migration. Seeded with an
-- "origen" group (see TaxonomyService). A position carries at most one tag per group
-- (enforced by position_tags' primary key), so "Origen" behaves as single-select.
CREATE TABLE tag_groups (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code        VARCHAR(32) NOT NULL,
    name        VARCHAR(80) NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 1000,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_tag_groups_user_code ON tag_groups (user_id, code);

CREATE TABLE tags (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id    UUID NOT NULL REFERENCES tag_groups(id) ON DELETE CASCADE,
    code        VARCHAR(48) NOT NULL,
    name        VARCHAR(80) NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 1000,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_tags_group_code ON tags (group_id, code);
