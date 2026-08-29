#!/usr/bin/env python3
"""T4-CE slice 2 (gate prototype): post-run self-diagnosis over a preflight run_dir.

Renders a run_dir's recorded evidence (instrument outputs, behaviour stream, goal tree,
log tails) into a structured defect-hypothesis prompt for the local model and captures
its classification + hypothesis. Output = PROPOSAL ONLY, never a filing.

Stdlib only. The evidence extractors are fixed and mechanical — identical for every
run_dir — so a replay over historical run directories measures the model, not the curation.

Usage:
  diagnosis_replay.py --run_dir /tmp/preflight_runs/<stamp> [--model qwen3-coder:30b]
                      [--dry-run] [--out DIR]

--dry-run prints the rendered evidence and exits without calling the model.
"""

import argparse
import json
import os
import re
import sys
import urllib.request
from collections import Counter

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434/api/generate")

# Behaviour-event payload fields worth relaying (compact, mechanism-agnostic).
EVENT_FIELDS = ["reason", "resultState", "merged", "role", "kind", "rule", "symbol",
                "direction", "state", "score", "terminalReason", "verifiedBuildResult",
                "fromCycle", "regroundedGoals", "end reportLoaded", "buildResult",
                "mutationId", "finalState", "errorKind", "missingToken"]

HALT_MARKERS = re.compile(
    r"Exhaustion runaway|System is paused|root convergence|GOAL_EXHAUSTED|"
    r"circuit breaker|Pausing|planner-output truncation|planner-output amputation|deadlock", re.I)


def read(path, limit=None):
    try:
        with open(path, errors="replace") as f:
            s = f.read()
        return s[:limit] if limit else s
    except OSError:
        return None


def parse_behaviour(path):
    """Tolerant parse: line-JSON or pretty-printed concatenated JSON objects."""
    s = read(path)
    if not s:
        return []
    events, dec, i = [], json.JSONDecoder(), 0
    while i < len(s):
        while i < len(s) and s[i] in " \r\n\t":
            i += 1
        if i >= len(s):
            break
        try:
            obj, j = dec.raw_decode(s, i)
            events.append(obj)
            i = j
        except ValueError:
            i = s.find("\n", i)
            if i < 0:
                break
            i += 1
    return events


def compact_event(e):
    parts = [str(e.get("event", "?"))]
    ts = str(e.get("timestamp", ""))[11:19]
    if ts:
        parts.insert(0, ts)
    payload = e.get("payload") or {}
    for f in EVENT_FIELDS:
        v = payload.get(f, e.get(f))
        if v is not None:
            v = str(v)
            if len(v) > 60:
                v = v[:60] + "…"
            parts.append(f"{f}={v}")
    return " ".join(parts)


def walk_goals(g):
    yield g
    for c in g.get("subGoals") or []:
        yield from walk_goals(c)


def render_goalstack(path):
    s = read(path)
    if not s:
        return "(no GoalStack.json)"
    try:
        d = json.loads(s)
    except ValueError:
        return "(GoalStack.json unparseable)"
    tree = d.get("goalTree") or d.get("rootGoal") or d
    lines = []
    statuses = Counter()
    blockers = []
    for g in walk_goals(tree):
        statuses[g.get("status", "?")] += 1
        md = g.get("metadata") or {}
        criteria = md.get("acceptanceCriteria") or []
        passed = md.get("criteriaPassStatus") or {}
        unmet = [c for i, c in enumerate(criteria) if not passed.get(str(i + 1), False)]
        if g.get("status") == "GIVEN_UP" and unmet:
            blockers.append((g.get("description", "")[:100], unmet[:4]))
    root_status = tree.get("status")
    lines.append(f"root status: {root_status}; subgoal statuses: "
                 + ", ".join(f"{k}={v}" for k, v in statuses.most_common()))
    lines.append(f"currentSubGoalId: {d.get('currentSubGoalId')}")
    if blockers:
        lines.append(f"GIVEN_UP subgoals with unmet criteria ({len(blockers)}; first 10):")
        for desc, unmet in blockers[:10]:
            lines.append(f"  - {desc}")
            for u in unmet:
                lines.append(f"      unmet: {str(u)[:120]}")
    else:
        lines.append("no GIVEN_UP subgoal carries unmet criteria")
    return "\n".join(lines)


def render_applog(path):
    s = read(path)
    if not s:
        return "(no autobuilder.log)"
    warn_err = [l for l in s.splitlines() if " WARN " in l or " ERROR " in l]
    # collapse identical message shapes (strip timestamp + numbers)
    shape = Counter()
    for l in warn_err:
        m = re.sub(r"^[\d\-: .]+", "", l)
        m = re.sub(r"\d+", "N", m)[:160]
        shape[m] += 1
    lines = [f"WARN/ERROR lines total: {len(warn_err)}; top recurring shapes:"]
    for msg, n in shape.most_common(12):
        lines.append(f"  {n:5d}x {msg}")
    halt = []
    seen = set()
    for l in s.splitlines():
        if HALT_MARKERS.search(l):
            key = re.sub(r"^[\d\-: .]+", "", l)[:120]
            if key in seen:
                continue
            seen.add(key)
            halt.append(l[:200])
    if halt:
        lines.append("halt-family log lines (deduped, first 12):")
        lines.extend("  " + h for h in halt[:12])
    return "\n".join(lines)


