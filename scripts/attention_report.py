#!/usr/bin/env python3
"""
attention_report.py — T6-L: what did a run spend its cycles ON, measured within the run.

T6-L left one live hypothesis after refuting two. Runs that score well on input validation score
badly on the loans family and vice versa (r = -0.41 over 25 runs), which is consistent with the
standing finding that the loop's attention follows whatever it can probe. But a correlation
between two OUTCOMES cannot tell attention from capability: a run may fail the loans family
because it never worked on it, or because it worked on it and could not get it right. Those want
opposite fixes, and the ticket says so explicitly — "it needs a within-run attention measurement,
not another correlation".

This is that measurement. For every cycle a run executed, the log records which subgoal was
current; the goal stack records that subgoal's acceptance criteria. Classifying the CRITERIA into
the same families the smoke checks group into gives, per run, how much of the run went to each
family — which can then be read against whether that family passed.

  ATTENTION   share of the run's cycles whose current subgoal carried criteria in this family
  MERGE       the same, counting only cycles that merged (attention that reached the tree)
  PASS        share of that family's smoke checks the run passed

Families are the smoke suite's own groups (see scripts/smoke_chain_report.py for the checks):

  creates           [1, 8]              POST /books, POST /members happy path
  validation        [2, 3, 4, 9, 10]    rejected input on those two creates
  loans_ref         [14, 15, 22]        404 when a loan names something that does not exist
  loans_limit       [19]                422 when a member exceeds the loan limit
  loans_unmeasured  -                   loan work no smoke check measures (400s on POST /loans)
  loans_structural  -                   the loan entity, service, repository — feeds every loan
                                        check and belongs to none
  other             -                   reads, listings, book/member plumbing

`loans_unmeasured` earns a row of its own rather than falling into `other` because it is the
concrete shape the attention hypothesis predicts: effort spent on a loan requirement the yardstick
never scores. If the loans families lose to anything, this is the cell that should show it.

BUILD_FIX subgoals are held out of every figure and reported separately. Such a subgoal exists
BECAUSE the build is red, so its compile probe fails by construction; counting it as attention on
whatever file it names would make "this family does not compile" partly true by definition.

Attribution is FRACTIONAL. A subgoal whose criteria span two families splits its cycles between
them instead of being counted once in each, so the shares of a run sum to 1 and no family can be
inflated by a broadly-worded subgoal.

A cycle whose subgoal is absent from the final goal stack (removed by a replan, and the stack is a
snapshot of the end state) is reported as `unresolved` and excluded from the shares, never
silently attributed. The unresolved share is printed so a run whose plan churned heavily can be
discounted by eye rather than trusted blindly.

Usage:
  attention_report.py [run-dir-glob ...]     default: /tmp/preflight_runs/*
  attention_report.py --json
  attention_report.py --per-run
"""
import argparse
import glob
import json
import os
import re
import sys

CYCLE_RE = re.compile(r"CYCLE\s+(\S+)\s+goal=(\S+)\s+sub=(\S+).*?merged=(true|false)")
MUT_RE = re.compile(r"\bmut=([A-Z_]+)")
# The pre-merge compile probe's verdict for the proposal of the cycle it precedes. The CYCLE line
# is written at cycle END, so probes seen since the previous CYCLE line belong to this one.
PROBE_RE = re.compile(r"T1-EC: probe result=(COMPILE_OK|COMPILE_ERROR)")
# The injector's re-probe after it has tried to repair the proposal. Separating the two says
# whether loan code arrives broken, or arrives broken and stays broken.
REPROBE_RE = re.compile(r"re-probe after rule=\S+ result=(COMPILE_OK|COMPILE_ERROR)")
FAIL_RE = re.compile(r"^FAIL \[(\d+)\]")
PASS_RE = re.compile(r"^PASS \[(\d+)\]")

FAMILY_CHECKS = {
    "creates": [1, 8],
    "validation": [2, 3, 4, 9, 10],
    "loans_ref": [14, 15, 22],
    "loans_limit": [19],
}
# Families that exist as work but that no check scores; they take attention and can win no points.
UNSCORED_FAMILIES = ("loans_unmeasured", "other")

