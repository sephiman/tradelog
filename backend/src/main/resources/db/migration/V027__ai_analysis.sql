-- AI trading-style analysis.
-- ai_settings: per-user opt-in for every AI feature. Off by default; no row reads as off, and no
-- provider configured means every AI endpoint answers 404 as if the feature did not exist.
CREATE TABLE ai_settings (
    user_id    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Persisted history of style analyses: one row per run, scoped to a profile and to the dashboard
-- filter state (period/exchange/origen) it was produced under, so past runs can be compared later.
-- payload_json carries the aggregate dashboard numbers sent to the model; result_json the model's
-- structured answer. tag_map links the anonymized labels the model saw ("Tag A") to the user's tag
-- ids — real tag names never reach the provider and are re-attached only when rendering.
CREATE TABLE ai_analysis_runs (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id                UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status                    VARCHAR(16) NOT NULL DEFAULT 'RUNNING'
                              CHECK (status IN ('RUNNING','COMPLETED','FAILED','INTERRUPTED')),
    model                     VARCHAR(120) NOT NULL,
    prompt_version            VARCHAR(32) NOT NULL,
    locale                    VARCHAR(2) NOT NULL,
    period_preset             VARCHAR(16) NOT NULL,
    range_from                DATE,
    range_to                  DATE,
    exchange_filter           VARCHAR(80) NOT NULL,
    origen_tag_id             UUID,
    time_zone                 VARCHAR(64) NOT NULL,
    sample_size               INTEGER NOT NULL,
    payload_json              JSONB NOT NULL,
    result_json               JSONB,
    tag_map                   JSONB,
    observations_count        INTEGER,
    insufficient_sample_count INTEGER,
    citations_total           INTEGER,
    citations_ungrounded      INTEGER,
    input_tokens              INTEGER,
    output_tokens             INTEGER,
    reasoning_tokens          INTEGER,
    latency_ms                BIGINT,
    error_code                VARCHAR(64),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at               TIMESTAMPTZ
);
CREATE INDEX idx_ai_analysis_runs_profile ON ai_analysis_runs (profile_id, created_at DESC);
