#!/usr/bin/env python3
"""
detector_replay.py — T6-D: make any recognizer prove it predicts something.

The four `analysis/pattern/` detectors fired 79 / 3 / 0 / 0 across 235 recorded runs, and none has
ever been checked against an outcome. This is the missing instrument: it mechanizes the outer loop's
highest-value move — *gate before building* — for in-loop recognizers.

Three modes, each answering a different question:

  observed      Do the detectors that DO fire predict worse outcomes? Joins recorded
                `pattern_detected` events to each run's outcome (terminal · smoke · label). No
                reconstruction needed, so this is always available.

  replay        What would a DIFFERENT rule or threshold have done? Re-evaluates a predicate against
                inputs rebuilt from `behaviour.ndjson`. Availability is per-rule and is REPORTED, never
                assumed: a rule whose inputs are not logged cannot be validated offline, and saying so
                is part of the result (it is an observability gap, not a pass).

  liveness      Did the recognizer evaluate at all, and how often did it fire? `fired=0` across a
                population is printed as a FINDING, not as silence. This exists because the project's
                worst recurring failure is silent inertness: the T4-CI health envelope produced zero
                comparisons for eight days, and `PlanningThrashSignalAnalyzer` has never fired in any
                recorded run — an instrument that abstains looks exactly like one with nothing to say.

Outcome classing is deliberately coarse and stated, not inferred: a run is `good` when it reached
GOAL_FULFILLED with a smoke score at or above the honest-fulfilment bar, `bad` when it ended on a
build/boot failure or a stall, and `unknown` otherwise. Association is reported as rates plus the
counts they come from, so a 3-firing detector cannot masquerade as a validated one.

Usage:
  detector_replay.py <path>...                 corpora and/or dirs to scan
  detector_replay.py backup /tmp/preflight_runs --min-bytes 100000
  detector_replay.py <paths> --rule thrash     replay one reconstructable rule
  detector_replay.py <paths> --json out.json
Exit 0 always; unreadable inputs are skipped and counted.
"""
import argparse
import glob
import json
import os
import re
import sys

# Smoke at or above this is an honest fulfilment; below it a FULFILLED terminal is a false completion.
HONEST_SMOKE_BAR = 20
SMOKE_RE = re.compile(r"SMOKE:\s*(\d+)/(\d+)")

# Rules whose inputs are reconstructable from behaviour events, measured 2026-07-25.
# Keeping this table explicit is the point: an unlogged rule must report as unreplayable, not as clean.
REPLAYABILITY = {
    "thrash": ("full", "mutation_negotiated.finalState / mutation_scored.llmState carry the state flip"),
    "empty-diff": ("partial", "mutation_proposed.files present, but projectDiff deletions are not logged"),
    "fingerprint-repeat": ("none", "lastEvolutionFingerprint is absent from behaviour events"),
    "focus-drift": ("none", "problemContext path keywords are absent from behaviour events"),
}


def decode_stream(path):
    """Events from a behaviour file (pretty-printed concatenated JSON, not line-JSON)."""
    with open(path, errors="replace") as fh:
        raw = fh.read()
    dec = json.JSONDecoder()
    i, n = 0, len(raw)
    while i < n:
        while i < n and raw[i] in " \n\r\t":
            i += 1
        if i >= n:
            return
        try:
            obj, i = dec.raw_decode(raw, i)
        except ValueError:
            return                      # truncated tail of a killed run — keep what parsed
        yield obj


