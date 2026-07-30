#!/usr/bin/env python3
"""T4-CE slice 1: evaluate the field-watch registry (scripts/watches.json) against a
preflight run_dir and print one result line per watch.

Usage: watch_report.py <run_dir-dir>

Results (type=watch):
  FIRED(n)               the fired pattern matched n times; the check note says what a
                         session should confirm by eye before closing the watch
  OPPORTUNITY-NO-FIRE(n) the opportunity arose n times but the mechanism never fired —
                         needs attention
  NO-OPPORTUNITY         neither pattern matched; the watch keeps riding
  NOT-OBSERVED           no opportunity pattern defined and no firing
Results (type=tally):
  COUNT(n)               a metric, not a pass/fail

Patterns are regexes over the RAW file text so both line-JSON and pretty-printed
run_dirs evaluate identically. Exit code is always 0 — the report informs, it never
gates ([[feedback-activity-not-progress]]: a watch firing is data, not a result on
the run).
"""

import json
import re
import sys
from pathlib import Path

FILE_MAP = {
    "behaviour": "behaviour.ndjson",
    "applog": "autobuilder.log",
    "goalstack": "GoalStack.json",
    "summary": "summary.txt",
}


def count_matches(run_dir: Path, spec) -> int:
    if not spec:
        return -1  # no pattern defined
    path = run_dir / FILE_MAP.get(spec.get("file", ""), spec.get("file", ""))
    if not path.is_file():
        return 0
    try:
        text = path.read_text(errors="replace")
    except OSError:
        return 0
    return len(re.findall(spec["pattern"], text))


def ticket_from_id(watch_id: str) -> str:
    """A watch id starts with its owning ticket, lowercased and undashed (t4da-… -> T4-DA)."""
    head = watch_id.split("-", 1)[0]
    m = re.match(r"^([a-z])(\d)([a-z]{1,2})$", head)
    return f"{m.group(1)}{m.group(2)}-{m.group(3)}".upper() if m else "?"


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[2])
        return 0
    run_dir = Path(sys.argv[1])
    registry = Path(__file__).resolve().parent / "watches.json"
    watches = json.loads(registry.read_text())["watches"]

    print(f"# watch report — run={run_dir} registry={len(watches)} watches")
    attention = 0
    for w in watches:
        # The registry holds two shapes. The original is machine-evaluable (fired/opportunity
        # patterns); later entries are prose-only (why/look_for) because what they watch for
        # cannot be reduced to a regex. Both are legitimate registry members, and a prose entry
        # must not abort the report for every entry after it — which is exactly what happened on
        # run 2026-07-30_18-39-00, where a missing "ticket" key raised KeyError partway through
        # and the remaining watches were never evaluated. Silence from a crashed reporter reads
        # the same as silence from a quiet watch.
        ticket = w.get("ticket") or ticket_from_id(w.get("id", ""))
        if "fired" not in w and "opportunity" not in w:
            print(f"watch={w.get('id')} ticket={ticket} result=MANUAL")
            continue
        fired = count_matches(run_dir, w.get("fired"))
        if w.get("type") == "tally":
            result = f"COUNT({fired})"
        else:
            opp = count_matches(run_dir, w.get("opportunity"))
            if fired > 0:
                result = f"FIRED({fired})"
            elif opp > 0:
                result = f"OPPORTUNITY-NO-FIRE({opp})"
                attention += 1
            elif opp == -1:
                result = "NOT-OBSERVED"
            else:
                result = "NO-OPPORTUNITY"
        line = f"watch={w['id']} ticket={ticket} result={result}"
        if result.startswith("FIRED") and w.get("check"):
            line += f" check={w['check']!r}"
        print(line)
    print(f"# {attention} watch(es) need attention (opportunity without firing)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