# "loan" also covers loans/loaned/loaning. Deliberately NOT "return": criteria say "returns HTTP
# 201" constantly, and admitting that verb made every create look like loan work.
LOAN_WORDS = ("loan", "lend", "borrow")
LIMIT_WORDS = ("422", "at most", "maximum", "max active", "limit", "exceed", "more than 3",
               "more than three", "3 active", "three active", "fourth")
ABSENT_WORDS = ("404", "not found", "absent", "non-existent", "nonexistent", "does not exist",
                "doesn't exist", "unknown id")
# Deliberately narrow. Bare "empty" matched "empty JSON array when no books are stored" (a GET
# listing) and bare "required" matched "Create the Loan entity with required fields" (structural
# work); both were checked by sampling the classifier's own output before any number was believed.
INVALID_WORDS = ("400", "invalid", "must match", "max 120", "validat", "is missing",
                 "when title is", "empty body", "empty string", "blank")
REQUIRED_WORDS = ("required", "missing")
DUPLICATE_WORDS = ("duplicate", "unique", "already registered", "409")
CREATE_WORDS = ("201", "post /books", "post /members")


def classify(text):
    """Families a single criterion (or description) belongs to. Never empty."""
    t = (text or "").lower()
    fams = set()
    loanish = any(w in t for w in LOAN_WORDS)
    if loanish:
        if any(w in t for w in LIMIT_WORDS):
            fams.add("loans_limit")
        if any(w in t for w in ABSENT_WORDS):
            fams.add("loans_ref")
        if not fams:
            if any(w in t for w in INVALID_WORDS):
                # A 400 requirement on a loan endpoint: real work, no smoke check measures it.
                fams.add("loans_unmeasured")
            else:
                # Loan work naming no status: the entity, the service, the repository. It feeds
                # every loan check and belongs to none, so it gets its own row rather than
                # disappearing into the same bucket as book and member plumbing.
                fams.add("loans_structural")
    else:
        if any(w in t for w in DUPLICATE_WORDS) and "email" in t:
            fams.add("validation")
        if any(w in t for w in INVALID_WORDS):
            fams.add("validation")
        elif any(w in t for w in REQUIRED_WORDS) and ("title" in t or "isbn" in t
                                                      or "name" in t or "email" in t):
            fams.add("validation")
        if not fams and any(w in t for w in CREATE_WORDS):
            fams.add("creates")
    return fams or {"other"}


def goal_index(stack_path):
    """subgoal id -> the text that decides its family (description + acceptance criteria)."""
    try:
        with open(stack_path, errors="replace") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return {}
    index = {}

    def walk(node):
        if not isinstance(node, dict):
            return
        gid = node.get("id")
        if gid:
            meta = node.get("metadata") or {}
            criteria = meta.get("acceptanceCriteria") or []
            if not isinstance(criteria, list):
                criteria = [str(criteria)]
            description = node.get("description") or ""
            index[gid] = {"description": description,
                          "criteria": [str(c) for c in criteria],
                          # Repair work, not resource work. A BUILD_FIX subgoal exists BECAUSE the
                          # build is red, so its probe fails by construction; counting it as
                          # attention on whatever file it names would make "this family does not
                          # compile" partly true by definition. Held out and reported separately.
                          "build_fix": "[BUILD_FIX]" in description.upper()}
        for child in node.get("subGoals") or []:
            walk(child)

    walk(data.get("goalTree") or {})
    return index


def family_weights(entry):
    """Fractional family weights for one subgoal, summing to 1."""
    weights = {}
    units = entry["criteria"] or [entry["description"]]
    units = [u for u in units if u.strip()] or [entry["description"] or ""]
    for unit in units:
        fams = classify(unit)
        for fam in fams:
            weights[fam] = weights.get(fam, 0.0) + 1.0 / (len(units) * len(fams))
    return weights


def read_cycles(log_path):
    """[(cycleId, subgoalId, merged, mutationState)] in order; state is None when none was scored."""
    out = []
    pending, repaired = [], []
    try:
        with open(log_path, errors="replace") as fh:
            for line in fh:
                probe = PROBE_RE.search(line)
                if probe:
                    pending.append(probe.group(1))
                    continue
                reprobe = REPROBE_RE.search(line)
                if reprobe:
                    repaired.append(reprobe.group(1))
                    continue
                if "CycleLoggingService - CYCLE " not in line:
                    continue
                m = CYCLE_RE.search(line)
                if m:
                    mut = MUT_RE.search(line)
                    # `probe` is the model's OWN output. `after_repair` is where the cycle ended up
                    # once the injector had its turn — the last verdict anyone saw.
                    first = pending[-1] if pending else None
                    last = repaired[-1] if repaired else first
                    out.append((m.group(1), m.group(3), m.group(4) == "true",
                                mut.group(1) if mut else None, first, last))
                    pending, repaired = [], []
    except OSError:
        return []
    return out


