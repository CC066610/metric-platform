-- Metric platform schema (PostgreSQL 16+).
-- Idempotent: Spring Boot runs this on every startup (spring.sql.init.mode=always).
-- Design notes are inline; the alert-related tables are the ones that matter
-- beyond a plain metrics store.

CREATE TABLE IF NOT EXISTS metric_point (
  id          BIGSERIAL PRIMARY KEY,
  metric_name TEXT NOT NULL,
  tags        JSONB NOT NULL DEFAULT '{}'::jsonb,
  ts          TIMESTAMPTZ NOT NULL,
  value       DOUBLE PRECISION NOT NULL
);

-- Every query filters by metric_name and a time range, so the column order
-- matters: name first, then ts descending for range scans.
CREATE INDEX IF NOT EXISTS idx_metric_point_name_ts
  ON metric_point (metric_name, ts DESC);

-- Per-key tag filtering uses the JSONB containment operator, so a GIN index
-- pays for itself as soon as a dashboard filters by host or service.
CREATE INDEX IF NOT EXISTS idx_metric_point_tags
  ON metric_point USING GIN (tags);

-- ---------------------------------------------------------------------------
-- Alert rules
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS alert_rule (
  id               BIGSERIAL PRIMARY KEY,
  metric_name      TEXT NOT NULL,
  operator         TEXT NOT NULL,                       -- gt | lt
  threshold        DOUBLE PRECISION NOT NULL,           -- absolute bound
  sigma_multiplier DOUBLE PRECISION,                    -- >0 switches to mean+k*stddev
  window_seconds   INT NOT NULL DEFAULT 300,            -- lookback for the sigma baseline
  -- De-bounce: how many consecutive breaching evaluations are required before
  -- the rule is allowed to fire. A single spike must not page anyone.
  min_consecutive  INT NOT NULL DEFAULT 2,
  -- Cooldown: once fired, stay quiet for this long even if still breaching.
  cooldown_seconds INT NOT NULL DEFAULT 300,
  enabled          BOOLEAN NOT NULL DEFAULT TRUE,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Alert state: one row per rule. This is what turns fire-and-forget alerts into
-- a state machine (ok -> pending -> firing -> ok) with recovery notifications.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS alert_state (
  rule_id       BIGINT PRIMARY KEY REFERENCES alert_rule(id) ON DELETE CASCADE,
  status        TEXT NOT NULL DEFAULT 'ok',              -- ok | pending | firing
  consecutive   INT NOT NULL DEFAULT 0,                  -- breaching evaluations in a row
  last_fired_at TIMESTAMPTZ,                             -- drives the cooldown window
  last_value    DOUBLE PRECISION,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Alert events: the durable history. Every transition (fire and resolve) is
-- recorded so the dashboard can show how long an incident lasted.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS alert_event (
  id          BIGSERIAL PRIMARY KEY,
  rule_id     BIGINT NOT NULL REFERENCES alert_rule(id) ON DELETE CASCADE,
  metric_name TEXT NOT NULL,
  kind        TEXT NOT NULL,                             -- firing | resolved
  observed    DOUBLE PRECISION NOT NULL,
  threshold   DOUBLE PRECISION NOT NULL,
  message     TEXT,
  fired_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_alert_event_rule_time
  ON alert_event (rule_id, fired_at DESC);

-- ---------------------------------------------------------------------------
-- Quiet hours (optional, improvement F). Daily windows only; the crossing-
-- midnight case is handled by the resolver, not by the schema.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS quiet_window (
  id         BIGSERIAL PRIMARY KEY,
  start_hhmm TEXT NOT NULL,                              -- '23:00'
  end_hhmm   TEXT NOT NULL,                              -- '07:00'
  enabled    BOOLEAN NOT NULL DEFAULT TRUE
);

-- ---------------------------------------------------------------------------
-- Seed data so the application has something to evaluate on first run.
-- ON CONFLICT keeps this section re-runnable.
-- ---------------------------------------------------------------------------

INSERT INTO alert_rule (metric_name, operator, threshold, sigma_multiplier, window_seconds,
                        min_consecutive, cooldown_seconds)
SELECT 'cpu.usage', 'gt', 85, NULL, 300, 2, 300
WHERE NOT EXISTS (SELECT 1 FROM alert_rule WHERE metric_name = 'cpu.usage');

INSERT INTO alert_rule (metric_name, operator, threshold, sigma_multiplier, window_seconds,
                        min_consecutive, cooldown_seconds)
SELECT 'http.latency.p95', 'gt', 0, 3.0, 600, 2, 300
WHERE NOT EXISTS (SELECT 1 FROM alert_rule WHERE metric_name = 'http.latency.p95');

INSERT INTO alert_state (rule_id)
SELECT r.id FROM alert_rule r
WHERE NOT EXISTS (SELECT 1 FROM alert_state s WHERE s.rule_id = r.id);
