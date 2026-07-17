#!/usr/bin/env python3
"""T4-CE slice 1: evaluate the field-watch registry (scripts/watches.json) against a
preflight corpus and print one result line per watch.

Usage: watch_report.py <corpus-dir>

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
corpora evaluate identically. Exit code is always 0 — the report informs, it never
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


def count_matches(corpus: Path, spec) -> int:
    if not spec:
        return -1  # no pattern defined
    path = corpus / FILE_MAP.get(spec.get("file", ""), spec.get("file", ""))
    if not path.is_file():
        return 0
    try:
        text = path.read_text(errors="replace")
    except OSError:
        return 0
    return len(re.findall(spec["pattern"], text))


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[2])
        return 0
    corpus = Path(sys.argv[1])
    registry = Path(__file__).resolve().parent / "watches.json"
    watches = json.loads(registry.read_text())["watches"]

    print(f"# watch report — corpus={corpus} registry={len(watches)} watches")
    attention = 0
    for w in watches:
        fired = count_matches(corpus, w.get("fired"))
        if w.get("type") == "tally":
            result = f"COUNT({fired})"
        else:
            opp = count_matches(corpus, w.get("opportunity"))
            if fired > 0:
                result = f"FIRED({fired})"
            elif opp > 0:
                result = f"OPPORTUNITY-NO-FIRE({opp})"
                attention += 1
            elif opp == -1:
                result = "NOT-OBSERVED"
            else:
                result = "NO-OPPORTUNITY"
        line = f"watch={w['id']} ticket={w['ticket']} result={result}"
        if result.startswith("FIRED") and w.get("check"):
            line += f" check={w['check']!r}"
        print(line)
    print(f"# {attention} watch(es) need attention (opportunity without firing)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
