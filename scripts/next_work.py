#!/usr/bin/env python3
"""
next_work.py — the roadmap read as a work queue, for an autonomous development session.

`roadmap.md` is the queue and the source of truth, but it is thousands of lines, so orienting by
reading it costs a large share of a session's budget before any work starts. This emits the same
information a session actually needs to pick up work — live tickets, their gates, and the
bookkeeping hazards — as a compact digest.

Three things it reports, each one a mistake a real session made:

  CENSUS       marker counts straight from the ticket headers. The 2026-07-24 census listed T6-A as
               open while its body recorded SHIPPED and the overview index already read `[x]`.

  WARNINGS     unflipped closures — a live checkbox over a body that says the ticket itself
               finished. T6-A sat at `[ ]` for a day while its body recorded SHIPPED and the
               overview index already read `[x]`; this is the drift M-A exists to eliminate, and it
               recurred anyway. The opposite direction is NOT a warning: a ticket closed won't-do on
               measured evidence is correctly `[x]`.

  DEAD DEPS    a live ticket whose stated gate/prerequisite/fallback cannot be satisfied as written —
               either blocked on a deferred ticket, or resting on one that was closed WITHOUT being
               built. A closed-and-built prerequisite is satisfied and stays quiet. The 2026-07-24
               plan named "T4-CE slice 2 -> T4-E" as the fallback spine; T4-CE had already closed
               NO-BUILD, so the plan was dead as written and a session re-derived the spine by hand.

Gate lines are extracted rather than summarised: a gated ticket must be entered by running its own
gate measurement, and the gate text is the instruction for that.

Usage:
  next_work.py                     digest: census, warnings, dead deps, live queue with gates
  next_work.py --id T4-CP          one ticket's marker, title and gate lines
  next_work.py --tier 4            live tickets of one tier
  next_work.py --warnings-only     just the bookkeeping problems (cheap consistency check)
  next_work.py --json              machine-readable
Exit code is 0 even with warnings; this reports, it does not gate.
"""
import argparse
import json
import os
import re
import sys

DEFAULT_ROADMAP = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                               ".claude-memory", "roadmap.md")
HEADER = re.compile(r"^- \[([ x~>])\] \*\*(T\d[A-Z0-9]*(?:-[A-Z0-9]+)*|M-[A-Z])\b(.*)$")
TICKET_REF = re.compile(r"\bT\d-[A-Z]{1,3}(?:-\d)?\b")
LIVE_MARKERS = (" ", "~")

# A line stating a precondition — where a reference to a dead ticket actually matters.
DEPENDENCY_WORDS = ("gated", "gate", "prerequisite", "precondition", "blocked", "depends",
                    "dependency", "re-entry", "reentry", "fallback", "spine", "requires",
                    "required first", "waits on", "unblocked")
# A line stating the ticket's own entry condition — what a session must satisfy to pick it up.
GATE_WORDS = DEPENDENCY_WORDS + ("do not build", "not build", "done when", "dod", "deferred",
                                 "evidence-gated", "build-gated", "measure", "next step")
# A body line claiming THIS ticket finished. Deliberately high-precision: the claim must LEAD the
# line (an append opens with it), because bodies mention other tickets shipping all the time
# ("Depends on: T3-BR — shipped"), and those are not this ticket's status.
CLOSURE_CLAIM = re.compile(
    r"^(?:[✅⚠️\s*_-]*)(?:\*\*)?(?:[A-Z][A-Z-]{1,14}\s+){0,2}(SHIPPED|CLOSED)\b")
# ... or an explicit self-closure anywhere in the line.
EXPLICIT_CLOSURE = re.compile(r"\bticket\s+CLOSED\b|\bCLOSED\s+\d{4}-\d\d-\d\d\b"
                              r"|\bclosed\s+per\s+(?:its|the)\b", re.I)
# A partial-progress qualifier: one slice/phase shipping under an open umbrella is not drift.
PARTIAL_CLAIM = re.compile(r"\b(slice|phase|half|part|step)\b", re.I)


# A closed ticket that was closed WITHOUT building: the decision was recorded, no mechanism exists.
DECLINE_CLAIM = re.compile(r"\bdo not build\b|\bdoes not get built\b|\bwon'?t-?do\b|\bno-build\b"
                           r"|\bpremise falsified\b|\bstood down\b|\bgate resolved negative\b"
                           r"|\bnegative result is the deliverable\b", re.I)


def declines_build(body):
    """True when a closed ticket's body records a decision NOT to build the mechanism."""
    return any(DECLINE_CLAIM.search(line) for line in body)