def read_smoke(run_dir):
    """{check -> bool} from the preserved sheet, else the final-files sheet, else None."""
    for name in ("preserved-smoke.txt", "artifact-smoke.txt"):
        path = os.path.join(run_dir, name)
        results = {}
        try:
            with open(path, errors="replace") as fh:
                for line in fh:
                    m = FAIL_RE.match(line)
                    if m:
                        results[int(m.group(1))] = False
                        continue
                    m = PASS_RE.match(line)
                    if m:
                        results[int(m.group(1))] = True
        except OSError:
            continue
        if results:
            return results
    return None


def measure_run(run_dir):
    cycles = read_cycles(os.path.join(run_dir, "autobuilder.log"))
    index = goal_index(os.path.join(run_dir, "GoalStack.json"))
    smoke = read_smoke(run_dir)
    if not cycles or not index or not smoke:
        return None

    attention, merge_attention = {}, {}
    scored, accepted = {}, {}
    late_cycles, late_merges = {}, {}
    unresolved = 0
    repaired = 0
    merged_cycles = 0
    probed, probe_failed = {}, {}
    settled, settled_failed = {}, {}
    repair_cycles = {}
    for position, (_cid, sub, merged, mut, probe, after_repair) in enumerate(cycles):
        entry = index.get(sub)
        if entry is None:
            unresolved += 1
            continue
        if entry["build_fix"]:
            repaired += 1
            for fam, weight in family_weights(entry).items():
                repair_cycles[fam] = repair_cycles.get(fam, 0.0) + weight
            continue
        if merged:
            merged_cycles += 1
        # "Late" = the back half of the run. Loan subgoals are planned last, so a raw loan-vs-book
        # conversion gap could be a gap between EARLY and LATE cycles wearing a family's name.
        # Restricting both sides to the same half is the cheapest available control.
        is_late = position >= len(cycles) / 2
        for fam, weight in family_weights(entry).items():
            attention[fam] = attention.get(fam, 0.0) + weight
            if merged:
                merge_attention[fam] = merge_attention.get(fam, 0.0) + weight
            if mut:
                scored[fam] = scored.get(fam, 0.0) + weight
                if mut == "ACCEPTED":
                    accepted[fam] = accepted.get(fam, 0.0) + weight
            if probe:
                probed[fam] = probed.get(fam, 0.0) + weight
                if probe == "COMPILE_ERROR":
                    probe_failed[fam] = probe_failed.get(fam, 0.0) + weight
            if after_repair:
                settled[fam] = settled.get(fam, 0.0) + weight
                if after_repair == "COMPILE_ERROR":
                    settled_failed[fam] = settled_failed.get(fam, 0.0) + weight
            if is_late:
                late_cycles[fam] = late_cycles.get(fam, 0.0) + weight
                if merged:
                    late_merges[fam] = late_merges.get(fam, 0.0) + weight

    resolved = len(cycles) - unresolved - repaired
    passes = {}
    for fam, checks in FAMILY_CHECKS.items():
        seen = [smoke[c] for c in checks if c in smoke]
        passes[fam] = (sum(1 for s in seen if s) / len(seen)) if seen else None

    return {
        "cycles": len(cycles),
        "resolved_cycles": resolved,
        "unresolved_share": (unresolved / len(cycles)) if cycles else 0.0,
        "merged_cycles": merged_cycles,
        "attention": {f: (v / resolved if resolved else 0.0) for f, v in attention.items()},
        "merge_attention": {f: (v / merged_cycles if merged_cycles else 0.0)
                            for f, v in merge_attention.items()},
        # Raw weights survive so conversion (did this family's work reach the tree?) can be
        # computed WITHIN a run, where the run's own difficulty and length cancel out.
        "cycles_raw": attention,
        "merges_raw": merge_attention,
        "scored_raw": scored,
        "accepted_raw": accepted,
        "late_cycles_raw": late_cycles,
        "late_merges_raw": late_merges,
        "probed_raw": probed,
        "probe_failed_raw": probe_failed,
        "settled_raw": settled,
        "settled_failed_raw": settled_failed,
        "repair_cycles": repaired,
        "repair_raw": repair_cycles,
        "pass": passes,
        "smoke_pass": sum(1 for v in smoke.values() if v),
        "smoke_total": len(smoke),
    }


