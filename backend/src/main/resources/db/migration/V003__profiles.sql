-- Trading profiles. Private to a single user (never shared). Kind is PERSONAL or BOT;
-- BOT profiles carry an optional free-text strategy note.
CREATE TABLE profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind            VARCHAR(16) NOT NULL CHECK (kind IN ('PERSONAL','BOT')),
    name            VARCHAR(80) NOT NULL,
    strategy_note   VARCHAR(1000),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_profiles_user ON profiles(user_id);
CREATE UNIQUE INDEX idx_profiles_user_name_lower ON profiles (user_id, LOWER(name));
