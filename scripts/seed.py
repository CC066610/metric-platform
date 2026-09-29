#!/usr/bin/env python3
"""Generate simulated metrics and push them to the platform.

Three shapes are produced on purpose, because they are what the alert rules are
evaluated against afterwards:

* a normal baseline for every metric,
* a short spike on ``http.latency.p95`` that must NOT notify anyone once the
  consecutive-breach rule is in place,
* a sustained excursion near the end of the run that MUST notify exactly once.

Usage:
    python seed.py                      # one day of data, ends ~now
    python seed.py --minutes 120        # shorter run
    python seed.py --api http://host:8080/api/metrics/batch
"""

from __future__ import annotations

import argparse
import json
import random
import time
import urllib.request
from datetime import datetime, timedelta, timezone

DEFAULT_API = "http://127.0.0.1:8080/api/metrics/batch"

# metric name -> (baseline, relative jitter)
BASELINES: dict[str, tuple[float, float]] = {
    "cpu.usage": (45.0, 0.12),
    "mem.usage": (62.0, 0.05),
    "http.latency.p95": (120.0, 0.15),
    "http.qps": (300.0, 0.10),
    "http.error.rate": (1.2, 0.30),
}

HOSTS = ("host-1", "host-2", "host-3")


def value_for(name: str, at: datetime, start: datetime, end: datetime) -> float:
    """Sample one value, injecting the two scripted anomalies."""
    base, jitter = BASELINES[name]
    total = max((end - start).total_seconds(), 1.0)
    progress = (at - start).total_seconds() / total

    if name == "http.latency.p95":
        # A short spike early in the run: fewer consecutive breaches than the
        # rule requires, so it must stay silent.
        if 0.20 <= progress < 0.21:
            return round(base * 4.5, 3)

        # A sustained excursion over the last few minutes: must fire once.
        if progress >= 0.97:
            return round(base * 3.2, 3)

    if name == "cpu.usage" and progress >= 0.97:
        return round(91.0, 3)

    return round(max(0.0, random.gauss(base, base * jitter)), 3)


def make_batch(at: datetime, start: datetime, end: datetime, size: int) -> dict:
    """Build one ingestion payload at a single simulated instant."""
    points = []
    for _ in range(size):
        name = random.choice(list(BASELINES))
        points.append({
            "name": name,
            "ts": at.isoformat(),
            "value": value_for(name, at, start, end),
            "tags": {"host": random.choice(HOSTS)},
        })
    return {"points": points}


def post(api: str, payload: dict) -> None:
    """Send one batch, raising on a non-2xx response."""
    request = urllib.request.Request(
        api,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        response.read()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", default=DEFAULT_API)
    parser.add_argument("--minutes", type=int, default=1440,
                        help="length of the simulated run in minutes")
    parser.add_argument("--batch", type=int, default=200,
                        help="points per second-step")
    parser.add_argument("--step-seconds", type=int, default=10,
                        help="simulated time advanced per request")
    args = parser.parse_args()

    end = datetime.now(timezone.utc).replace(microsecond=0)
    start = end - timedelta(minutes=args.minutes)

    simulated = start
    sent = 0
    batches = 0
    wall_start = time.perf_counter()

    while simulated <= end:
        post(args.api, make_batch(simulated, start, end, args.batch))
        sent += args.batch
        batches += 1
        simulated += timedelta(seconds=args.step_seconds)

    elapsed = time.perf_counter() - wall_start
    print(f"sent {sent} points in {batches} batches over {elapsed:.1f}s")
    print(f"window: {start.isoformat()} .. {end.isoformat()}")
    print(f"throughput: {sent / max(elapsed, 0.001):,.0f} points/s")


if __name__ == "__main__":
    main()