def pearson(xs, ys):
    n = len(xs)
    if n < 3:
        return None
    mx, my = sum(xs) / n, sum(ys) / n
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    dx = sum((x - mx) ** 2 for x in xs) ** 0.5
    dy = sum((y - my) ** 2 for y in ys) ** 0.5
    if dx == 0 or dy == 0:
        return None
    return num / (dx * dy)


def collect(patterns, since=None, until=None):
    runs = {}
    for pattern in patterns:
        for d in sorted(glob.glob(pattern)):
            if not os.path.isdir(d):
                continue
            name = os.path.basename(d.rstrip("/"))
            # Run dirs are named by start timestamp, so a lexical compare is a time compare.
            if (since and name < since) or (until and name >= until):
                continue
            got = measure_run(d)
            if got:
                runs[name] = got
    return runs


ORDER = ["creates", "validation", "loans_ref", "loans_limit", "loans_unmeasured",
         "loans_structural", "other"]
LOAN_FAMILIES = ("loans_ref", "loans_limit", "loans_unmeasured", "loans_structural")
BOOK_MEMBER_FAMILIES = ("creates", "validation")


def ratio(run, numerator_key, denominator_key, families, floor=1.0):
    spent = sum(run[denominator_key].get(f, 0.0) for f in families)
    landed = sum(run[numerator_key].get(f, 0.0) for f in families)
    return (landed / spent) if spent >= floor else None


def conversion(run, families):
    """Share of this run's cycles on `families` that merged. None when the run did no such work."""
    return ratio(run, "merges_raw", "cycles_raw", families)


def paired(runs, left, right, numerator_key, denominator_key, floor=1.0):
    """[(run, left_ratio, right_ratio)] for runs where both sides did enough work to compare."""
    out = []
    for name, r in sorted(runs.items()):
        a = ratio(r, numerator_key, denominator_key, left, floor)
        b = ratio(r, numerator_key, denominator_key, right, floor)
        if a is not None and b is not None:
            out.append((name, a, b))
    return out


