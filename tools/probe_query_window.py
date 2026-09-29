#!/usr/bin/env python3
"""Probe the /api/metrics/query time-window semantics.

Written to settle one question: does the endpoint treat `from` and `to` as
inclusive or exclusive bounds? The e2e suite reported an empty result for a
window that should have contained the data, so this isolates the boundary
behaviour rather than guessing at it.

Usage:
    python tools/probe_query_window.py
"""

from __future__ import annotations

import argparse
import json
import urllib.error
import urllib.request


def post(base: str, timestamp: str, name: str = "e2e.check", value: float = 2.0) -> dict:
    payload = json.dumps({
        "points": [{"name": name, "ts": timestamp, "value": value,
                    "tags": {"host": "probe"}}]
    }).encode("utf-8")
    request = urllib.request.Request(
        f"{base}/api/metrics/batch", data=payload,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode("utf-8"))


def query(base: str, name: str, frm: str, to: str, extra: str = "") -> tuple[int, object]:
    url = f"{base}/api/metrics/query?name={name}&from={frm}&to={to}{extra}"
    try:
        with urllib.request.urlopen(url, timeout=20) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as failure:
        return failure.code, failure.read().decode("utf-8", "replace")[:200]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--anchor", default="2026-09-29T09:20:00Z",
                        help="timestamp the probe writes and then queries around")
    args = parser.parse_args()
    base = args.base.rstrip("/")
    anchor = args.anchor
    name = "probe.window"

    print(f"writing one point at {anchor} under metric {name}\n")
    print("  response:", post(base, anchor, name))

    cases = [
        ("window contains the point",      "2026-09-29T09:00:00Z", "2026-09-29T10:00:00Z"),
        ("point is the lower bound",       anchor,                 "2026-09-29T10:00:00Z"),
        ("point is the upper bound",       "2026-09-29T09:00:00Z", anchor),
        ("window ends 1s before point",    "2026-09-29T09:00:00Z", "2026-09-29T09:19:59Z"),
        ("window starts 1s after point",   "2026-09-29T09:20:01Z", "2026-09-29T10:00:00Z"),
        ("no timezone designator",         "2026-09-29T09:00:00",   "2026-09-29T10:00:00"),
        ("explicit milliseconds",          "2026-09-29T09:00:00.000Z", "2026-09-29T10:00:00.000Z"),
    ]

    print(f"\n  {'case':<32} {'HTTP':>5}  {'buckets':>8}  {'count':>7}")
    for label, frm, to in cases:
        code, body = query(base, name, frm, to)
        if isinstance(body, list):
            count = body[0]["count"] if body else "-"
            buckets = len(body)
        else:
            count, buckets = "-", "-"
        print(f"  {label:<32} {code:>5}  {str(buckets):>8}  {str(count):>7}")

    print("\nreading back the stored range to confirm what the database holds")
    code, body = query(base, name, "2026-09-01T00:00:00Z", "2026-10-01T00:00:00Z")
    if isinstance(body, list) and body:
        print(f"  {len(body)} bucket(s): {json.dumps(body[0])}")
    else:
        print(f"  HTTP {code} {body}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
