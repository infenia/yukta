#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Reads the stream-<n>.out files of perf/run.sh notifications: each line is `<arrival epoch seconds> data:<json>`.

Prints how many streams got events, how many got a reset, and the delay between a change (the notification's
updatedAt) and its arrival at a client. The creation of the open questions (setup, all at once) is reported
apart from the changes made while the test runs (answers and updates): only the second counts. Exits 1 when a
stream got nothing, a stream got a reset, or the p95 lag of the test's changes is above LAG_P95_MS (default 1000).
usage: stream-lag.py <dir> <streams>
"""
import glob
import json
import os
import sys
from datetime import datetime

directory, streams = sys.argv[1], int(sys.argv[2])
limit = float(os.environ.get("LAG_P95_MS", "1000"))
got = resets = 0
lags = []  # changes made by the test itself
setup_lags = []  # the open questions being created before it
for path in glob.glob(os.path.join(directory, "stream-*.out")):
    events = 0
    for line in open(path, errors="replace"):
        arrived, _, rest = line.partition(" ")
        if not rest.startswith("data:"):
            continue
        try:
            data = json.loads(rest[5:])
            arrived = float(arrived)
        except ValueError:
            continue
        events += 1
        if data.get("type") == "reset":
            resets += 1
        n = data.get("notification")
        if n:
            changed = datetime.fromisoformat(n["updatedAt"].replace("Z", "+00:00")).timestamp()
            (setup_lags if data.get("type") == "created" else lags).append(max(0.0, (arrived - changed) * 1000))
    got += events > 0
lags.sort()
setup_lags.sort()
pct = lambda p, v=None: (v if v is not None else lags)[min(len(v if v is not None else lags) - 1, int(len(v if v is not None else lags) * p))] if (v if v is not None else lags) else float("nan")
print(f"streams: {got} of {streams} received events, {resets} resets")
print(f"stream_lag_setup_ms: events={len(setup_lags)} p50={pct(.5, setup_lags):.0f} p95={pct(.95, setup_lags):.0f} max={setup_lags[-1] if setup_lags else float('nan'):.0f} (informative)")
print(f"stream_lag_ms: events={len(lags)} p50={pct(.5):.0f} p95={pct(.95):.0f} p99={pct(.99):.0f} max={lags[-1] if lags else float('nan'):.0f} (limit p95 {limit:.0f})")
sys.exit(0 if got == streams and resets == 0 and lags and pct(.95) <= limit else 1)
