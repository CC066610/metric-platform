#!/usr/bin/env python3
"""Host metric collector.

Samples CPU, memory, disk, and network on this machine and pushes them to the
platform, which stores, charts, and alerts on them. This is the component that
turns the dashboard from simulated data into a view of a real host.

Design notes:

* Sampling and reporting are separate threads. A sampler fills a bounded queue
  at a fixed cadence; a reporter drains it in batches. A slow or failing
  platform therefore cannot change the sampling rate, and the queue bound turns
  an outage into dropped samples instead of unbounded memory growth.
* Every derived rate (bytes per second, disk busy percent) is computed from
  ``time.monotonic`` deltas rather than the nominal interval. A sleep overshoots,
  and dividing by the nominal interval would quietly skew every rate.
* Every metric here maps to an alert rule shipped in ``rules.sql``, so a fresh
  install produces data that is actually being judged rather than merely stored.
* No dependencies beyond psutil and the standard library, so the file can be
  copied onto a server and run directly.

Usage:
    python agent.py                              # localhost:8080, every 10s
    python agent.py --url http://host:8080 --interval 5 --verbose
    python agent.py --once                       # one sample, print it, send nothing
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import platform
import socket
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import Event, Lock, Thread

import psutil

LOG = logging.getLogger("agent")


@dataclass(frozen=True)
class Sample:
    """One observation ready for the platform."""

    name: str
    ts: str
    value: float
    tags: dict[str, str]


def default_host_label() -> str:
    """Best available machine label, lowercased.

    ``socket.gethostname()`` is tried last because on some Windows
    configurations it returns a single character rather than the machine name,
    and a one-character label is useless on a dashboard. When every source is
    unusable the label is derived from the machine's node identifier plus a
    short digest of the primary MAC address: stable across restarts, and
    distinct between machines that all report the same broken name.
    """
    for candidate in (os.environ.get("COMPUTERNAME"),
                      os.environ.get("HOSTNAME"),
                      platform.node(),
                      socket.gethostname()):
        if candidate and len(candidate.strip()) > 1:
            return candidate.strip().lower()

    seed = platform.node() or "host"
    mac = uuid.getnode()
    digest = hashlib.sha256(f"{seed}:{mac}".encode("utf-8")).hexdigest()[:6]
    return f"unknown-{digest}"


@dataclass
class Config:
    """Runtime settings."""

    url: str = "http://127.0.0.1:8080"
    interval: float = 10.0
    host: str = field(default_factory=default_host_label)
    batch_size: int = 500
    queue_size: int = 5000
    timeout: float = 10.0
    verbose: bool = False
    disk_mounts: list[str] | None = None
    # Sent as X-API-Key. Optional because a platform on this host accepts
    # loopback ingestion without one; a remote platform requires it.
    api_key: str | None = None


def now_iso() -> str:
    """Current UTC time as an ISO-8601 string with an explicit offset."""
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class HostSampler:
    """Turns psutil readings into metric samples.

    The sampler holds the previous counters so that counters whose meaning is a
    rate can be differenced. It neither sleeps nor retries: the caller owns the
    cadence.
    """

    def __init__(self, config: Config) -> None:
        self.config = config
        # The first cpu_percent call only establishes the baseline and returns
        # 0.0. Calling it here keeps that placeholder out of the stored data.
        psutil.cpu_percent(interval=None)
        self._last_net: tuple[float, int, int] | None = None
        self._last_disk: tuple[float, dict[str, tuple[int, int, int]]] | None = None

    def collect(self) -> list[Sample]:
        """Sample the host once.

        Every construction below names its fields. The dataclass has two
        string-typed members (name and ts) and two others, so a positional call
        that gets the order wrong still type-checks and silently stores a
        timestamp as a value; naming them makes that impossible.
        """
        at = now_iso()
        samples: list[Sample] = []
        tags = {"host": self.config.host}

        samples.append(Sample(name="cpu.usage", ts=at,
                              value=round(float(psutil.cpu_percent(interval=None)), 3),
                              tags=dict(tags)))

        load1, _, _ = psutil.getloadavg()
        cores = psutil.cpu_count(logical=True) or 1
        samples.append(Sample(name="cpu.load.1m_per_core", ts=at,
                              value=round(float(load1) / cores, 4), tags=dict(tags)))

        memory = psutil.virtual_memory()
        samples.append(Sample(name="mem.usage", ts=at,
                              value=round(float(memory.percent), 3), tags=dict(tags)))
        samples.append(Sample(name="mem.available.bytes", ts=at,
                              value=float(memory.available), tags=dict(tags)))

        samples.extend(self._disk_space(at))
        samples.extend(self._disk_throughput(at))
        samples.extend(self._network(at))
        return samples

    def _disk_space(self, at: str) -> list[Sample]:
        samples: list[Sample] = []
        for partition in psutil.disk_partitions(all=False):
            mount = partition.mountpoint
            if self.config.disk_mounts and mount not in self.config.disk_mounts:
                continue
            try:
                usage = psutil.disk_usage(mount)
            except (PermissionError, OSError):
                # Removable or system-reserved volumes raise here; skipping is
                # right because there is nothing to report about them.
                continue
            # The mount point is used verbatim as a label: a Windows drive
            # letter and a Linux path both work, and the label only has to be
            # stable for the rule's tag filter to keep matching.
            tags = {"host": self.config.host, "mount": mount}
            samples.append(Sample(name="disk.used.percent", ts=at,
                                  value=round(float(usage.percent), 3), tags=dict(tags)))
            samples.append(Sample(name="disk.free.bytes", ts=at,
                                  value=float(usage.free), tags=dict(tags)))
        return samples

    def _disk_throughput(self, at: str) -> list[Sample]:
        """Aggregate disk counters into host-wide busy percent and throughput."""
        try:
            counters = psutil.disk_io_counters(perdisk=True) or {}
        except (RuntimeError, OSError):
            return []
        if not counters:
            return []

        current = {
            name: (entry.read_bytes, entry.write_bytes, entry.read_time + entry.write_time)
            for name, entry in counters.items()
        }
        now = time.monotonic()
        samples: list[Sample] = []

        if self._last_disk is not None:
            last_at, previous = self._last_disk
            elapsed = max(now - last_at, 0.001)
            elapsed_ms = elapsed * 1000.0
            read_rate = write_rate = busy_ms = 0.0
            for name, (read_bytes, write_bytes, busy) in current.items():
                before = previous.get(name)
                if before is None:
                    continue
                read_rate += max(read_bytes - before[0], 0) / elapsed
                write_rate += max(write_bytes - before[1], 0) / elapsed
                busy_ms += max(busy - before[2], 0)
            tags = {"host": self.config.host}
            samples.append(Sample(name="disk.read.bytes_per_sec", ts=at,
                                  value=round(read_rate, 3), tags=dict(tags)))
            samples.append(Sample(name="disk.write.bytes_per_sec", ts=at,
                                  value=round(write_rate, 3), tags=dict(tags)))
            # busy_ms is summed across devices, so it is divided by the device
            # count: the result reads as "how busy was the average device",
            # which is comparable across machines with different disk counts.
            samples.append(Sample(
                name="disk.busy.percent",
                ts=at,
                value=round(min(busy_ms / elapsed_ms / len(current) * 100.0, 100.0), 3),
                tags=dict(tags)))

        self._last_disk = (now, current)
        return samples

    def _network(self, at: str) -> list[Sample]:
        counters = psutil.net_io_counters()
        now = time.monotonic()
        samples: list[Sample] = []
        if self._last_net is not None:
            last_at, last_sent, last_recv = self._last_net
            elapsed = max(now - last_at, 0.001)
            tags = {"host": self.config.host}
            samples.append(Sample(
                name="net.sent.bytes_per_sec", ts=at,
                value=round(max(counters.bytes_sent - last_sent, 0) / elapsed, 3),
                tags=dict(tags)))
            samples.append(Sample(
                name="net.recv.bytes_per_sec", ts=at,
                value=round(max(counters.bytes_recv - last_recv, 0) / elapsed, 3),
                tags=dict(tags)))
        self._last_net = (now, counters.bytes_sent, counters.bytes_recv)
        return samples


class Reporter:
    """Runs the sampling and reporting loops for one host."""

    def __init__(self, config: Config, sampler: HostSampler) -> None:
        self.config = config
        self.sampler = sampler
        self.queue: deque[Sample] = deque(maxlen=config.queue_size)
        self._lock = Lock()
        self._stop = Event()
        self._sent = 0
        self._dropped = 0
        self._threads: list[Thread] = []

    def start(self) -> None:
        """Start both loops."""
        self._threads = [
            Thread(target=self._sample_loop, name="sampler", daemon=True),
            Thread(target=self._report_loop, name="reporter", daemon=True),
        ]
        for thread in self._threads:
            thread.start()

    def stop(self) -> None:
        """Ask both loops to finish and wait briefly for them."""
        self._stop.set()
        for thread in self._threads:
            thread.join(timeout=2.0)

    def run_forever(self) -> None:
        """Run until interrupted."""
        self.start()
        try:
            while not self._stop.is_set():
                time.sleep(0.5)
        except KeyboardInterrupt:
            LOG.info("interrupted, flushing")
        finally:
            self.stop()
        LOG.info("sent %d samples, dropped %d", self._sent, self._dropped)

    @property
    def stats(self) -> tuple[int, int]:
        """Sent and dropped sample counts."""
        return self._sent, self._dropped

    def _sample_loop(self) -> None:
        while not self._stop.is_set():
            started = time.monotonic()
            try:
                samples = self.sampler.collect()
            except Exception:  # noqa: BLE001 - one bad reading must not kill the loop
                LOG.exception("sampling failed")
                samples = []

            with self._lock:
                for sample in samples:
                    if len(self.queue) == self.queue.maxlen:
                        self._dropped += 1
                    self.queue.append(sample)

            # Subtract the time collection took so the cadence does not drift.
            self._stop.wait(max(self.config.interval - (time.monotonic() - started), 0.0))

    def _report_loop(self) -> None:
        backoff = 1.0
        while not self._stop.is_set():
            self._stop.wait(self.config.interval)
            batch = self._drain()
            if not batch:
                continue
            try:
                self._sent += self._send(batch)
                backoff = 1.0
            except Exception as failure:  # noqa: BLE001 - transient network faults
                # Dropping is deliberate: a retry queue needs durability and
                # ordering, which this collector does not need at one batch per
                # interval. The loss is counted and logged.
                self._dropped += len(batch)
                if isinstance(failure, urllib.error.HTTPError) and failure.code in (401, 403):
                    # Rejection is not transient: every retry fails the same way,
                    # so it must not be filed under routine send failures. An
                    # agent that quietly stops reporting is worse than one that
                    # never started, because the dashboards stay green.
                    LOG.error(
                        "platform rejected the credential (%s): set --api-key or "
                        "METRICS_API_KEY. %d samples dropped and this host is NOT "
                        "being monitored.",
                        failure.code, len(batch))
                else:
                    LOG.warning("send failed (%s), %d samples dropped, backoff %.0fs",
                                failure, len(batch), backoff)
                self._stop.wait(backoff)
                backoff = min(backoff * 2, 60.0)

    def _drain(self) -> list[Sample]:
        with self._lock:
            take = min(len(self.queue), self.config.batch_size)
            return [self.queue.popleft() for _ in range(take)]

    def _send(self, batch: list[Sample]) -> int:
        body = json.dumps({
            "points": [
                {"name": s.name, "ts": s.ts, "value": s.value, "tags": s.tags}
                for s in batch
            ]
        }).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.config.api_key:
            headers["X-API-Key"] = self.config.api_key
        request = urllib.request.Request(
            f"{self.config.url.rstrip('/')}/api/metrics/batch",
            data=body,
            headers=headers,
        )
        with urllib.request.urlopen(request, timeout=self.config.timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
        if self.config.verbose:
            LOG.info("accepted %s via %s", payload.get("accepted"), payload.get("route"))
        return int(payload.get("accepted", 0))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Host metric collector")
    parser.add_argument("--url", default="http://127.0.0.1:8080", help="platform base URL")
    parser.add_argument("--interval", type=float, default=10.0,
                        help="seconds between samples and between report attempts")
    parser.add_argument("--host", default=None, help="host label; defaults to the machine name")
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--queue-size", type=int, default=5000,
                        help="buffered samples before the oldest are dropped")
    parser.add_argument("--disk-mount", action="append", dest="disk_mounts",
                        help="restrict disk metrics to this mount point; repeatable")
    parser.add_argument("--once", action="store_true",
                        help="collect two readings, print them, send nothing")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--api-key", default=os.environ.get("METRICS_API_KEY"),
                        help="sent as X-API-Key; defaults to $METRICS_API_KEY. Only "
                             "needed when the platform is not on this host.")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
    )

    config = Config(
        url=args.url,
        interval=args.interval,
        host=(args.host or default_host_label()),
        batch_size=args.batch_size,
        queue_size=args.queue_size,
        verbose=args.verbose,
        disk_mounts=args.disk_mounts,
        api_key=args.api_key,
    )

    sampler = HostSampler(config)

    if args.once:
        # Rate metrics need two readings, so take one, wait, take another. The
        # output is then exactly the set a running agent would report.
        sampler.collect()
        time.sleep(1.0)
        for sample in sampler.collect():
            print(f"{sample.name:<30} {float(sample.value):>16,.3f}  {sample.tags}")
        return 0

    LOG.info("collecting host=%s every %.1fs -> %s", config.host, config.interval, config.url)
    Reporter(config, sampler).run_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
