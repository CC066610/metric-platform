# metric-platform

[![ci](https://github.com/CC066610/metric-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/CC066610/metric-platform/actions/workflows/ci.yml)

Self-hosted metric collection with anomaly alerts, built as a single Spring Boot
service on PostgreSQL.

The point of the project is the alert pipeline, not the chart. Most small
monitoring setups either page on every threshold crossing or never page at all;
this one keeps a state machine per rule so a spike stays quiet, a sustained
excursion notifies once, and a recovery notifies again.

![Dashboard demonstration](docs/demo.gif)

*Dashboard recorded against this repository's running stack. Playback is 2x;
the raw recording is kept out of the repository. The chart shows a full day of
one-minute samples; the alert transition at the end is the host's own measured
CPU value breaching a rule created for the demonstration, evaluated by the
shipped state machine.*

## What it does

- **Ingestion**: `POST /api/metrics/batch` stores up to 1000 points per request,
  routed by batch size between a JDBC batch of `INSERT` statements and a single
  `COPY FROM STDIN` stream. The response names the route that served it.
- **Query**: `GET /api/metrics/query` aggregates into fixed-width buckets. The
  bucket width is chosen from the requested span (60s / 300s / 3600s) so a
  seven-day view returns a comparable number of points to a one-hour view.
- **Detection**: alert rules compare either against an absolute threshold or
  against `mean + k * stddev` computed over a configurable lookback window. The
  statistics are computed in PostgreSQL, not in the application.
- **Alert state machine**: every rule carries `ok → pending → firing → ok`
  state with a consecutive-breach counter and a cooldown window.
- **History**: both firing and recovery are written to `alert_event`, so the
  duration of an incident is queryable.
- **Delivery**: notifications leave the evaluation path on a dedicated thread,
  with SMTP timeouts set so a hung mail server cannot stall anything.
- **Retention**: points older than the configured window are deleted in bounded
  batches.

## Architecture

```
  agent / seed script
          │  POST /api/metrics/batch
          ▼
  ┌───────────────────────┐        ┌──────────────────────────┐
  │  MetricController     │───────▶│  metric_point (Postgres) │
  └───────────────────────┘        └────────────┬─────────────┘
                                                │ latest / baseline
  ┌───────────────────────┐   every 30s         ▼
  │  AlertEvaluator       │◀───────  ┌────────────────────┐
  │  (scheduled)          │          │  AlertStateMachine │  pure decision logic
  └───────┬───────────────┘          └────────────────────┘
          │ state + event
          ▼
  ┌───────────────────────┐        ┌──────────────────────────┐
  │  AlertStore           │───────▶│ alert_state, alert_event │
  └───────────────────────┘        └──────────────────────────┘
          │ on firing / resolved
          ▼
  ┌───────────────────────┐
  │  AlertNotifier        │  single background thread, SMTP timeouts
  └───────────────────────┘
```

## Dashboard

A Vue 3 + ECharts single-page dashboard lives in `frontend/`. It polls the API;
there is no push channel, because the backend's own refresh cadence is measured
in seconds and a socket would add lifecycle handling for no visible benefit.

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173, proxies /api to 127.0.0.1:8080
```

Start the backend first. What the page shows:

| Region | Source | Notes |
| --- | --- | --- |
| Line chart | `GET /api/metrics/query` | average plus the bucket's max/min envelope |
| Time range | local | 1h / 6h / 24h / 7d; the bucket width comes from the backend, chosen from the span |
| Alert rules | `GET /api/alerts/status` | `ok` / `pending` / `firing`, consecutive count, last value |
| Alert history | `GET /api/alerts/events` | firing and resolution, so an incident's duration is visible |
| Write path | `GET /api/metrics/ingestion` | which route served the last batch, plus the threshold in force |

The write-path card is what makes the ingestion work visible instead of buried in
configuration: send a 3-point batch and it reads "批量 INSERT", send 1,000 points
and it reads "COPY FROM STDIN".

Production build:

```bash
npm run build        # emits frontend/dist
```

ECharts is imported per component (`echarts/core` plus only the chart, axis, and
renderer modules this page uses) rather than from the package index. The full
import cost 1,113 kB against 595 kB tree-shaken, 374 kB gzipped against 204 kB.

## Host agent

`agent/agent.py` samples this machine with psutil and pushes the readings to the
platform, which is what makes the dashboard a view of a real host rather than a
replay of generated data.

```bash
pip install psutil
python agent/agent.py --once          # collect two readings and print them
python agent/agent.py                 # collect every 10s against localhost:8080
python agent/agent.py --url http://host:8080 --interval 5 --host web-01
python agent/agent.py --api-key "$METRICS_API_KEY"   # against a remote platform
```

A platform running on the same host accepts ingestion from loopback without a
key, so the local agent needs no configuration. A platform anywhere else
requires `--api-key`, or `METRICS_API_KEY` in the environment. If the key is
wrong the agent logs the rejection at ERROR rather than filing it under routine
send failures, because an agent that quietly stops reporting leaves the
dashboard green while the host goes unmonitored.

Apply the rules that match what it emits:

```bash
psql -U metrics -h 127.0.0.1 -d metrics -f agent/rules.sql
```

Metrics reported: `cpu.usage`, `cpu.load.1m_per_core`, `mem.usage`,
`mem.available.bytes`, `disk.used.percent`, `disk.free.bytes`,
`disk.read.bytes_per_sec`, `disk.write.bytes_per_sec`, `disk.busy.percent`,
`net.sent.bytes_per_sec`, `net.recv.bytes_per_sec`. Disk metrics carry a `mount`
label, so one rule covers every volume.

Sampling and reporting run on separate threads with a bounded queue between
them, so a slow platform cannot change the sampling cadence and an outage
degrades into dropped samples rather than unbounded memory. Every rate is
computed from `time.monotonic` deltas rather than the nominal interval. Details
and limitations: [agent/README.md](agent/README.md).

## Requirements

- JDK 21
- Node 20 or newer for the dashboard
- Python 3.10 or newer plus psutil for the host agent
- PostgreSQL 16 or newer, reachable as `metrics` / `metrics`
- Maven 3.9+ (or use the bundled wrapper once generated)

## Database setup

The schema is applied automatically on startup from
`src/main/resources/schema.sql`. To create the role and database first:

```sql
CREATE USER metrics WITH PASSWORD 'metrics';
CREATE DATABASE metrics OWNER metrics;
```

## Run

```bash
export DASHBOARD_PASSWORD='pick-something'   # optional; generated at startup if unset
export METRICS_API_KEY='pick-something'      # optional; unset accepts loopback writes only
export ALERT_MAIL_TO=you@example.com         # optional; empty disables mail delivery
mvn spring-boot:run
```

Override the datasource with `DB_URL`, `DB_USER`, and `DB_PASSWORD`.

The dashboard asks for `DASHBOARD_USER` (default `dashboard`) and
`DASHBOARD_PASSWORD` on first load. When `DASHBOARD_PASSWORD` is unset the
platform generates one, logs it once at WARN, and uses it — so an unconfigured
instance is reachable only by someone who can read its log, never by nobody.

## Verify

Reads need the operator credential and writes need the agent key.

```bash
# store one point (agent credential)
curl -s -X POST localhost:8080/api/metrics/batch \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: $METRICS_API_KEY" \
  -d '{"points":[{"name":"cpu.usage","ts":"2026-09-23T10:00:00Z","value":42.5,"tags":{"host":"h1"}}]}'
# -> {"accepted":1}

# generate a day of data with two scripted anomalies
python scripts/seed.py --minutes 120

# aggregate (bucket width chosen automatically)
curl -s -u "dashboard:$DASHBOARD_PASSWORD" "localhost:8080/api/metrics/query?name=http.latency.p95\
&from=2026-09-23T00:00:00Z&to=2026-09-23T23:59:59Z" | head -c 400

# alert state and history
curl -s -u "dashboard:$DASHBOARD_PASSWORD" localhost:8080/api/alerts/status
curl -s -u "dashboard:$DASHBOARD_PASSWORD" localhost:8080/api/alerts/events
```

`tools/check_e2e.py` runs all of this against a live instance, including the
rejections, and reports each check as PASS, FAIL or SKIP:

```bash
python tools/check_e2e.py --dashboard-password "$DASHBOARD_PASSWORD" --api-key "$METRICS_API_KEY"
```

It writes 603 probe points under `e2e.check` and leaves them in the database, so
run it against a development instance rather than one you care about.

## API

Every path below requires the operator credential except `/actuator/health`,
which is open so a load balancer can probe it.

| Method | Path | Credential | Purpose |
| --- | --- | --- | --- |
| POST | `/api/metrics/batch` | agent key | store up to 1000 points |
| GET | `/api/metrics/query` | operator | aggregate one metric over a range |
| GET | `/api/metrics/names` | operator | list metrics with point counts |
| GET | `/api/metrics/count` | operator | total stored points |
| GET | `/api/metrics/ingestion` | operator | last write path taken, threshold, and override |
| GET | `/api/alerts/status` | operator | current state of every rule |
| GET | `/api/alerts/events` | operator | alert history, newest first |
| POST | `/api/alerts/rules` | operator | create a rule |
| POST | `/api/alerts/rules/{id}/enabled` | operator | enable or disable a rule |
| POST | `/api/alerts/rules/{id}/evaluate` | operator | evaluate one rule now |
| DELETE | `/api/alerts/rules/{id}` | operator | delete a rule |
| GET | `/actuator/health` | none | liveness for a load balancer |

## Design decisions

**Why `JdbcTemplate` instead of JPA.** Every read is an aggregate over a time
bucket. An ORM would map entities the queries never materialise, and the
bucketing expression (`floor(epoch / width) * width`) has no ORM equivalent.

**Why the bucket width comes from the span.** A fixed width serves one zoom
level only. Choosing from the span keeps the response bounded at roughly the
same number of buckets regardless of the range, which is what makes the wide
queries fast.

**Why the alert state machine lives in the application, not in SQL.** The
transitions depend on the previous state, the elapsed cooldown, and the
consecutive count. Expressing that in a single SQL statement is possible but
unreadable, and the decision logic is the part worth testing directly. It is a
pure function over records, so `AlertStateMachineTest` covers the whole table
without a database.

**Why the dashboard uses Basic auth and the collector uses a header.** Two
callers with different constraints: a browser session, and a machine that pushes
on a timer. The browser gets `Authorization: Basic`, because anything the
dashboard holds is visible to whoever opens it — a key compiled into the frontend
is a published key. The collector gets `X-API-Key`, a static credential on a host
the operator already controls.

Both are request headers, and neither establishes a session, so there is no
cookie for a cross-site request to ride on. That is why CSRF protection is
disabled rather than merely tolerated: with no ambient credential there is
nothing for it to protect. Turning it off is the conclusion, not a shortcut.

**Why the two credentials are not interchangeable.** The batch endpoint requires
`ROLE_AGENT` and everything else requires `ROLE_OPERATOR`, so a leaked dashboard
password cannot forge metrics and a leaked collector key cannot read the
dashboard or edit alert rules. `tools/check_e2e.py` asserts both directions —
401 with no key, 403 with the operator credential — instead of only the happy
path.

**Why keys are compared in constant time, and why an unset key is not an open
door.** A byte-by-byte comparison leaks the matching prefix length to anyone who
can time the response; `MessageDigest.isEqual` closes that. With no key
configured the platform accepts ingestion only from a loopback address, which
keeps the local agent zero-configuration while leaving a remote attacker nothing
to forge. An unset dashboard password is generated at startup and logged once, so
"no configuration" never means "no authentication".

**Why not JWT or OAuth2.** JWT solves stateless verification across services that
do not share a session store; this is one process, so it would add signing keys,
expiry and revocation to solve a problem it does not have. OAuth2 fits corporate
SSO, and its client-credentials flow is a renamed static secret, so it does not
even help the collector. Both are defensible in a system with those constraints;
neither is here.

**Why consecutive breaches and cooldown are separate knobs.**
`minConsecutive` filters transient spikes before anything is sent. `cooldown`
suppresses repeats while the metric keeps breaching. A metric that stays
breaching remains in `firing`; the cooldown only controls notification, which is
why an ongoing incident's duration stays visible in the stored state.

**Why notifications are not retried.** A retry queue needs durability, ordering,
and a dead-letter story. At this alert volume the failure mode of a dropped
notification is acceptable and the failure is logged. Adding the queue is the
first item under deferred work.

**Why ingestion routes by batch size instead of always using COPY.** The
benchmark shows COPY is slower below roughly a hundred rows per call, because
its fixed per-call protocol setup outweighs the per-row parser and planner work
it avoids. A single deployment-wide choice would therefore be wrong for one of
the two regimes; the selection is per request and the threshold comes from the
measurement.

**Why `CopyMetricStore` is not `@Transactional`.** It borrows its own connection,
so an outer transaction would not cover the COPY stream and the annotation would
only mislead a reader. One `COPY` is atomic by itself; there is no partial-batch
rollback and no `ON CONFLICT` or `RETURNING` on this path.

**Why statistics run in PostgreSQL.** The baseline needs an aggregate over a
window. Pulling raw points into the JVM to average them would move the entire
window over the wire on every evaluation.

**Why detection is not a separate service.** Anomaly scoring is one aggregate
inside the database:

```sql
SELECT avg(value), stddev_pop(value), count(*) FROM metric_point
WHERE metric_name = ? AND ts >= now() - (? * INTERVAL '1 second')
```

At the measured 0.112 ms per rule it returns three numbers. A detection process in
another runtime answering the same question over HTTP would compute the same
mean and standard deviation, then add a round trip, a second process to keep
running, a new failure mode, and a second owner for a decision the state machine
already owns. Same capability, strictly more that can break.

Extracting detection becomes worthwhile when it adds an analysis the database
cannot express or the current approach cannot perform:

- a seasonal baseline, so a metric with a daily shape stops producing false
  positives during its own peak. The measurement under limitations is what rules
  this out for now: `cpu.usage` and `mem.usage` alarm at 1.7x and 4.4x the
  stationary-Gaussian rate, with the window length barely changing the answer, so
  there is no measured error left for a seasonal model to remove on the two
  metrics a rule actually watches. A seasonal fit would also need several weeks
  of continuous history to validate against; the collector has been running for
  about a day, and validating against generated data would prove only that the
  generator is periodic.
- change-point detection or drift estimation, where the question is when a level
  shifted rather than whether one sample is far from a mean. This is the one gap
  the measurement does identify: no single upper bound describes a throughput
  metric whose `max / median` is five orders of magnitude, and both detectors
  tested raised roughly ten notifications per five hours on those metrics.
- cross-metric attribution, which needs labelled incidents that this deployment
  has not produced.

Those are the conditions to revisit. The current shortfall is stated under
limitations rather than worked around with machinery that cannot be shown to
help.

## Measured behaviour

Numbers below were produced on this machine (PostgreSQL 17.11, Windows) and are
re-measurable from the scripts in `scripts/` and the benchmark in
`src/main/java/com/example/metrics/bench/`. They describe this host, not the
design; re-measure before quoting them.

**Ingestion — write path comparison.** Same table, same 100,000 points, same
machine and driver; only the write path changes. 1 warm-up run, median of 7.

| rows per call | `batchUpdate` (rows/s) | `COPY FROM STDIN` (rows/s) | ratio |
| ---: | ---: | ---: | ---: |
| 1 | 7,605 | 5,102 | **0.67x** |
| 100 | 63,246 | 92,199 | 1.46x |
| 1,000 | 72,371 | 106,700 | 1.47x |
| 10,000 | 71,481 | **126,392** | **1.77x** |

Three things the table says that a single headline number would hide:

- **COPY loses below roughly 100 rows per call.** Each `copyIn` pays a fixed
  protocol setup cost, so at one row per call that cost is paid per row.
- **`batchUpdate` plateaus after 1,000 rows** (72,371 → 71,481). Past that point
  the bottleneck is server-side heap insert and WAL, which both paths pay, which
  is why the ceiling here is 1.7x rather than an order of magnitude.
- **1.77x is a localhost floor, not a general figure.** The machine is the same
  host, so round-trip latency is ~0. A remote database would widen the gap,
  since `batchUpdate` needs a round trip per batch and COPY needs one per stream.

A control run with `reWriteBatchedInserts=false` produced the same magnitude
(~75k vs ~121k best-case), confirming the driver's INSERT rewriting is not what
the comparison is measuring.

**Ingestion — the selection is live, not just measured.** `MetricController`
posts through `MetricIngestionService`, which routes each request by batch size
using the measured crossover:

| request rows | route | response |
| ---: | --- | --- |
| 1 / 50 / 99 | `batchUpdate` | `{"accepted":99,"route":"batch"}` |
| 100 / 1,000 | `COPY FROM STDIN` | `{"accepted":1000,"route":"copy"}` |

The route is reported in the response body so the choice is observable in
production rather than inferred from configuration. `metric-platform.ingestion
.force-path` pins one path (`batch` or `copy`) for benchmarking and for rolling
back a bad ingestion change without a code revert; verified by starting with
`--metric-platform.ingestion.force-path=batch` and observing `route":"batch"`
at 1,000 rows.

**Ingestion — end-to-end over HTTP.** 100 requests of 1,000 points (100,000
points, 80 KB payloads) through the API: **3.41 s, ~29,400 points/s**. This is
3-4x below the direct JDBC figure above because the timed path adds JSON
deserialization, bean validation, a synchronous HTTP client, and controller
dispatch. It is the number that describes what a real agent gets; the JDBC table
describes the write path in isolation.

Reproduce with:

```bash
mvn -q compile
mvn -q exec:java -Dexec.mainClass=com.example.metrics.bench.WritePathBenchmark \
    -Dexec.args="100000 7"
```

Full methodology and the interview-relevant trade-offs:
[docs/write-path-experiment.md](docs/write-path-experiment.md).

**Ingestion — server-side bulk upper bound.** 100,000 points loaded by one
`INSERT ... SELECT generate_series` in **848 ms** (~118k rows/s). This is the
server's own bulk path with no client involved, so it bounds what any client-side
path can reach on this hardware.

**Read amplification** — 23,768 stored points, 11 metrics, measured with
`EXPLAIN (ANALYZE)` on `cpu.usage`:

| query shape | rows the plan touches | rows returned | server time |
| --- | ---: | ---: | ---: |
| raw scan over 7 days | 3,048 | 3,048 | 1.14 ms |
| bucketed at 300 s over 24 h | 1,666 | 62 | 2.44 ms |

The win is the row count that reaches the client, not the milliseconds: the
bucketed query reads more rows in total (a wider window) while returning 62 of
them instead of 1,666. A chart renders 62 points, and the browser never sees the
other 1,604.

An earlier benchmark on a synthetic 100,000 point dataset showed the same effect
at a larger scale: 60,479 raw rows became 289 buckets. Those figures describe
that dataset, which the benchmark script rebuilds on demand and then drops. The
numbers above are the ones the current data reproduces.

**Index usage** — both plans above use `Bitmap Index Scan on
idx_metric_point_name_ts` and neither falls back to a sequential scan, so the
composite index is actually used rather than merely present.

**Statistical baseline** — mean and population standard deviation over a 300
second window (**29 samples**) in **0.112 ms** of server execution. At that cost
the baseline is recomputed on every evaluation pass rather than cached, and there
is no reason to move it out of the database. Note that a client-side `psql`
round trip for the same statement reports about 8 ms; that figure measures the
connection, not the query, which is why the plan's own execution time is the one
quoted here.

**Alert state machine** — the full lifecycle, observed through the HTTP API:

| step | input | resulting state | event |
| --- | --- | --- | --- |
| 1 | 95.0, first breach | `pending`, consecutive 1 | none |
| 2 | 96.0, second breach | `firing`, consecutive 2 | `FIRING` |
| 3 | 97.0, inside cooldown | `firing`, consecutive 3 | none |
| 4 | 30.0, recovered | `ok`, consecutive 0 | `RESOLVED` |
| 5 | 25.0, still normal | `ok`, consecutive 0 | none |

History after the run held exactly two rows, one `FIRING` and one `RESOLVED`.

## Known limitations and deferred work

- **Authentication is not encryption.** Basic credentials are base64, which is
  reversible, and the API key crosses the wire in the clear. Over plain HTTP both
  are readable by anyone on the path, so this is safe on a trusted network or
  behind TLS and nowhere else. The platform does not terminate TLS itself; put it
  behind a reverse proxy before it leaves the host.
- **Authenticated, not authorised per resource.** Every operator sees every
  metric and every rule. There are no per-tenant boundaries because the system
  holds one host's data, and inventing them would be structure without a user.
- **The three-sigma bound only holds for a stationary metric, and how badly it
  breaks is measurable.** `scripts/analyse_sigma_fpr.py` scores both detectors on
  the collector's own data: a fixed bound, and `mean + 3 sigma` over the preceding
  window, using only prior samples so the value being judged never enters its own
  baseline. A stationary Gaussian metric would alarm on 0.27% of samples.

  | metric | window giving the lowest rate | rate | incidents | vs Gaussian | fixed bound: incidents |
  | --- | --- | ---: | ---: | ---: | ---: |
  | `cpu.usage` | 30 min | 0.45% | 8 | 1.7x | 0 |
  | `mem.usage` | 30 min | 1.20% | 6 | 4.4x | 2 |
  | `disk.write.bytes_per_sec` | 60 min | 0.89% | 8 | 3.3x | 6 |
  | `disk.read.bytes_per_sec` | 60 min | 1.12% | 9 | 4.2x | 10 |
  | `net.recv.bytes_per_sec` | 60 min | 1.30% | 6 | 4.8x | 11 |
  | `net.sent.bytes_per_sec` | 30 min | 2.01% | 12 | 7.5x | 10 |

  Two conclusions follow, and neither is the one the limitation used to assert:

  - **For resource occupancy the assumption survives.** `cpu.usage` runs 1.7x the
    Gaussian rate and `mem.usage` 4.4x, and the window length changes the answer
    little. A statistical rule on these metrics is defensible at the traffic this
    host produces, which is what the shipped rules rely on.
  - **For throughput metrics no threshold works.** Every I/O and network metric
    sits at 4-8x the Gaussian rate, and the fixed absolute bound does no better
    (10, 11 and 10 incidents against the sigma detector's 9, 6 and 6). Reading
    alarms per hour: 1,689 ten-second buckets is 4.7 hours of data, so
    `net.recv` at 11 incidents is roughly two false alarms an hour. Neither bound
    is at fault; the distribution is not one a single threshold describes.

  Checking the burstiness explains the split. `max / median` is 2.5x for
  `cpu.usage` but 4,520x for `disk.read.bytes_per_sec` and 131,086x for
  `net.recv.bytes_per_sec`: throughput is idle most of the time and saturated
  briefly, so a trailing mean and standard deviation fitted during an idle
  stretch describe nothing about the burst that follows.

  `scripts/explain_sigma_fpr.py` attributes every alarm to a mechanism by
  reconstructing the bound that produced it and comparing that bound with the
  metric's own spread over the whole record. The two metric classes fail for
  different reasons, and the numbers make the difference plain:

  | metric | alarms | bound below the record's own std | median bound, as a fraction of that std |
  | --- | ---: | ---: | ---: |
  | `cpu.usage` (30 min) | 20 | 13 | 0.753 |
  | `net.recv.bytes_per_sec` (60 min) | 22 | **22** | **0.006** |

  For `cpu.usage` the detector is approximately right: the bound usually lands
  within the metric's normal spread, and the alarms cluster where a quiet
  half hour made the trailing window unrepresentative. A recorded example fired
  at 47.2% whose preceding window held a mean of 11.7 and a standard deviation of
  11.2, giving a bound of 45.2 — above the values in its own window, but well
  below the roughly 65% the metric reaches during a build. The sample was
  unremarkable for the host; the baseline was unrepresentative of it.

  For `net.recv.bytes_per_sec` the detector is not working at all. Every one of
  22 alarms had a bound below the record's standard deviation, and the median
  bound sat at 0.6% of it. One recorded alarm fired at 67 MB/s against a bound of
  115 kB/s, because the preceding 60 minutes held a mean of 3.2 kB/s: the metric
  had been idle for almost the whole window with a single modest upload in it.
  That detector was not distinguishing an anomaly from normal traffic; it was
  firing on traffic.

  The underlying point is that a trailing window answers "is this point unusual
  compared with the last N minutes", which is only the same question as "is this
  point unusual for this system" when the metric is stationary. Throughput
  violates that: bursts last seconds while idle stretches last minutes to hours,
  so a window either misses bursts entirely and reports an idle baseline, or
  contains one and takes its variance from that single event. Lengthening the
  window is not a fix — it trades a bound that is too low for one that adapts too
  slowly to a genuine level change. The two-state structure needs a detector with
  two states, not one mean and one standard deviation.

  The practical consequence, and the reason no throughput rule is shipped:
  adding an I/O or network rule with either detector buys roughly ten notifications
  per five hours with no evidence that any of them is a fault. The data contains
  no labelled incidents, so this measures false alarms only; it cannot show what
  either detector would catch.

  Reproduce with `python scripts/analyse_sigma_fpr.py`, which also writes the
  full result as JSON.

- **Single-writer ingestion.** No write batching daemon, no queue. Sustained
  ingestion above what one JDBC connection achieves would need an external
  buffer; the COPY path raises the ceiling but does not remove it.
- **The COPY payload is built fully in memory before it is sent.** A request of
  1,000 points is small enough that this is invisible, but the streaming API
  (`copyIn` with a `Reader` fed incrementally) is what would make a much larger
  batch safe, and the benchmark's COPY figure is conservative because of it.
- **Notification delivery is best-effort.** No retry, no dead-letter, no
  per-channel fan-out. Only mail plus the logged transition.
- **The mail health indicator reports `DOWN` when no SMTP server is reachable,**
  which makes `/actuator/health` return 503 even though the API is healthy. Run
  with `management.health.mail.enabled=false` when mail is not configured.
- **The staleness guard is global, not per rule.** `max-point-age-seconds`
  applies to every metric, so a metric that is legitimately slow-moving (a daily
  counter) needs a larger value or it is skipped.
- **The query response does not report the bucket width it chose.** A client
  cannot label the axis without re-deriving the tier from the span.
- **No tag-based rule scoping.** Rules match a metric name only, so a rule
  cannot target `host=host-3` alone.
- **No downsampling at rest.** Old points stay at full resolution until they
  are deleted; a tiered table would be the next step.
- **No authentication.** The API assumes a trusted network. Credentials and
  authorization are not implemented.

## Layout

```
src/main/java/com/example/metrics/
├── config/        deployment settings, web configuration
├── domain/        records and enums: rule, state, event, evaluation
├── service/       state machine, evaluator, notifier, retention job
├── store/         JdbcTemplate repositories
└── web/           controllers and request/response records
scripts/seed.py    simulated data with scripted anomalies
```
