#!/usr/bin/env python3
"""Deterministic dashboard seed: 24 hours of one-minute samples per metric.

Shape is chosen so the dashboard and the alert rules both have something to
show:

* ``cpu.usage`` sits near 45 and rises above the 85 threshold for the last
  ~15 minutes, which is a sustained breach and must fire.
* ``http.latency.p95`` has a single one-point spike early on, which must NOT
  fire because the rule needs two consecutive breaches.
* ``http.error.rate`` stays low.

Usage:
    python seed_dashboard.py                  # 24h, one point per minute
    python seed_dashboard.py --minutes 180    # shorter, dense run
"""

from __future__ import annotations

import argparse
import json
import math
import random
import time
import urllib.request
from datetime import datetime, timedelta, timezone

API = "http://127.0.0.1:8080/api/metrics/batch"
BATCH = 1000

# metric name -> (baseline, jitter as a fraction of baseline)
PROFILES: dict[str, tuple[float, float]] = {
    "cpu.usage": (45.0, 0.10),
    "mem.usage": (62.0, 0.04),
    "http.latency.p95": (120.0, 0.12),
    "http.qps": (300.0, 0.08),
    "http.error.rate": (1.2, 0.25),
}

HOSTS = ("host-1", "host-2", "host-3")


def value_for(name: str, progress: float, rng: random.Random) -> float:
    """Sample one value; ``progress`` is 0 at the start of the run and 1 at the end."""
    base, jitter = PROFILES[name]

    if name == "cpu.usage":
        # Sustained excursion over the final 15 minutes: past the threshold for
        # far more than the two consecutive evaluations the rule requires.
        if progress >= 0.99:
            return round(88.0 + rng.random() * 6, 3)

    if name == "http.latency.p95":
        # One isolated spike. The rule needs two consecutive breaches, so this
        # must remain pending and never notify.
        if 0.35 <= progress < 0.3507:
            return round(base * 4.0, 3)

    # A daily-ish wave plus noise, so the chart is not a flat line.
    wave = math.sin(progress * math.pi * 4) * base * 0.06
    return round(max(0.0, base + wave + rng.gauss(0, base * jitter)), 3)


def build_points(minutes: int, step_seconds: int, rng: random.Random) -> list[dict]:
    """Points for every metric at a fixed cadence, oldest first.

    ``step_seconds`` should be smaller than the dashboard's narrowest bucket
    (60 seconds) so that a bucket holds several samples and its max, min, and
    average differ. At one sample per minute every bucket collapses to a single
    value and the chart's envelope band has zero width.
    """
    end = datetime.now(timezone.utc).replace(microsecond=0)
    steps = max(minutes * 60 // step_seconds, 1)
    start = end - timedelta(seconds=steps * step_seconds)
    total = max(steps, 1)

    points: list[dict] = []
    for step in range(steps + 1):
        at = start + timedelta(seconds=step * step_seconds)
        progress = step / total
        for name in PROFILES:
            points.append({
                "name": name,
                "ts": at.isoformat().replace("+00:00", "Z"),
                "value": value_for(name, progress, rng),
                "tags": {"host": rng.choice(HOSTS)},
            })
    return points


def post(points: list[dict]) -> dict:
    """Send one batch and return the parsed response, which reports the route."""
    payload = json.dumps({"points": points}).encode("utf-8")
    request = urllib.request.Request(
        API, data=payload, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> None:
    global API

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minutes", type=int, default=1440,
                        help="length of the run in minutes")
    parser.add_argument("--step-seconds", type=int, default=10,
                        help="sampling cadence; keep below the 60s narrowest bucket")
    parser.add_argument("--api", default=API)
    parser.add_argument("--seed", type=int, default=20260928)
    args = parser.parse_args()

    API = args.api

    rng = random.Random(args.seed)
    points = build_points(args.minutes, args.step_seconds, rng)
    print(f"generated {len(points):,} points spanning {args.minutes} minutes "
          f"at {args.step_seconds}s cadence")

    routes: dict[str, int] = {}
    started = time.perf_counter()
    for offset in range(0, len(points), BATCH):
        chunk = points[offset:offset + BATCH]
        result = post(chunk)
        routes[result["route"]] = routes.get(result["route"], 0) + result["accepted"]

    elapsed = time.perf_counter() - started
    total = sum(routes.values())
    print(f"stored {total:,} points in {elapsed:.2f}s ({total / max(elapsed, 0.001):,.0f} points/s)")
    for route, rows in sorted(routes.items()):
        print(f"  route {route}: {rows:,} rows")
    print(f"window: {points[0]['ts']} .. {points[-1]['ts']}")


if __name__ == "__main__":
    main()
