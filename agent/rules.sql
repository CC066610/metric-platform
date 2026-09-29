-- Alert rules for the host metrics the agent in ../agent/agent.py produces.
--
-- Every rule below names a metric the agent actually emits, so a fresh install
-- starts judging real data instead of storing it silently. Thresholds are
-- starting points for a workstation; tune them per host.
--
-- The statistical rule is shown as a comment because the agent's own baseline
-- is more useful for a metric with a daily shape; a fixed threshold is the
-- right default until such a shape is understood.
--
-- Re-running this file is safe: each insert is guarded by a NOT EXISTS check on
-- the metric name plus operator, so a second run adds nothing.

-- CPU: sustained saturation. Two consecutive breaches are required, so a build
-- or a browser tab cannot page anyone.
INSERT INTO alert_rule (metric_name, operator, threshold, sigma_multiplier, window_seconds,
                        min_consecutive, cooldown_seconds)
SELECT 'cpu.usage', 'gt', 85, NULL, 300, 2, 600
WHERE NOT EXISTS (
  SELECT 1 FROM alert_rule WHERE metric_name = 'cpu.usage' AND operator = 'gt' AND threshold = 85
);

-- Load per core above 2.0 means more runnable threads than cores: a queue is
-- forming. Judged over a longer window because load is already a moving average.
INSERT INTO alert_rule (metric_name, operator, threshold, sigma_multiplier, window_seconds,
                        min_consecutive, cooldown_seconds)
SELECT 'cpu.load.1m_per_core', 'gt', 2.0, NULL, 300, 3, 900
WHERE NOT EXISTS (
  SELECT 1 FROM alert_rule
  WHERE metric_name = 'cpu.load.1m_per_core' AND operator = 'gt' AND threshold = 2.0
);

-- Memory: 90 percent leaves little room for a spike before the OOM killer runs.
INSERT INTO alert_rule (metric_name, operator, threshold, sigma_multiplier, window_seconds,
                        min_consecutive, cooldown_seconds)
SELECT 'mem.usage', 'gt', 90, NULL, 300, 2, 900
WHERE NOT EXISTS (
  SELECT 1 FROM alert_rule WHERE metric_name = 'mem.usage' AND operator = 'gt' AND threshold = 90
);

-- Disk: one rule covers every mount, because the mount point is a label rather
-- than part of the metric name. A 5 minute window with 3 breaches fits a metric
-- that moves slowly and must not fire on a temporary log burst.
INSERT INTO alert_rule (metric_name, operator, threshold, sigma_multiplier, window_seconds,
                        min_consecutive, cooldown_seconds)
SELECT 'disk.used.percent', 'gt', 85, NULL, 300, 3, 3600
WHERE NOT EXISTS (
  SELECT 1 FROM alert_rule
  WHERE metric_name = 'disk.used.percent' AND operator = 'gt' AND threshold = 85
);

-- Free space, absolute: below 5 GiB is a problem regardless of what percentage
-- of a large volume that represents.
INSERT INTO alert_rule (metric_name, operator, threshold, sigma_multiplier, window_seconds,
                        min_consecutive, cooldown_seconds)
SELECT 'disk.free.bytes', 'lt', 5368709120, NULL, 300, 3, 3600
WHERE NOT EXISTS (
  SELECT 1 FROM alert_rule
  WHERE metric_name = 'disk.free.bytes' AND operator = 'lt' AND threshold = 5368709120
);

-- Ensure every rule has a state row.
INSERT INTO alert_state (rule_id)
SELECT r.id FROM alert_rule r
WHERE NOT EXISTS (SELECT 1 FROM alert_state s WHERE s.rule_id = r.id);
