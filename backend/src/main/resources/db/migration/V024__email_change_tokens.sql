-- Verified email change: one row per mailed confirmation link, carrying the address the user asked
-- to move to. Only the token hash is stored. A password change or a completed reset deletes the
-- user's unused rows, so the real owner can always cancel a change in flight.
CREATE TABLE email_change_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    new_email   VARCHAR(255) NOT NULL,
    token_hash  TEXT NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_email_change_tokens_user ON email_change_tokens(user_id);