def claims_own_closure(line):
    """True when this line asserts the ticket itself is finished (not a slice, not another ticket)."""
    stripped = line.strip()
    if PARTIAL_CLAIM.search(stripped):
        return False
    return bool(CLOSURE_CLAIM.match(stripped) or EXPLICIT_CLOSURE.search(stripped))


def parse(path):
    """[{id, marker, title, body_lines, line_no}] in file order, one entry per ticket header."""
    with open(path, errors="replace") as fh:
        lines = fh.read().splitlines()
    tickets, current = [], None
    for n, line in enumerate(lines, 1):
        m = HEADER.match(line)
        if m:
            marker, tid, rest = m.group(1), m.group(2), m.group(3)
            title = rest.split("**", 1)[0].lstrip(":").strip() or tid
            current = {"id": tid, "marker": marker, "title": title, "line": n, "body": []}
            tickets.append(current)
        elif current is not None:
            if line.startswith("- ["):        # a header we do not recognise ends the body
                current = None
            else:
                current["body"].append(line)
    return tickets


def tier_of(ticket_id):
    m = re.match(r"^T(\d)", ticket_id)
    return m.group(1) if m else "M"


def matching_lines(body, words, limit=None):
    """Body lines mentioning any of `words`, trimmed of markdown bullet noise."""
    out = []
    for line in body:
        low = line.lower()
        if any(w in low for w in words):
            out.append(line.strip().lstrip("-* ").strip())
            if limit and len(out) >= limit:
                break
    return out


# Gate lines are ranked, not taken in file order: a ticket's entry condition matters more than its
# design notes, and only the first few are shown.
GATE_PRIORITY = (
    ("re-entry", "reentry", "do not build", "does not get built", "evidence-gated", "build-gated",
     "gate still unmet", "stood down", "deferred", "blocked"),
    ("done when", "dod:", "prerequisite", "precondition", "depends on", "requires", "gated"),
)


def gate_lines(body, limit=3):
    """The strongest few precondition lines: entry conditions before design prose."""
    scored = []
    for line in body:
        low = line.lower()
        if not any(w in low for w in GATE_WORDS):
            continue
        rank = len(GATE_PRIORITY)
        for i, group in enumerate(GATE_PRIORITY):
            if any(w in low for w in group):
                rank = i
                break
        scored.append((rank, line.strip().lstrip("-*_ ").strip()))
    scored.sort(key=lambda x: x[0])            # stable: ties keep file order
    return [text for _, text in scored[:limit]]


def find_warnings(tickets):
    """Unflipped closures: a live checkbox over a body that says the ticket itself finished.

    Only this direction is reported. The reverse ("[x] but the body declines the build") is not an
    error at all — a ticket closed won't-do on measured evidence is correctly `[x]`, which is how
    T3-G, T4-CE and T4-CP all legitimately read.
    """
    warnings = []
    for t in tickets:
        if t["marker"] not in LIVE_MARKERS:
            continue
        claims = [line.strip().lstrip("-*_ ").strip() for line in t["body"]
                  if claims_own_closure(line)]
        if claims:
            warnings.append({
                "kind": "unflipped-closure", "id": t["id"], "line": t["line"],
                "detail": "checkbox is [%s] but the body claims this ticket finished"
                          % t["marker"],
                "evidence": claims[:1]})
    return warnings


def find_dead_deps(tickets):
    """Live tickets whose stated precondition cannot be satisfied as written.

    A prerequisite that is closed AND BUILT is satisfied — the common case, and not reported. Two
    cases are hazards:
      blocked-on-deferred      the precondition is `[>]`, so the gate cannot open without reviving it
      closed-without-building  the precondition is `[x]` but was closed won't-do / gate-negative, so
                               a plan resting on it is dead as written (T4-E -> T4-CE slice 2)
    """
    by_id = {t["id"]: t for t in tickets}
    out = []
    for t in tickets:
        if t["marker"] not in LIVE_MARKERS:
            continue
        seen = set()
        for line in t["body"]:
            low = line.lower()
            if not any(w in low for w in DEPENDENCY_WORDS):
                continue
            for ref in TICKET_REF.findall(line):
                if ref == t["id"] or ref in seen or ref not in by_id:
                    continue
                dep = by_id[ref]
                if dep["marker"] == ">":
                    kind = "blocked-on-deferred"
                elif dep["marker"] == "x" and declines_build(dep["body"]):
                    kind = "closed-without-building"
                else:
                    continue                      # closed and built = precondition satisfied
                seen.add(ref)
                out.append({"id": t["id"], "depends_on": ref, "dep_marker": dep["marker"],
                            "kind": kind, "line": line.strip().lstrip("-* ").strip()[:200]})
    return out