def load_outcome_ledger():
    """T6-E: the durable outcome ledger, keyed by run id. Empty dict when absent."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "run_outcomes.json")
    try:
        with open(path) as fh:
            return json.load(fh).get("runs") or {}
    except (OSError, ValueError):
        return {}


LEDGER = load_outcome_ledger()


def read_summary(corpus, run_id=None):
    """summary.txt for this corpus, falling back to the durable T6-E ledger.

    The fallback matters because summary.txt lives in the /tmp corpus and disappears with it, while
    the ledger persists — so a run scored months ago still contributes ground truth here.
    """
    out = {}
    path = os.path.join(corpus, "summary.txt") if corpus else None
    if (not path or not os.path.isfile(path)):
        for key in filter(None, (run_id, os.path.basename(os.path.abspath(corpus)) if corpus else None)):
            row = LEDGER.get(key) or LEDGER.get(key.split("/")[0])
            if row:
                return dict(row)
        return out
    try:
        with open(path, errors="replace") as fh:
            for line in fh:
                if "=" in line:
                    k, v = line.split("=", 1)
                    out[k.strip()] = v.strip()
    except OSError:
        return {}
    return out


def smoke_score(text):
    m = SMOKE_RE.search(text or "")
    return int(m.group(1)) if m else None


def classify_outcome(summary, terminal):
    """`good` / `bad` / `unknown` — coarse on purpose, and stated rather than inferred.

    A FULFILLED run below the honest bar is a false completion and counts as BAD: that is the failure
    class this project cares most about, so it must not be scored as a success.
    """
    smoke = summary.get("smoke", "")
    score = smoke_score(smoke)
    term = summary.get("terminal") or terminal or ""
    if score is not None:
        return "good" if score >= HONEST_SMOKE_BAR else "bad"
    if any(k in smoke for k in ("boot-failed", "artifact-build-failed")):
        return "bad"
    if "GOAL_FULFILLED" in term:
        return "unknown"                # fulfilled but unscored — refuse to guess
    if any(k in term for k in ("stall", "exhaust", "STAGNAT", "deadline")):
        return "bad"
    return "unknown"


def scan_inputs(paths, min_bytes):
    """[(run_id, behaviour_path, corpus_dir)] deduped by file name; the largest copy of a run wins."""
    found = {}

    def offer(behaviour, corpus):
        try:
            size = os.path.getsize(behaviour)
        except OSError:
            return
        if size < min_bytes:
            return
        key = os.path.basename(behaviour)
        if key == "behaviour.ndjson" and corpus:
            key = os.path.basename(os.path.abspath(corpus)) + "/" + key
        prev = found.get(key)
        if prev is None or size > prev[2]:
            found[key] = (behaviour, corpus, size)

    for path in paths:
        if os.path.isfile(path):
            offer(path, os.path.dirname(path))
        elif os.path.isdir(path):
            direct = os.path.join(path, "behaviour.ndjson")
            if os.path.isfile(direct):
                offer(direct, path)
            for sub in glob.glob(os.path.join(path, "**", "behaviour*.ndjson"), recursive=True):
                offer(sub, os.path.dirname(sub))
    return [(rid, p, c) for rid, (p, c, _) in sorted(found.items())]


def replay_thrash(events_by_cycle, order):
    """The `thrash` rule: evaluation state flipped ACCEPTED<->REJECTED between consecutive decisions.

    Reconstructed from the negotiated outcome (falling back to the raw LLM state). The in-loop rule
    additionally requires a stagnation conjunct, which is NOT reconstructable — so this replays the
    flip half only, and the caller must read it as an upper bound on firings, never as parity.
    """
    states, fired = [], []
    for cid in order:
        ev = events_by_cycle.get(cid, {})
        state = ev.get("negotiated_state") or ev.get("llm_state")
        if state not in ("ACCEPTED", "REJECTED"):
            continue
        if states and states[-1][1] != state:
            fired.append(cid)
        states.append((cid, state))
    return fired, len(states)


def analyse_run(run_id, behaviour, corpus):
    order, seen = [], set()
    per_cycle = {}
    firings = []                        # recorded pattern_detected events
    terminal = None

    for ev in decode_stream(behaviour):
        name = ev.get("event") or ev.get("type")
        cid = ev.get("cycleId")
        payload = ev.get("payload") or {}
        if cid and cid not in seen:
            seen.add(cid)
            order.append(cid)
            per_cycle[cid] = {}
        if name == "pattern_detected":
            detector = str(payload.get("detector") or "?")
            firings.append({"cycle": cid, "detector": detector,
                            "signal": payload.get("signalEmitted"),
                            "evidence": payload.get("evidence")})
        elif name == "mutation_negotiated" and cid:
            per_cycle[cid]["negotiated_state"] = payload.get("finalState")
        elif name == "mutation_scored" and cid:
            per_cycle[cid]["llm_state"] = payload.get("llmState")
        elif name == "mutation_proposed" and cid:
            per_cycle[cid]["proposed_files"] = len(payload.get("files") or [])
        elif name == "cycle_end" and payload.get("resultState"):
            terminal = payload["resultState"]
        elif name == "system_paused":
            terminal = "SYSTEM_PAUSED"

    if not order:
        return None
    summary = read_summary(corpus, run_id)
    return {
        "run": run_id,
        "cycles": len(order),
        "terminal": summary.get("terminal") or terminal,
        "outcome": classify_outcome(summary, terminal),
        "firings": firings,
        "_order": order,
        "_per_cycle": per_cycle,
    }


def rate(part, whole):
    return (100.0 * part / whole) if whole else 0.0


def report(rows, skipped, rule, out):
    write = out.write
    total = len(rows)
    outcomes = {k: sum(1 for r in rows if r["outcome"] == k) for k in ("good", "bad", "unknown")}
    write("T6-D detector replay — %d runs (%d skipped)\n" % (total, skipped))
    write("  outcome mix: good %d · bad %d · unknown %d  "
          "(good = FULFILLED with smoke >= %d; a FULFILLED run below the bar counts as BAD)\n"
          % (outcomes["good"], outcomes["bad"], outcomes["unknown"], HONEST_SMOKE_BAR))

    # ---- liveness + observed association -----------------------------------
    detectors = {}
    for r in rows:
        for f in r["firings"]:
            base = f["detector"].split(":")[0]
            d = detectors.setdefault(base, {"firings": 0, "runs": set(), "rules": {}})
            d["firings"] += 1
            d["runs"].add(r["run"])
            rule_name = f["detector"].split(":", 1)[1] if ":" in f["detector"] else "(whole)"
            d["rules"][rule_name] = d["rules"].get(rule_name, 0) + 1

    write("\nLIVENESS — evaluated over %d runs (fired=0 is a FINDING, not silence)\n" % total)
    # Current classes first, then names that only appear in historical corpora. T5-BH renamed
    # PatternSignalAnalyzer -> EmptyDiffDetector and deleted PlanningThrashSignalAnalyzer, so a
    # zero against those two is expected in NEW runs and must not read as a fresh inertness finding.
    known = ["EmptyDiffDetector", "ScopeThrashSignalAnalyzer", "ReplanPatternDetector"]
    historical = {"PatternSignalAnalyzer": "renamed to EmptyDiffDetector by T5-BH",
                  "PlanningThrashSignalAnalyzer": "deleted by T5-BH (was inert 0/237)"}
    # Introduced after this corpus was recorded: a zero here means "not yet exercised", NOT inert.
    # Reporting it as inertness would be a false finding of exactly the kind this harness exists to
    # prevent, so new detectors are named explicitly until a run has had the chance to fire them.
    pending = {"EmptyDiffDetector": "new in T5-BH — no recorded run predates it; zero is expected"}
    for name in known + [d for d in sorted(detectors) if d not in known]:
        d = detectors.get(name)
        if not d:
            note = historical.get(name) or pending.get(name)
            if note:
                write("  %-30s fired 0 — %s\n" % (name, note))
            else:
                write("  %-30s fired 0 in %d runs   <== INERT: never fired in the whole population\n"
                      % (name, total))
            continue
        if name in historical:
            write("  %-30s fired %4d in %3d/%d runs — historical: %s\n"
                  % (name, d["firings"], len(d["runs"]), total, historical[name]))
            for rname, n in sorted(d["rules"].items(), key=lambda kv: -kv[1]):
                write("      %-26s %4d\n" % (rname, n))
            continue
        write("  %-30s fired %4d in %3d/%d runs (%.0f%%)\n"
              % (name, d["firings"], len(d["runs"]), total, rate(len(d["runs"]), total)))
        for rname, n in sorted(d["rules"].items(), key=lambda kv: -kv[1]):
            write("      %-26s %4d\n" % (rname, n))

    scored_total = outcomes["good"] + outcomes["bad"]
    write("\nOBSERVED ASSOCIATION (tier 1: smoke-scored runs — the strong ground truth)\n")
    if scored_total < 20:
        write("  ** %d/%d runs carry a conclusive outcome. Ground truth is scarce by history, not by\n"
              "     neglect: the ~223 archived runs were never scored and cannot be (T6-E). The durable\n"
              "     ledger now grows forward, so treat these numbers as indicative today and re-run\n"
              "     this as scripts/run_outcomes.json fills. **\n"
              % (scored_total, total))
    write("  (base rate: %.0f%% of scored runs ended bad, n=%d)\n"
          % (rate(outcomes["bad"], scored_total), scored_total))
    for name in sorted(detectors):
        runs = detectors[name]["runs"]
        scored = [r for r in rows if r["run"] in runs and r["outcome"] in ("good", "bad")]
        bad = sum(1 for r in scored if r["outcome"] == "bad")
        if not scored:
            write("  %-30s no SCORED run fired it — association unmeasurable\n" % name)
            continue
        write("  %-30s bad in %d/%d scored firing-runs (%.0f%%)%s\n"
              % (name, bad, len(scored), rate(bad, len(scored)),
                 "   [n<5 — not evidence]" if len(scored) < 5 else ""))

    # Weaker proxy, kept clearly separate: the terminal is recorded for every run, so it gives power
    # the smoke score cannot. It is a PROXY — a GOAL_FULFILLED run can still be a false completion
    # (three are on record), which is exactly why it never overrides the scored tier above.
    fulfilled = [r for r in rows if "GOAL_FULFILLED" in (r["terminal"] or "")]
    write("\nOBSERVED ASSOCIATION (tier 2: terminal proxy — weaker, but covers every run)\n")
    write("  proxy: reached GOAL_FULFILLED = %d/%d runs (%.0f%%). A fulfilled run may still be a\n"
          "  false completion, so this ranks detectors; it does not certify them.\n"
          % (len(fulfilled), total, rate(len(fulfilled), total)))
    for name in sorted(detectors):
        runs = detectors[name]["runs"]
        fired_rows = [r for r in rows if r["run"] in runs]
        ff = sum(1 for r in fired_rows if "GOAL_FULFILLED" in (r["terminal"] or ""))
        base = rate(len(fulfilled), total)
        got = rate(ff, len(fired_rows))
        write("  %-30s fulfilled in %2d/%2d firing-runs (%.0f%% vs %.0f%% base) -> %s\n"
              % (name, ff, len(fired_rows), got, base,
                 "fires on WORSE runs" if got < base else "fires on BETTER-or-equal runs"))

    # ---- replayability -----------------------------------------------------
    write("\nREPLAYABILITY — can a rule be re-evaluated offline at all?\n")
    for rname, (status, why) in sorted(REPLAYABILITY.items()):
        mark = {"full": "yes", "partial": "partial", "none": "NO"}[status]
        write("  %-20s %-8s %s\n" % (rname, mark, why))
    write("  A rule marked NO cannot be validated offline — that is an observability gap,\n"
          "  not a clean result. Logging its inputs is the prerequisite for judging it.\n")

    if rule:
        write("\nREPLAY — rule '%s'\n" % rule)
        if REPLAYABILITY.get(rule, ("none", ""))[0] == "none":
            write("  refused: inputs are not present in behaviour events (see above)\n")
        elif rule != "thrash":
            write("  not implemented yet; only 'thrash' has a replay implementation\n")
        else:
            fired_runs, total_fire, decisions = 0, 0, 0
            per_outcome = {"good": 0, "bad": 0}
            for r in rows:
                fires, n = replay_thrash(r["_per_cycle"], r["_order"])
                decisions += n
                if fires:
                    fired_runs += 1
                    total_fire += len(fires)
                    if r["outcome"] in per_outcome:
                        per_outcome[r["outcome"]] += 1
            write("  flips found: %d across %d evaluable decisions in %d/%d runs\n"
                  % (total_fire, decisions, fired_runs, total))
            scored = per_outcome["good"] + per_outcome["bad"]
            if scored:
                write("  of scored runs where it would fire: %d bad / %d (%.0f%%)\n"
                      % (per_outcome["bad"], scored, rate(per_outcome["bad"], scored)))
            write("  NOTE upper bound: the in-loop rule also requires a stagnation conjunct that is\n"
                  "  not reconstructable, so real firings are a subset of these flips.\n")


def main(argv):
    ap = argparse.ArgumentParser(description="T6-D detector replay + outcome association.")
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--min-bytes", type=int, default=0)
    ap.add_argument("--rule", default=None, help="replay one rule (see REPLAYABILITY)")
    ap.add_argument("--json", default=None)
    args = ap.parse_args(argv)

    rows, skipped = [], 0
    for run_id, behaviour, corpus in scan_inputs(args.paths, args.min_bytes):
        try:
            row = analyse_run(run_id, behaviour, corpus)
        except Exception as exc:            # one bad run must not lose the batch
            sys.stderr.write("detector_replay: skipped %s (%s)\n" % (run_id, exc))
            row = None
        if row is None:
            skipped += 1
        else:
            rows.append(row)

    report(rows, skipped, args.rule, sys.stdout)
    if args.json:
        with open(args.json, "w") as fh:
            json.dump([{k: v for k, v in r.items() if not k.startswith("_")} for r in rows],
                      fh, indent=1)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as exc:                # a measurement must never break the thing measuring
        sys.stderr.write("detector_replay: %s\n" % exc)
        sys.exit(0)
