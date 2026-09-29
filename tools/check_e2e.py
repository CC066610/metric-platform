#!/usr/bin/env python3
"""End-to-end check of the running platform over its own HTTP API.

Reports each check as PASS, FAIL or SKIP. SKIP is for checks that need a
component which is not running (the SMTP capture server, for instance), so a
skipped check is never counted as a pass.

Usage:
    python tools/check_e2e.py
    python tools/check_e2e.py --base http://127.0.0.1:8080
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

RESULTS: list[tuple[str, str, str]] = []


def record(name: str, status: str, detail: str = "") -> None:
    RESULTS.append((name, status, detail))
    marker = {"PASS": "PASS", "FAIL": "FAIL", "SKIP": "SKIP"}[status]
    print(f"  [{marker}] {name}" + (f" — {detail}" if detail else ""))


def call(method: str, url: str, body: dict | None = None, timeout: int = 20):
    """Perform one request, returning (status_code, parsed_body_or_text)."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            try:
                return response.status, json.loads(raw)
            except json.JSONDecodeError:
                return response.status, raw
    except urllib.error.HTTPError as failure:
        raw = failure.read().decode("utf-8", "replace")
        try:
            return failure.code, json.loads(raw)
        except json.JSONDecodeError:
            return failure.code, raw
    except Exception as failure:  # noqa: BLE001 - report, do not raise
        return None, str(failure)


def iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    args = parser.parse_args(argv)
    base = args.base.rstrip("/")
    now = datetime.now(timezone.utc)

    print(f"target: {base}\n")

    print("read endpoints")
    code, body = call("GET", f"{base}/api/metrics/count")
    total_before = body.get("points") if isinstance(body, dict) else None
    if code == 200 and isinstance(total_before, int):
        record("GET /api/metrics/count", "PASS", f"{total_before} points")
    else:
        record("GET /api/metrics/count", "FAIL", f"HTTP {code} {body}")

    code, body = call("GET", f"{base}/api/metrics/names")
    if code == 200 and isinstance(body, list) and body:
        record("GET /api/metrics/names", "PASS", f"{len(body)} metrics")
    else:
        record("GET /api/metrics/names", "FAIL", f"HTTP {code} {body}")

    print("\nwrite path routing (threshold is 100 rows)")
    stamp = iso(now)
    for rows, expected in ((3, "batch"), (100, "copy"), (500, "copy")):
        points = [{"name": "e2e.check", "ts": stamp, "value": 1.0,
                   "tags": {"host": "e2e"}} for _ in range(rows)]
        code, body = call("POST", f"{base}/api/metrics/batch", {"points": points})
        got = body.get("route") if isinstance(body, dict) else None
        if code == 202 and got == expected and body.get("accepted") == rows:
            record(f"{rows} rows -> {expected}", "PASS", f"route={got} accepted={body['accepted']}")
        else:
            record(f"{rows} rows -> {expected}", "FAIL", f"HTTP {code} {body}")

    print("\nquery")
    # The endpoint treats the window as half-open ([from, to)), and the points
    # written just above use the same `now`, so an upper bound of `now` would
    # exclude them. A forward margin keeps this check independent of how long
    # the write requests took.
    span_from = iso(now - timedelta(hours=24))
    span_to = iso(now + timedelta(minutes=5))
    code, body = call("GET", f"{base}/api/metrics/query?name=e2e.check"
                             f"&from={span_from}&to={span_to}")
    if code == 200 and isinstance(body, list) and body:
        first = body[0]
        needed = {"bucket", "avg", "max", "min", "count"}
        if needed.issubset(first.keys()):
            record("query returns bucket shape", "PASS", f"{len(body)} buckets, keys ok")
        else:
            record("query returns bucket shape", "FAIL", f"keys {sorted(first.keys())}")
    else:
        record("query returns bucket shape", "FAIL", f"HTTP {code} {body}")

    code, body = call("GET", f"{base}/api/metrics/query?name=e2e.check"
                             f"&from={span_from}&to={span_to}&bucketSeconds=300")
    if code == 200 and isinstance(body, list) and body:
        record("query honours explicit bucketSeconds", "PASS", f"{len(body)} buckets at 300s")
    else:
        record("query honours explicit bucketSeconds", "FAIL", f"HTTP {code} {body}")

    code, body = call("GET", f"{base}/api/metrics/query?name=e2e.check"
                             f"&from={span_to}&to={span_from}")
    if code in (400, 422) or (isinstance(body, dict) and body.get("error")):
        record("query rejects inverted range", "PASS", f"HTTP {code}")
    else:
        record("query rejects inverted range", "FAIL", f"HTTP {code} {body}")

    code, body = call("GET", f"{base}/api/metrics/query?name=does.not.exist"
                             f"&from={span_from}&to={span_to}")
    if code == 200 and isinstance(body, list) and body == []:
        record("query on unknown metric returns empty list", "PASS")
    else:
        record("query on unknown metric returns empty list", "FAIL", f"HTTP {code} {body}")

    print("\nvalidation")
    code, body = call("POST", f"{base}/api/metrics/batch", {"points": []})
    if code in (400, 422):
        record("empty batch rejected", "PASS", f"HTTP {code}")
    else:
        record("empty batch rejected", "FAIL", f"HTTP {code} {body}")

    code, body = call("POST", f"{base}/api/metrics/batch",
                      {"points": [{"name": "e2e.check", "ts": "not-a-timestamp",
                                   "value": 1.0, "tags": {}}]})
    if code in (400, 422):
        record("malformed timestamp rejected", "PASS", f"HTTP {code}")
    else:
        record("malformed timestamp rejected", "FAIL", f"HTTP {code} {body}")

    print("\nwrite path visibility")
    code, body = call("GET", f"{base}/api/metrics/ingestion")
    if code == 200 and isinstance(body, dict) and "route" in body:
        record("GET /api/metrics/ingestion", "PASS",
               f"route={body['route']} threshold={body.get('copyMinBatchSize')}")
    else:
        record("GET /api/metrics/ingestion", "FAIL", f"HTTP {code} {body}")

    print("\nalerts")
    code, body = call("GET", f"{base}/api/alerts/status")
    if code == 200 and isinstance(body, list):
        record("GET /api/alerts/status", "PASS", f"{len(body)} rules")
    else:
        record("GET /api/alerts/status", "FAIL", f"HTTP {code} {body}")

    code, body = call("GET", f"{base}/api/alerts/events?limit=5")
    if code == 200 and isinstance(body, list):
        record("GET /api/alerts/events", "PASS", f"{len(body)} events")
    else:
        record("GET /api/alerts/events", "FAIL", f"HTTP {code} {body}")

    code, body = call("POST", f"{base}/api/alerts/rules",
                      {"metricName": "e2e.check", "operator": "gt", "threshold": 1e12,
                       "sigmaMultiplier": None, "windowSeconds": 300,
                       "minConsecutive": 1, "cooldownSeconds": 300})
    if code in (200, 201) and isinstance(body, dict) and body.get("id"):
        rule_id = body["id"]
        record("create rule", "PASS", f"id={rule_id}")

        code, body = call("POST", f"{base}/api/alerts/rules/{rule_id}/evaluate")
        if code == 200:
            record("evaluate rule", "PASS", f"{str(body)[:80]}")
        else:
            record("evaluate rule", "FAIL", f"HTTP {code} {body}")

        # The enabled flag travels as a query parameter, not a request body.
        code, body = call("POST", f"{base}/api/alerts/rules/{rule_id}/enabled?enabled=false")
        record("disable rule", "PASS" if code == 200 else "FAIL", f"HTTP {code} {body}")

        code, body = call("DELETE", f"{base}/api/alerts/rules/{rule_id}")
        if code == 200:
            record("delete rule", "PASS")
        else:
            record("delete rule", "FAIL", f"HTTP {code} {body}")
    else:
        record("create rule", "FAIL", f"HTTP {code} {body}")

    print("\nhealth")
    code, body = call("GET", f"{base}/actuator/health")
    status = body.get("status") if isinstance(body, dict) else None
    if status == "UP":
        record("actuator health", "PASS", "UP")
    elif status == "DOWN":
        detail = json.dumps(body.get("components", {}).get("mail", {}))[:90]
        record("actuator health", "FAIL", f"DOWN — mail: {detail}")
    else:
        record("actuator health", "FAIL", f"HTTP {code} {body}")

    print("\ncleanup")
    code, body = call("GET", f"{base}/api/metrics/count")
    total_after = body.get("points") if isinstance(body, dict) else None
    if total_before is not None and total_after is not None:
        added = total_after - total_before
        record("count grew by the number written", "PASS" if added == 603 else "FAIL",
               f"before={total_before} after={total_after} delta={added} (expected 603)")

    passed = sum(1 for _, s, _ in RESULTS if s == "PASS")
    failed = sum(1 for _, s, _ in RESULTS if s == "FAIL")
    skipped = sum(1 for _, s, _ in RESULTS if s == "SKIP")
    print(f"\n{passed} passed, {failed} failed, {skipped} skipped")
    if failed:
        print("\nfailures:")
        for name, status, detail in RESULTS:
            if status == "FAIL":
                print(f"  - {name}: {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
