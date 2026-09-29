#!/usr/bin/env python3
"""Explain why a rolling sigma detector raises false alarms, from the data.

The companion script (analyse_sigma_fpr.py) reports how often the detector is
wrong. This one explains the mechanism behind each wrong alarm, because "the
metric is bursty" is a description rather than a cause.

For every alarm the script reconstructs the state the detector was in: the mean
and standard deviation of the preceding window, the resulting bound, and what
the metric normally reaches. That separates two mechanisms that produce the same
symptom:

* A window that is too quiet. The trailing window happened to cover an idle
  stretch, so its standard deviation is small and the bound lands below values
  the metric reaches routinely. The alarm says nothing about the current sample;
  it says the baseline was unrepresentative.
* A window that is too slow. The metric stepped to a new level. The trailing
  window still contains mostly the old level, so the bound sits between the two
  and every sample of the new level alarms until the window fills with it. One
  level shift becomes a burst of alarms.

Usage:
    python explain_sigma_fpr.py --metric cpu.usage
    python explain_sigma_fpr.py --metric net.recv.bytes_per_sec --window 30
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime
from pathlib import Path

import numpy as np

# The analysis functions live beside this file; import them rather than
# duplicating the detector, so the explanation cannot drift from the measurement.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyse_sigma_fpr import (  # noqa: E402
    DEFAULT_METRICS,
    count_incidents,
    fetch_series,
    sigma_detector,
)


def baseline_trace(values: np.ndarray, window: int) -> tuple[np.ndarray, np.ndarray]:
    """Mean and standard deviation of the preceding window, per sample."""
    means = np.full(values.size, np.nan)
    stddevs = np.full(values.size, np.nan)
    for index in range(window, values.size):
        history = values[index - window:index]
        means[index] = history.mean()
        stddevs[index] = history.std()
    return means, stddevs


def classify(
    values: np.ndarray, index: int, means: np.ndarray, stddevs: np.ndarray,
    window: int, k: float, typical_scale: float,
) -> tuple[str, float]:
    """Attribute one alarm to a mechanism.

    A bound far below the metric's usual spread can only come from a window that
    was quieter than the metric generally is, which is the quiet-window case. A
    bound above it is consistent with the metric actually moving somewhere new,
    which is the level-shift case.
    """
    bound = means[index] + k * stddevs[index]
    ratio = bound / typical_scale if typical_scale > 0 else float("nan")
    # The value itself is also informative: a shift shows a sustained step whose
    # level is close to the observed samples, while a quiet-window alarm is a
    # single isolated sample far from its neighbours.
    neighbours = values[max(0, index - 3):index + 4]
    neighbours = neighbours[neighbours != values[index]] if neighbours.size > 1 else neighbours
    isolation = (
        abs(values[index] - np.median(neighbours)) / (np.std(neighbours) + 1e-9)
        if neighbours.size > 0 else float("inf")
    )
    if isolation > 3.0:
        return "isolated spike", ratio
    return "level shift", ratio


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metric", default="cpu.usage",
                        help=f"one of: {', '.join(DEFAULT_METRICS)}")
    parser.add_argument("--window", type=int, default=30,
                        help="baseline window in minutes")
    parser.add_argument("--k", type=float, default=3.0)
    parser.add_argument("--examples", type=int, default=2,
                        help="how many alarms to print in full")
    args = parser.parse_args(argv)

    series = fetch_series(args.metric)
    values = np.array([bucket["avg"] for bucket in series], dtype=float)
    window = max(int(args.window * 60 / 10), 2)

    means, stddevs = baseline_trace(values, window)
    flags = sigma_detector(values, window, args.k)
    positions = np.flatnonzero(flags)
    incidents, lengths = count_incidents(flags)

    # The metric's own spread over the whole record is the reference for "what
    # this metric normally does", independent of any window.
    typical_scale = float(np.std(values))

    print(f"metric          : {args.metric}")
    print(f"buckets         : {values.size} at 10 s each "
          f"({values.size * 10 / 3600:.1f} h)")
    print(f"detector        : mean + {args.k} sigma over the preceding {args.window} min")
    print(f"whole-record std: {typical_scale:,.4g}   "
          f"(this is what the metric usually does)")
    print(f"alarms          : {flags.sum()} samples in {incidents} incidents")
    print()

    if positions.size == 0:
        print("no alarms to explain")
        return 0

    # Attribute every alarm, not just the printed examples.
    buckets: dict[str, int] = {}
    ratios: list[float] = []
    for index in positions:
        kind, ratio = classify(values, index, means, stddevs, window, args.k, typical_scale)
        buckets[kind] = buckets.get(kind, 0) + 1
        ratios.append(ratio)
    print("mechanism counts (all alarms):")
    for kind, count in sorted(buckets.items(), key=lambda item: -item[1]):
        print(f"  {kind:<16} {count:>4} samples")
    print()
    print(f"bound as a fraction of the whole-record std:")
    print(f"  min  {min(ratios):.3f}")
    print(f"  median {float(np.median(ratios)):.3f}")
    print(f"  max  {max(ratios):.3f}")
    below = sum(1 for ratio in ratios if ratio < 1.0)
    print(f"  alarms whose bound was below 1.0 x the record std: {below} of {len(ratios)}")
    print()

    print(f"=== {min(args.examples, len(positions))} alarms in detail ===")
    for index in positions[:args.examples]:
        before = means[index]
        sigma = stddevs[index]
        bound = before + args.k * sigma
        kind, ratio = classify(values, index, means, stddevs, window, args.k, typical_scale)
        stamp = series[index]["bucket"]
        lo = max(0, index - window)
        print(f"\n[{stamp}]  classified: {kind}")
        print(f"  value                  {values[index]:,.4g}")
        print(f"  window mean            {before:,.4g}")
        print(f"  window std             {sigma:,.4g}   "
              f"(whole-record std is {typical_scale:,.4g})")
        print(f"  bound = mean + {args.k}*std {bound:,.4g}")
        # Computed separately: an escaped quote inside an f-string expression is
        # rejected by the parser on this Python version.
        hint = "   <- bound sat below the metric's usual spread" if ratio < 1 else ""
        print(f"  bound / record std     {ratio:.3f}{hint}")
        print(f"  window covered         {series[lo]['bucket']} .. {series[index - 1]['bucket']}")
        window_values = values[lo:index]
        print(f"  window  max / median   {window_values.max():,.4g} / {np.median(window_values):,.4g}")
        neighbour = values[max(0, index - 5):index + 6]
        print(f"  surrounding +-5 samples: "
              + " ".join(f"{v:,.4g}" for v in neighbour))
    return 0


if __name__ == "__main__":
    sys.exit(main())