def render_evidence(run_dir):
    sec = []

    def add(title, body):
        if body:
            sec.append(f"--- {title} ---\n{body.strip()}")

    add("INSTRUMENT SUMMARY (summary.txt)", read(os.path.join(run_dir, "summary.txt")))
    add("ABANDONED MARKER", read(os.path.join(run_dir, "ABANDONED.txt")))
    add("WATCH REPORT", read(os.path.join(run_dir, "watch_report.txt"), 4000))
    add("RUN END REPORT (the run's own terminal self-description)",
        read(os.path.join(run_dir, "run-end-report.json"), 6000)
        # run directories captured before the 2026-07-17 rename carry the file under its old name
        or read(os.path.join(run_dir, "run-testament.json"), 6000))
    add("GOAL TREE", render_goalstack(os.path.join(run_dir, "GoalStack.json")))

    beh = os.path.join(run_dir, "behaviour.ndjson")
    if not os.path.exists(beh):
        pl = os.path.join(run_dir, "partial_logs")
        if os.path.isdir(pl):
            for f in os.listdir(pl):
                if f.startswith("behaviour"):
                    beh = os.path.join(pl, f)
                    break
    events = parse_behaviour(beh)
    if events:
        hist = Counter(e.get("event", "?") for e in events)
        body = ("event histogram: "
                + ", ".join(f"{k}={v}" for k, v in hist.most_common()) + "\n")
        body += "first 5 events:\n" + "\n".join("  " + compact_event(e) for e in events[:5])
        body += "\nlast 40 events:\n" + "\n".join("  " + compact_event(e) for e in events[-40:])
        add(f"BEHAVIOUR STREAM ({len(events)} events)", body)
    else:
        add("BEHAVIOUR STREAM", "(none or unparseable)")

    applog = os.path.join(run_dir, "autobuilder.log")
    if not os.path.exists(applog):
        pl = os.path.join(run_dir, "partial_logs")
        if os.path.isdir(pl):
            for f in os.listdir(pl):
                if f.startswith("autobuilder"):
                    applog = os.path.join(pl, f)
                    break
    add("APP LOG DIGEST", render_applog(applog))

    console = read(os.path.join(run_dir, "console.log"))
    if console:
        add("CONSOLE TAIL", "\n".join(console.splitlines()[-10:]))

    smoke = read(os.path.join(run_dir, "summary.txt")) or ""
    if "artifact-build-failed" in smoke or "boot-failed" in smoke:
        ab = read(os.path.join(run_dir, "artifact-build.log"))
        if ab:
            errs = [l for l in ab.splitlines() if "ERROR" in l or "error" in l][:20]
            add("ARTIFACT BUILD FAILURE (ground-truth build of the terminal tree)",
                "\n".join(errs))

    return "\n\n".join(sec)


PROMPT_HEADER = """You are the post-run diagnosis job of "autobuilder", an autonomous \
self-improvement loop that evolves a target Java/Spring Boot project (a book-lending REST API) \
toward a goal using a local LLM. Each run ends at a terminal: fulfilled, an honest halt by a \
brake, an external deadline kill, or an environment failure. Mechanisms inside the framework \
(gates, brakes, injectors, planners) are engineered and can themselves be defective.

You receive the recorded evidence of ONE run. Diagnose it.

Class definitions:
- FRAMEWORK_DEFECT — the run's outcome or artifact was harmed by a bug in the autobuilder \
framework itself (a wrong decision, wrong injected code/import, a starved agent, stale state \
carried where it should have been reset); a framework code fix would address it.
- HONEST_NO_DEFECT — the framework behaved as designed: the goal was fulfilled, or the run \
ended in an honest halt/deadline while brakes and gates fired correctly; residual imperfections \
are the local model's capability, not framework bugs.
- ENVIRONMENT — an external failure (model host down, process killed externally, infrastructure \
crash) ended or invalidated the run.

Instruments can be wrong: cross-check the summary labels against the logs and the behaviour \
stream; when they disagree, say so and trust the primary evidence.

Respond in EXACTLY this format (no extra prose):
CLASSIFICATION: FRAMEWORK_DEFECT | HONEST_NO_DEFECT | ENVIRONMENT
CONFIDENCE: <0.0-1.0>
PRIMARY_HYPOTHESIS: <2-5 sentences naming the mechanism area and the causal chain>
EVIDENCE:
- <quoted or paraphrased evidence line 1>
- <line 2>
- <line 3>
TIER_SUGGESTION: <T1 correctness bug | T2 designed-but-dumb policy | T4 missing capability | T5 observability | none>

=== EVIDENCE ===

"""


def ask_model(model, prompt):
    payload = json.dumps({
        "model": model,
        "prompt": prompt,
        "stream": False,
        "options": {"temperature": 0.0},
    }).encode()
    req = urllib.request.Request(OLLAMA_URL, data=payload,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read())["response"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--model", default="qwen3-coder:30b")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--out", default=None,
                    help="directory for candidate_findings-<stamp>.md (default: run_dir dir)")
    args = ap.parse_args()

    evidence = render_evidence(args.run_dir)
    prompt = PROMPT_HEADER + evidence
    est_tokens = (len(prompt) + 3) // 4
    print(f"[diagnosis] run={args.run_dir} evidence≈{est_tokens} tokens", file=sys.stderr)
    if args.dry_run:
        print(prompt)
        return

    answer = ask_model(args.model, prompt)
    stamp = os.path.basename(os.path.normpath(args.run_dir))
    outdir = args.out or args.run_dir
    os.makedirs(outdir, exist_ok=True)
    outfile = os.path.join(outdir, f"candidate_findings-{stamp}.md")
    with open(outfile, "w") as f:
        f.write(f"# Candidate findings (PROPOSAL ONLY — never auto-filed)\n"
                f"run_dir: {stamp}\nmodel: {args.model} temp=0.0\n\n{answer}\n")
    print(answer)
    print(f"[diagnosis] written: {outfile}", file=sys.stderr)


if __name__ == "__main__":
    main()
