CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- users: participant record + experiment group
CREATE TABLE IF NOT EXISTS users (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    age_at_signup INTEGER NOT NULL,
    gender TEXT NOT NULL,
    university_major TEXT NOT NULL,
    test_score NUMERIC NOT NULL,
    signup_ts TIMESTAMPTZ NOT NULL DEFAULT now(),
    experiment_group TEXT NOT NULL CHECK (experiment_group IN ('control', 'rate', 'inference')),
    experiment_config JSONB,
    capture_status TEXT NOT NULL DEFAULT 'active' CHECK (capture_status IN ('active', 'opted_out_soft', 'opted_out_hard')),
    opted_out_at TIMESTAMPTZ,
    capture_end_ts TIMESTAMPTZ NOT NULL,
    auth_token TEXT NOT NULL UNIQUE
);

-- canonical_events: use/non-use windows, insert-only, idempotent via client_record_id
CREATE TABLE IF NOT EXISTS canonical_events (
    client_record_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id),
    start_ts TIMESTAMPTZ NOT NULL,
    distinct_app_count INTEGER NOT NULL
);

-- table is insert-only via sync-canonical, which never shipped before this
-- column existed, so it's always empty at deploy time: safe to add NOT NULL
-- directly without a backfill/default.
ALTER TABLE canonical_events ADD COLUMN IF NOT EXISTS granularity TEXT NOT NULL CHECK (granularity IN ('small', 'large'));

-- schema v2 (REQ-SYNC-14a, REQ-SYNC-22): end_ts dropped, derived
-- server-side instead. Every statement here must stay safe to re-run
-- against a database that's already on schema v2, since schema.sql
-- reruns in full on every future edit (SchemaMigrationResource is keyed
-- by a hash of this file).
--
-- v_participant_hours is redefined here, before the DROP COLUMN below,
-- deliberately: on a database still on the pre-v2 schema, the live view
-- still queries end_ts, so Postgres refuses to drop a column a view
-- depends on (SQLState 2BP01). Replacing the view's definition first
-- removes that dependency, so the DROP COLUMN that follows always
-- succeeds, whether this is a first run or a rerun.
CREATE OR REPLACE VIEW v_participant_hours AS
SELECT
    user_id,
    SUM(duration_hours) AS captured_hours
FROM (
    SELECT
        user_id,
        EXTRACT(EPOCH FROM (
            LEAD(start_ts) OVER (PARTITION BY user_id ORDER BY start_ts) - start_ts
        )) / 3600.0 AS duration_hours
    FROM canonical_events
) per_epoch
GROUP BY user_id;

ALTER TABLE canonical_events DROP COLUMN IF EXISTS end_ts;

-- Backfilled rather than defaulted: any pre-v2 rows (test data only; the
-- study had not started yet) get an is_use guess from distinct_app_count,
-- so the column can go NOT NULL without wiping existing rows. New rows
-- always supply is_use directly (openapi.yaml CanonicalRecord).
ALTER TABLE canonical_events ADD COLUMN IF NOT EXISTS is_use BOOLEAN;
UPDATE canonical_events SET is_use = (distinct_app_count > 0) WHERE is_use IS NULL;
ALTER TABLE canonical_events ALTER COLUMN is_use SET NOT NULL;

-- REQ-SYNC-22: backstop for the same reason the on-device schema has
-- uq_epochs_subject_start -- a repeated start_ts for one subject means
-- an epoch got constructed twice, which would otherwise silently corrupt
-- the LEAD()-based duration derivation in v_participant_hours above.
CREATE UNIQUE INDEX IF NOT EXISTS uq_canonical_events_user_start ON canonical_events (user_id, start_ts);

CREATE INDEX IF NOT EXISTS idx_canonical_events_user_id ON canonical_events(user_id);

-- inference_metrics: standalone per-inference records, inference group only
CREATE TABLE IF NOT EXISTS inference_metrics (
    client_record_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id),
    ts TIMESTAMPTZ NOT NULL,
    inference_time_ms NUMERIC NOT NULL,
    battery_pct NUMERIC,
    ram_usage_mb NUMERIC
);

CREATE INDEX IF NOT EXISTS idx_inference_metrics_user_id ON inference_metrics(user_id);

-- resource_metrics: standalone idle RAM/battery readings, all groups (REQ-RES-01)
CREATE TABLE IF NOT EXISTS resource_metrics (
    client_record_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id),
    ts TIMESTAMPTZ NOT NULL,
    battery_pct NUMERIC,
    ram_usage_mb NUMERIC
);