def digest(tickets, args, out):
    write = out.write
    counts = {m: sum(1 for t in tickets if t["marker"] == m) for m in (" ", "~", ">", "x")}
    distinct = len({t["id"] for t in tickets})
    write("ROADMAP WORK QUEUE — %d ticket headers (%d distinct ids)\n" % (len(tickets), distinct))
    write("  open [ ] %d · in-progress [~] %d · deferred [>] %d · closed [x] %d\n"
          % (counts[" "], counts["~"], counts[">"], counts["x"]))
    if len(tickets) != distinct:
        dupes = sorted({t["id"] for t in tickets
                        if sum(1 for o in tickets if o["id"] == t["id"]) > 1})
        write("  NOTE ids headed more than once: %s\n" % ", ".join(dupes))

    warnings = find_warnings(tickets)
    write("\nBOOKKEEPING WARNINGS (%d)\n" % len(warnings))
    if not warnings:
        write("  none — checkboxes agree with the bodies\n")
    for w in warnings:
        write("  [%s] %s (roadmap.md:%d) — %s\n" % (w["kind"], w["id"], w["line"], w["detail"]))
        for ev in w["evidence"]:
            write("      %s\n" % ev[:200])
    if args.warnings_only:
        return

    deps = find_dead_deps(tickets)
    write("\nDEAD OR MOVED DEPENDENCIES (%d) — a live ticket pointing at closed/deferred work\n"
          % len(deps))
    if not deps:
        write("  none\n")
    for d in deps:
        write("  [%s] %s -> %s [%s]\n      %s\n" % (d["kind"], d["id"], d["depends_on"],
                                                    d["dep_marker"], d["line"]))

    live = [t for t in tickets if t["marker"] in LIVE_MARKERS]
    if args.tier:
        live = [t for t in live if tier_of(t["id"]) == str(args.tier)]
    write("\nLIVE QUEUE (%d actionable: open + in-progress%s)\n"
          % (len(live), ", tier %s" % args.tier if args.tier else ""))
    for t in sorted(live, key=lambda t: (tier_of(t["id"]), t["id"])):
        write("\n  [%s] T%s · %s — %s\n" % (t["marker"], tier_of(t["id"]), t["id"],
                                            t["title"][:110]))
        gates = gate_lines(t["body"], 3)
        if gates:
            for g in gates:
                write("      gate: %s\n" % g[:220])
        else:
            write("      gate: none stated — read the body before starting\n")

    deferred = [t for t in tickets if t["marker"] == ">"]
    write("\n%d deferred [>] tickets are NOT in the queue; each carries its own re-entry condition"
          " (--id <T> to read one).\n" % len(deferred))


def main(argv):
    ap = argparse.ArgumentParser(description="Read roadmap.md as a work queue.")
    ap.add_argument("--roadmap", default=DEFAULT_ROADMAP)
    ap.add_argument("--id", help="print one ticket's marker, title and gate lines")
    ap.add_argument("--tier", help="restrict the live queue to one tier digit")
    ap.add_argument("--warnings-only", action="store_true", help="only the bookkeeping problems")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args(argv)

    if not os.path.isfile(args.roadmap):
        sys.stderr.write("next_work: no roadmap at %s\n" % args.roadmap)
        return 0
    tickets = parse(args.roadmap)

    if args.id:
        found = [t for t in tickets if t["id"].upper() == args.id.upper()]
        if not found:
            sys.stderr.write("next_work: no ticket %s\n" % args.id)
            return 0
        for t in found:
            payload = {"id": t["id"], "marker": t["marker"], "title": t["title"],
                       "line": t["line"], "gates": gate_lines(t["body"], 8)}
            if args.json:
                print(json.dumps(payload, indent=1))
            else:
                print("[%s] %s — %s  (roadmap.md:%d)" % (t["marker"], t["id"], t["title"], t["line"]))
                for g in payload["gates"]:
                    print("    %s" % g)
        return 0

    if args.json:
        print(json.dumps({
            "census": {m: sum(1 for t in tickets if t["marker"] == m) for m in (" ", "~", ">", "x")},
            "warnings": find_warnings(tickets),
            "dead_dependencies": find_dead_deps(tickets),
            "live": [{"id": t["id"], "marker": t["marker"], "title": t["title"],
                      "tier": tier_of(t["id"]), "gates": gate_lines(t["body"], 3)}
                     for t in tickets if t["marker"] in LIVE_MARKERS]}, indent=1))
        return 0

    digest(tickets, args, sys.stdout)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as exc:  # orientation must never be what breaks a session
        sys.stderr.write("next_work: %s\n" % exc)
        sys.exit(0)
