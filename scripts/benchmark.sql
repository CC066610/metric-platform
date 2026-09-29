-- Benchmark harness for the bucketed query path.
-- Generates 100k points, then times the three read shapes the dashboard uses.
\timing on

TRUNCATE metric_point;

-- 100k points over the last 24 hours, in 10 second steps, with jitter.
INSERT INTO metric_point (metric_name, tags, ts, value)
SELECT 'http.latency.p95',
       jsonb_build_object('host', 'host-' || (g % 3 + 1)),
       now() - ((g * 10) || ' seconds')::interval,
       120 + 30 * sin(g / 50.0) + (random() * 20 - 10)
FROM generate_series(1, 100000) AS g;

ANALYZE metric_point;

SELECT count(*) AS points, min(ts) AS oldest, max(ts) AS newest FROM metric_point;

\echo '--- 1) raw scan, no bucketing (what a naive chart query does) ---'
SELECT count(*) AS raw_rows
FROM metric_point
WHERE metric_name = 'http.latency.p95'
  AND ts >= now() - interval '7 days';

\echo '--- 2) bucketed at 60s over 6 hours ---'
SELECT count(*) AS buckets
FROM (
  SELECT to_timestamp(floor(extract(epoch FROM ts) / 60) * 60) AS bucket
  FROM metric_point
  WHERE metric_name = 'http.latency.p95'
    AND ts >= now() - interval '6 hours'
    AND ts < now()
  GROUP BY bucket
) b;

\echo '--- 3) bucketed at 300s over 24 hours ---'
SELECT count(*) AS buckets
FROM (
  SELECT to_timestamp(floor(extract(epoch FROM ts) / 300) * 300) AS bucket,
         avg(value) AS avg_v, max(value) AS max_v, min(value) AS min_v, count(*) AS c
  FROM metric_point
  WHERE metric_name = 'http.latency.p95'
    AND ts >= now() - interval '24 hours'
    AND ts < now()
  GROUP BY bucket
) b;

\echo '--- 4) index usage check for the bucketed range scan ---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT to_timestamp(floor(extract(epoch FROM ts) / 300) * 300) AS bucket,
       avg(value), max(value), min(value), count(*)
FROM metric_point
WHERE metric_name = 'http.latency.p95'
  AND ts >= now() - interval '24 hours'
  AND ts < now()
GROUP BY bucket;

\echo '--- 5) statistical baseline (mean + 3 sigma) over a 10 minute window ---'
SELECT round(avg(value)::numeric, 3) AS mean,
       round(stddev_pop(value)::numeric, 3) AS stddev,
       count(*) AS samples,
       round((avg(value) + 3 * stddev_pop(value))::numeric, 3) AS sigma_bound
FROM metric_point
WHERE metric_name = 'http.latency.p95'
  AND ts >= now() - interval '10 minutes';
