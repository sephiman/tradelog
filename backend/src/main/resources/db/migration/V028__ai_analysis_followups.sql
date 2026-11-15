-- Persisted follow-up Q&A on a completed AI analysis run.
CREATE TABLE ai_analysis_followups (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id               UUID NOT NULL REFERENCES ai_analysis_runs(id) ON DELETE CASCADE,
    profile_id           UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    user_id              UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    question             TEXT NOT NULL,
    answer               TEXT NOT NULL,
    citations_json       JSONB NOT NULL DEFAULT '[]',
    citations_total      INTEGER NOT NULL DEFAULT 0,
    citations_ungrounded INTEGER NOT NULL DEFAULT 0,
    input_tokens         INTEGER,
    output_tokens        INTEGER,
    reasoning_tokens     INTEGER,
    latency_ms           BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_analysis_followups_run ON ai_analysis_followups (run_id, created_at ASC);
