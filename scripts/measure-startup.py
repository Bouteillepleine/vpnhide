#!/usr/bin/env python3
"""Measure VPN Hide cold startup until the Dashboard is ready.

The script reports both Android's `am start -W` timing and the app-defined
`VpnHide-Startup event=dashboard_ready` marker. The latter is the primary
metric: it fires after the startup splash can be released and the Dashboard
content has reached a Compose frame.
"""

from __future__ import annotations

import argparse
import re
import statistics
import subprocess
import sys
import time
from dataclasses import dataclass


PACKAGE = "dev.okhsunrog.vpnhide"
ACTIVITY = f"{PACKAGE}/.MainActivity"
STARTUP_TAG = "VpnHide-Startup"


@dataclass(frozen=True)
class Sample:
    total_time_ms: int | None
    wait_time_ms: int | None
    dashboard_ready_ms: int


def adb(serial: str | None, *args: str, check: bool = True, timeout: float | None = None) -> subprocess.CompletedProcess[str]:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    return subprocess.run(cmd, check=check, capture_output=True, text=True, timeout=timeout)


def parse_am_time(output: str, key: str) -> int | None:
    match = re.search(rf"^{re.escape(key)}:\s*(\d+)$", output, re.MULTILINE)
    return int(match.group(1)) if match else None


def read_dashboard_ready(serial: str | None) -> int | None:
    out = adb(serial, "logcat", "-d", "-v", "brief", "-s", f"{STARTUP_TAG}:I", "*:S").stdout
    matches = re.findall(r"event=dashboard_ready elapsedMs=(\d+)", out)
    return int(matches[-1]) if matches else None


def percentile(values: list[int], pct: float) -> int:
    if not values:
        raise ValueError("empty values")
    ordered = sorted(values)
    index = min(len(ordered) - 1, round((len(ordered) - 1) * pct))
    return ordered[index]


def measure_one(serial: str | None, timeout_sec: float, cold_delay_sec: float) -> Sample:
    adb(serial, "logcat", "-c")
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    time.sleep(cold_delay_sec)

    am = adb(serial, "shell", "am", "start", "-W", "-n", ACTIVITY, timeout=timeout_sec)
    deadline = time.monotonic() + timeout_sec
    dashboard_ready_ms: int | None = None
    while time.monotonic() < deadline:
        dashboard_ready_ms = read_dashboard_ready(serial)
        if dashboard_ready_ms is not None:
            break
        time.sleep(0.1)

    if dashboard_ready_ms is None:
        raise TimeoutError(f"did not see {STARTUP_TAG} dashboard_ready marker within {timeout_sec:.1f}s")

    return Sample(
        total_time_ms=parse_am_time(am.stdout, "TotalTime"),
        wait_time_ms=parse_am_time(am.stdout, "WaitTime"),
        dashboard_ready_ms=dashboard_ready_ms,
    )


def print_summary(name: str, values: list[int]) -> None:
    print(
        f"{name}: median={round(statistics.median(values))}ms "
        f"p90={percentile(values, 0.90)}ms min={min(values)}ms max={max(values)}ms"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-s", "--serial", help="adb device serial")
    parser.add_argument("-n", "--runs", type=int, default=10, help="number of cold starts")
    parser.add_argument("--timeout", type=float, default=20.0, help="seconds to wait for each launch")
    parser.add_argument("--cold-delay", type=float, default=1.0, help="seconds after force-stop before launching")
    args = parser.parse_args()

    if args.runs <= 0:
        parser.error("--runs must be positive")

    adb(args.serial, "start-server")
    samples: list[Sample] = []
    for idx in range(1, args.runs + 1):
        sample = measure_one(args.serial, args.timeout, args.cold_delay)
        samples.append(sample)
        print(
            f"run {idx}: dashboardReady={sample.dashboard_ready_ms}ms "
            f"amTotal={sample.total_time_ms if sample.total_time_ms is not None else '?'}ms "
            f"amWait={sample.wait_time_ms if sample.wait_time_ms is not None else '?'}ms"
        )
        sys.stdout.flush()

    dashboard = [sample.dashboard_ready_ms for sample in samples]
    print_summary("dashboardReady", dashboard)

    total = [sample.total_time_ms for sample in samples if sample.total_time_ms is not None]
    if total:
        print_summary("amTotal", total)

    wait = [sample.wait_time_ms for sample in samples if sample.wait_time_ms is not None]
    if wait:
        print_summary("amWait", wait)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