def report_paired(title, rows, label_left, label_right):
    if not rows:
        print(f"\n{title}: no run did enough of both to compare")
        return
    worse = sum(1 for _, a, b in rows if a < b)
    mean = sum(a - b for _, a, b in rows) / len(rows)
    ma = sum(a for _, a, _ in rows) / len(rows)
    mb = sum(b for _, _, b in rows) / len(rows)
    print(f"\n{title}")
    print(f"  {label_left} {ma:.0%} vs {label_right} {mb:.0%} — "
          f"lower in {worse} of {len(rows)} runs, mean delta {mean:+.0%}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("patterns", nargs="*", default=["/tmp/preflight_runs/*"])
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--per-run", action="store_true")
    ap.add_argument("--since", metavar="RUN_PREFIX",
                    help="only runs at or after this run-dir name, e.g. 2026-08-01_20-38")
    ap.add_argument("--until", metavar="RUN_PREFIX", help="only runs before this run-dir name")
    args = ap.parse_args()

    runs = collect(args.patterns or ["/tmp/preflight_runs/*"], args.since, args.until)
    if not runs:
        print("no run carries a cycle log + goal stack + smoke sheet")
        return 1

    if args.json:
        json.dump(runs, sys.stdout, indent=1, sort_keys=True)
        print()
        return 0

    print(f"T6-L within-run attention — {len(runs)} runs\n")
    print(f"{'family':<18}{'attention':>10}{'of merges':>11}{'pass':>8}   runs scoring it")
    for fam in ORDER:
        att = [r["attention"].get(fam, 0.0) for r in runs.values()]
        mrg = [r["merge_attention"].get(fam, 0.0) for r in runs.values()]
        pss = [r["pass"][fam] for r in runs.values() if r["pass"].get(fam) is not None]
        pass_txt = f"{sum(pss) / len(pss):>7.0%}" if pss else "      -"
        print(f"{fam:<18}{sum(att) / len(att):>9.0%}{sum(mrg) / len(mrg):>10.0%}"
              f"{pass_txt}   {len(pss) if pss else '-'}")

    print("\nDoes attention explain the pass rate? (per family, across runs)")
    print(f"{'family':<18}{'r(attention,pass)':>19}{'n':>5}")
    for fam in FAMILY_CHECKS:
        pairs = [(r["attention"].get(fam, 0.0), r["pass"][fam])
                 for r in runs.values() if r["pass"].get(fam) is not None]
        r = pearson([p[0] for p in pairs], [p[1] for p in pairs])
        print(f"{fam:<18}{('%+.2f' % r) if r is not None else '   n/a':>19}{len(pairs):>5}")

    # The within-run comparison the cross-run correlation cannot make: each run is its own
    # control, so its length, difficulty and overall quality cancel.
    print("\nWithin-run conversion — of the cycles a run spent on a family, what share merged?")
    print(f"{'run':<22}{'loans':>8}{'book/mem':>10}{'delta':>8}")
    pairs = []
    for name, r in sorted(runs.items()):
        loan = conversion(r, LOAN_FAMILIES)
        bkmm = conversion(r, BOOK_MEMBER_FAMILIES)
        if loan is None or bkmm is None:
            continue
        pairs.append((name, loan, bkmm))
        print(f"{name:<22}{loan:>7.0%}{bkmm:>9.0%}{loan - bkmm:>+8.0%}")
    if pairs:
        lower = sum(1 for _, l, b in pairs if l < b)
        mean_delta = sum(l - b for _, l, b in pairs) / len(pairs)
        print(f"\nloan work converted WORSE in {lower} of {len(pairs)} runs; "
              f"mean delta {mean_delta:+.0%}")

    report_paired("Where in the chain is it lost? — the evaluator's verdict",
                  paired(runs, LOAN_FAMILIES, BOOK_MEMBER_FAMILIES, "accepted_raw", "scored_raw"),
                  "loan proposals ACCEPTED", "book/member")
    report_paired("Does the model's own output compile? — first probe, before any repair",
                  paired(runs, LOAN_FAMILIES, BOOK_MEMBER_FAMILIES,
                         "probe_failed_raw", "probed_raw"),
                  "loan proposals FAILING the probe", "book/member")
    report_paired("...and after the injector has had its turn?",
                  paired(runs, LOAN_FAMILIES, BOOK_MEMBER_FAMILIES,
                         "settled_failed_raw", "settled_raw"),
                  "loan proposals still red", "book/member")
    report_paired("Position control — same comparison, back half of each run only",
                  paired(runs, LOAN_FAMILIES, BOOK_MEMBER_FAMILIES,
                         "late_merges_raw", "late_cycles_raw"),
                  "late loan cycles merged", "late book/member")

    repair_total = sum(sum(r["repair_raw"].values()) for r in runs.values())
    repair_loan = sum(sum(r["repair_raw"].get(f, 0.0) for f in LOAN_FAMILIES)
                      for r in runs.values())
    all_cycles = sum(r["cycles"] for r in runs.values())
    print("\nBUILD_FIX repair cycles (held out of every figure above)")
    print(f"  {repair_total:.0f} of {all_cycles} cycles ({repair_total / all_cycles:.0%}); "
          f"{repair_loan / repair_total:.0%} of them name loan code"
          if repair_total else "  none")

    unres = sum(r["unresolved_share"] for r in runs.values()) / len(runs)
    print(f"\nunresolved cycles (subgoal gone from the final stack): {unres:.0%} mean")

    if args.per_run:
        print("\nper run:")
        head = "".join(f"{f[:9]:>10}" for f in ORDER)
        print(f"{'run':<22}{'cyc':>5}{'unres':>7}{head}   smoke")
        for name, r in sorted(runs.items()):
            row = "".join(f"{r['attention'].get(f, 0.0):>9.0%} " for f in ORDER)
            print(f"{name:<22}{r['cycles']:>5}{r['unresolved_share']:>6.0%} {row}"
                  f"  {r['smoke_pass']}/{r['smoke_total']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
