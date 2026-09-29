#!/usr/bin/env python3
"""Measure the false-alarm rate of a rolling 3-sigma detector on real data.

Framing
-------
The platform's statistical rule compares each value against ``mean + k * stddev``
over a trailing window. Its documented weakness is the assumption that a metric
is roughly stationary: a burst that is normal for the host can exceed a bound
fitted during a quieter stretch.

This script quantifies that on the data the collector actually produced. The
data contains no labelled incidents, so no detector can be scored for missed
faults. What it does support is the reverse question, which is the one that
causes alert fatigue: **how often does each detector raise an alarm when the
preceding window gave it no reason to expect one?**

Two decisions make the result defensible:

* The baseline for each sample uses only the samples before it. A rolling window
  centred on the sample, or a window including it, would leak the value being
  judged and report a flattering rate.
* Alarms are counted as incidents (a run of alarm samples) as well as samples.
  An operator experiences incidents; a metric that alarms 12% of its samples in
  one unbroken run is one incident, not hundreds.

Usage:
    python analyse_sigma_fpr.py                    # every rate metric
    python analyse_sigma_fpr.py --metrics cpu.usage mem.usage
    python analyse_sigma_fpr.py --json out.json
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from math import erf, sqrt

import numpy as np

API = "http://127.0.0.1:8080"

# Metrics where a rolling statistical bound is plausible and where the platform
# could reasonably want a rule. Disk occupancy is excluded because it moves too
# slowly for a 3-sigma burst to mean anything at these window lengths.
DEFAULT_METRICS = [
    "cpu.usage",
    "mem.usage",
    "disk.read.bytes_per_sec",
    "disk.write.bytes_per_sec",
    "net.recv.bytes_per_sec",
    "net.sent.bytes_per_sec",
]

WINDOWS_MINUTES = (5, 15, 30, 60)

# Alarm samples closer together than this belong to the same incident, mirroring
# the cooldown the platform applies before notifying again.
INCIDENT_GAP_SAMPLES = 30


def two_sided_gaussian_rate(k: float) -> float:
    """Probability a standard normal value falls outside mean +- k sigma."""
    return 2.0 * (1.0 - 0.5 * (1.0 + erf(k / sqrt(2.0))))


def fetch_series(metric: str, days: int = 2, bucket_seconds: int = 10) -> list[dict]:
    """Read a metric at a fixed bucket width so every metric is comparable."""
    now = datetime.now(timezone.utc)
    frm = (now - timedelta(days=days)).strftime("%Y-%m-%dT%H:%M:%SZ")
    url = (f"{API}/api/metrics/query?name={urllib.parse.quote(metric)}"
           f"&from={frm}&to={now.strftime('%Y-%m-%dT%H:%M:%SZ')}"
           f"&bucketSeconds={bucket_seconds}")
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def count_incidents(flags: np.ndarray, gap: int = INCIDENT_GAP_SAMPLES) -> tuple[int, list[int]]:
    """Group alarm samples into incidents and return the count plus run lengths.

    A new incident starts when an alarm appears more than ``gap`` samples after
    the previous alarm, so a continuous excursion counts once.
    """
    positions = np.flatnonzero(flags)
    if positions.size == 0:
        return 0, []
    incidents = 1
    lengths = []
    run_start = positions[0]
    previous = positions[0]
    for position in positions[1:]:
        if position - previous > gap:
            lengths.append(int(previous - run_start + 1))
            incidents += 1
            run_start = position
        previous = position
    lengths.append(int(previous - run_start + 1))
    return incidents, lengths


def sigma_detector(values: np.ndarray, window: int, k: float) -> np.ndarray:
    """Alarm where a value exceeds mean + k sigma of the preceding ``window`` samples."""
    flags = np.zeros(values.size, dtype=bool)
    for index in range(window, values.size):
        history = values[index - window:index]      # strictly prior samples
        mean = history.mean()
        stddev = history.std()                       # population, matching stddev_pop
        if stddev <= 0:
            # A perfectly flat history gives no scale, so nothing is surprising.
            continue
        flags[index] = values[index] > mean + k * stddev
    return flags


def fixed_detector(values: np.ndarray, threshold: float) -> np.ndarray:
    """Alarm where a value exceeds an absolute threshold."""
    return values > threshold


def describe(values: np.ndarray) -> dict:
    """Summary statistics used to pick a comparable fixed threshold."""
    median = float(np.median(values))
    # Median absolute deviation scaled to a standard-deviation equivalent: a
    # robust spread that a burst of load does not inflate.
    mad = float(np.median(np.abs(values - median)))
    return {
        "count": int(values.size),
        "min": float(values.min()),
        "max": float(values.max()),
        "mean": float(values.mean()),
        "median": median,
        "p99": float(np.percentile(values, 99)),
        "robust_sigma": 1.4826 * mad,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metrics", nargs="*", default=DEFAULT_METRICS)
    parser.add_argument("--k", type=float, default=3.0, help="sigma multiplier")
    parser.add_argument("--gap", type=int, default=INCIDENT_GAP_SAMPLES,
                        help="samples of quiet that separate two incidents")
    parser.add_argument("--json", default=None,
                        help="also write the full result as JSON to this path")
    args = parser.parse_args(argv)

    theoretical = two_sided_gaussian_rate(args.k)
    print(f"rolling baseline: mean + {args.k} sigma over the preceding window")
    print(f"a stationary gaussian metric would alarm on {theoretical * 100:.3f}% of samples")
    print(f"incidents separated by more than {args.gap} quiet samples\n")

    report = {"k": args.k, "gap": args.gap, "theoretical_rate": theoretical, "metrics": {}}

    for metric in args.metrics:
        try:
            series = fetch_series(metric)
        except Exception as failure:  # noqa: BLE001 - one bad metric must not stop the run
            print(f"{metric}: query failed ({failure})")
            continue
        if len(series) < 100:
            print(f"{metric}: only {len(series)} buckets, skipped")
            continue

        values = np.array([bucket["avg"] for bucket in series], dtype=float)
        stats = describe(values)
        # A fixed threshold set from the robust spread, so the comparison is
        # between two detectors of similar sensitivity rather than two arbitrary
        # numbers.
        fixed_threshold = stats["median"] + args.k * stats["robust_sigma"]

        print(f"=== {metric} ===")
        print(f"  {stats['count']} buckets  min={stats['min']:,.1f}  median={stats['median']:,.1f}  "
              f"max={stats['max']:,.1f}  robust_sigma={stats['robust_sigma']:,.1f}")
        print(f"  fixed threshold (median + {args.k} robust sigma) = {fixed_threshold:,.1f}")

        entry = {"stats": stats, "fixed_threshold": fixed_threshold, "detectors": {}}

        fixed_flags = fixed_detector(values, fixed_threshold)
        fixed_incidents, fixed_lengths = count_incidents(fixed_flags, args.gap)
        fixed_rate = fixed_flags.mean() * 100.0
        print(f"  {'fixed':<14} alarms {fixed_flags.sum():>5} samples ({fixed_rate:>6.2f}%)  "
              f"{fixed_incidents:>4} incidents")
        entry["detectors"]["fixed"] = {
            "alarm_samples": int(fixed_flags.sum()),
            "alarm_rate_pct": float(fixed_rate),
            "incidents": fixed_incidents,
            "longest_incident_samples": max(fixed_lengths) if fixed_lengths else 0,
        }

        for minutes in WINDOWS_MINUTES:
            window = max(int(minutes * 60 / 10), 2)   # 10 second buckets
            flags = sigma_detector(values, window, args.k)
            incidents, lengths = count_incidents(flags, args.gap)
            rate = flags.mean() * 100.0
            ratio = rate / (theoretical * 100.0) if theoretical > 0 else float("nan")
            print(f"  {f'sigma {minutes:>3}m':<14} alarms {flags.sum():>5} samples ({rate:>6.2f}%)  "
                  f"{incidents:>4} incidents   {ratio:>6.1f}x gaussian")
            entry["detectors"][f"sigma_{minutes}m"] = {
                "window_samples": window,
                "alarm_samples": int(flags.sum()),
                "alarm_rate_pct": float(rate),
                "incidents": incidents,
                "longest_incident_samples": max(lengths) if lengths else 0,
                "ratio_to_gaussian": float(ratio),
            }
        print()
        report["metrics"][metric] = entry

    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2)
        print(f"wrote {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