CREATE INDEX IF NOT EXISTS idx_resource_metrics_user_id ON resource_metrics(user_id);

-- interventions: outcome of each triggered intervention, rate/inference groups (REQ-INT-01)
CREATE TABLE IF NOT EXISTS interventions (
    client_record_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id),
    triggered_at TIMESTAMPTZ NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('processed', 'not_processed_filtered', 'not_processed_hidden', 'not_processed_timeout')),
    time_to_close_ms INTEGER
);

CREATE INDEX IF NOT EXISTS idx_interventions_user_id ON interventions(user_id);

-- REQ-ADM-01: sum of canonical epoch durations per user. Duration is no
-- longer a stored end_ts - start_ts; it's derived per subject from the
-- contiguous-timeline invariant (one epoch's end is the next epoch's
-- start_ts). Each subject's single most-recently-synced epoch has no
-- successor yet, so LEAD() is NULL for it and SUM() drops it until a
-- later epoch supersedes it -- captured_hours always lags the true
-- total by at most one epoch, by design (REQ-SYNC-14c).
-- (Definition lives above, next to the canonical_events v2 migration --
-- see the comment there for why.)

-- REQ-ADM-01: users currently active and within their capture window
CREATE OR REPLACE VIEW v_active_participants AS
SELECT *
FROM users
WHERE capture_status = 'active'
  AND now() <= capture_end_ts;

-- REQ-ADM-02: volume_goal_hours is a research parameter, read from config, not hardcoded
CREATE TABLE IF NOT EXISTS study_config (
    key TEXT PRIMARY KEY,
    value NUMERIC NOT NULL
);

INSERT INTO study_config (key, value)
VALUES ('volume_goal_hours', 0)
ON CONFLICT (key) DO NOTHING;

-- REQ-ADM-02: enrolled-participant target for the admin Grafana dashboard
INSERT INTO study_config (key, value)
VALUES ('participant_goal', 0)
ON CONFLICT (key) DO NOTHING;

-- REQ-RATE-03: intervention_rate is a research parameter, read from config, not hardcoded
INSERT INTO study_config (key, value)
VALUES ('intervention_rate', 0)
ON CONFLICT (key) DO NOTHING;

CREATE OR REPLACE VIEW v_volume_vs_goal AS
SELECT
    (SELECT COALESCE(SUM(captured_hours), 0) FROM v_participant_hours) AS captured_hours,
    (SELECT value FROM study_config WHERE key = 'volume_goal_hours') AS goal_hours;

-- Deployment group (REQ-DEP-19): POST /final storage.
-- users.test_score already exists (NUMERIC NOT NULL) -- REQ-DEP-03.
ALTER TABLE users ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ;  -- set when the final send is stored

-- One row per participant who finished: the send as received.
CREATE TABLE IF NOT EXISTS final_sends (
    user_id          UUID PRIMARY KEY REFERENCES users(user_id),
    end_reason       TEXT        NOT NULL CHECK (end_reason IN ('completed', 'opt_out')),
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),   -- server time
    forms            JSONB       NOT NULL,                 -- the "forms" object exactly as received
    metrics_stored   INTEGER     NOT NULL,
    metrics_skipped  INTEGER     NOT NULL DEFAULT 0
);

-- One row per answered question, for analysis.
CREATE TABLE IF NOT EXISTS final_answers (
    user_id       UUID    NOT NULL REFERENCES final_sends(user_id),
    form_id       TEXT    NOT NULL,
    form_version  INTEGER NOT NULL,
    question_id   TEXT    NOT NULL,
    value_int     INTEGER,
    value_text    TEXT,
    PRIMARY KEY (user_id, form_id, question_id),
    CHECK ((value_int IS NULL) <> (value_text IS NULL))
);

-- One row per performance record. No ordering or time column on purpose:
-- the app shuffles them and sends no timestamps (privacy).
CREATE TABLE IF NOT EXISTS final_metrics (
    user_id            UUID    NOT NULL REFERENCES final_sends(user_id),
    decision_point     TEXT    NOT NULL CHECK (decision_point IN ('screen_on', 'active_use')),
    duration_ms        INTEGER NOT NULL CHECK (duration_ms >= 0),
    battery_delta_pct  INTEGER,
    current_mean       BIGINT,
    peak_rss_kb        INTEGER
);

CREATE INDEX IF NOT EXISTS idx_final_metrics_user_id ON final_metrics(user_id);
