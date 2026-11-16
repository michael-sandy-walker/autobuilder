#!/usr/bin/env python3
"""T4-EZ measurement (a): do stuck SHAPES recur across tasks and projects?

Reads ~/.autobuilder/run-archive/*/stuck-ledger.json (runs ordered by folder name = timestamp),
normalises each signature to its L1 shape (no model), and prints the counts the rule on the
T4-EZ ticket names: R_w (within task, exact signature), R_x (across tasks / families, shape),
C (same ending question across tasks), and a descriptive closed-ticket match.

Usage: python3 scripts/t4ez_shape_recurrence.py [--shapes] [--check]
"""
import collections
import glob
import json
import os
import re
import sys

ARCHIVE = os.path.expanduser("~/.autobuilder/run-archive")
ROADMAP = os.path.join(os.path.dirname(__file__), "..", ".claude-memory", "roadmap.md")
CONTENTLESS = {"FLAT_ROOT", "EXHAUSTION", "PROTECTED_WRITE"}
AUTOBUILDER_MODULES = {"ladder-grind", "ladder-rows", "parameterized-rows", "history-reads", "edit-hunks",
                       "receiver-questions", "stuck-questions", "rule-extraction", "constraint-samples",
                       "participation-totals"}


def task_of(target):
    t = re.sub(r"-withheld$", "", target)
    if t in ("labelmaker-seed-hand", "labelmaker-seed-rev"):
        t = "labelmaker"
    return t


def family_of(task):
    return "autobuilder-modules" if task in AUTOBUILDER_MODULES else task


# ---------- shapes ----------

def compiler_shape(msg):
    m = msg.strip()
    m = re.sub(r"^\[(WARNING|ERROR)\]\s*", "", m)
    m = re.sub(r"^\S+\.java:(\[\d+,\d+\])?\s*", "", m)
    kind = re.search(r"symbol:\s+(class|variable|method)\b", m)
    m = m.split("\n")[0].strip()
    if m == "cannot find symbol" and kind:
        m = "cannot find symbol " + kind.group(1)
    rules = [
        (r"^\S+ has no \S+$", "<Type> has no <member>()"),
        (r"^cannot find symbol (class|variable|method)\b.*", r"cannot find symbol \1"),
        (r"^cannot find symbol$", "cannot find symbol"),
        (r"^incompatible types: bad return type in lambda.*", "incompatible types: bad return type in lambda"),
        (r"^incompatible types: inference variable.*", "incompatible types: inference variable bounds"),
        (r"^incompatible types: .* cannot be converted to .*", "incompatible types: cannot be converted"),
        (r"^method .* is already defined in .*", "method already defined"),
        (r"^constructor .* cannot be applied to given types.*", "constructor cannot be applied to given types"),
        (r"^package .* does not exist$", "package does not exist"),
        (r"^\S+ '.*' already exists\.?$", "<member> already exists"),
        (r"^cannot access .*", "cannot access <type>"),
        (r"^unreported exception .*", "unreported exception must be caught or declared"),
        (r"^class is not allowed to extend sealed class.*", "not allowed to extend sealed class"),
    ]
    for pat, rep in rules:
        if re.match(pat, m):
            return re.sub(pat, rep, m)
    return re.sub(r"\d+", "N", m)


def refusal_shape(ground):
    g = ground.strip()
    if "COMPILATION ERROR" in g:
        cm = re.search(r"\.java:\[\d+,\d+\]\s*(.*?)(\s*\|\s*\[ERROR\]|$)", g)
        return "compilation error: " + (compiler_shape(cm.group(1)) if cm else "?")
    if g.startswith("test suite failing"):
        return "test suite failing"
    if "exits at startup" in g:
        if "required a bean" in g:
            return "exits at startup: a constructor parameter's bean not found"
        if "no main manifest attribute" in g:
            return "exits at startup: no main manifest attribute"
        return "exits at startup: other"
    if "spec-named endpoint" in g:
        st = re.search(r"answers (\d{3})", g)
        return "spec-named endpoint fails at runtime: answers " + (st.group(1) if st else "?")
    if "acceptance criteria fail when exercised" in g:
        return "acceptance criteria fail when exercised against the running application"
    if "name an entry point no test calls" in g:
        return "obligation names an entry point no test calls and value-asserts"
    if "no test written from the requirement" in g:
        return "no test written from a requirement's wording could be kept"
    if "declares entry point" in g:
        return "packaging succeeded but the declared entry point is wrong"
    g = re.sub(r"'[^']*'|\"[^\"]*\"", "Q", g)
    g = re.sub(r"\d+", "N", g)
    return g[:100]


def test_shape(detail):
    if not detail:
        return "?"
    m = detail[0].split(" -- ", 1)[-1].strip()
    m = re.sub(r"^.*==> ", "", m)
    if m.startswith("expected"):
        return "expected ... but was"
    if m.startswith("Expecting") or m.startswith("Expected"):
        head = re.split(r" / |:|\"|<", m, 1)[0].strip()
        return re.sub(r"\d+", "N", head)
    if m.startswith("test input given") or m.startswith("ran with"):
        return "parameterized row: " + test_shape([m.split(" | ", 1)[-1]]) if " | " in m else "parameterized row"
    if m.startswith("Argument(s) are different") or m.startswith("Argument"):
        return "mock: arguments are different"
    if m.startswith("Error creating bean"):
        return "context: error creating bean"
    return "other"


def shape(e):
    src, sig = e["source"], e["signature"]
    if src in CONTENTLESS:
        return src
    if src == "BUILD":
        det = e.get("detail") or []
        comp = next((d[len("compiler="):] for d in det if d.startswith("compiler=")), None)
        return "BUILD: " + compiler_shape(comp if comp else sig.replace("the build fails: ", ""))
    if src in ("CLOSURE_REFUSAL", "BOOT"):
        ground = re.split(r"was refused: |keeps showing: ", sig, 1)[-1]
        return "REFUSAL: " + refusal_shape(ground.replace(" -- ", " — "))
    if src == "TEST_FAILURES":
        return "TEST: " + test_shape(e.get("detail"))
    if src == "VETO":
        h, _, msg = sig.partition(":")
        return "VETO: " + h + ": " + re.sub(r"\d+", "N", msg.strip())
    return src + ": " + sig


def ending_question(e):
    qs = e.get("questions") or []
    if not qs:
        return None
    q = qs[-1]
    text = re.sub(r"'[^']*'|\"[^\"]*\"|`[^`]*`", "Q", q.get("question", ""))
    return (re.sub(r"\d+", "N", text), q.get("authority", "").split(":")[0])


# ---------- corpus ----------

def load():
    runs = []
    for f in sorted(glob.glob(os.path.join(ARCHIVE, "*", "stuck-ledger.json"))):
        d = os.path.dirname(f)
        target = "?"
        try:
            for line in open(os.path.join(d, "target.txt")):
                if line.startswith("target="):
                    target = line.split("=", 1)[1].strip()
        except OSError:
            pass
        runs.append((os.path.basename(d), target, json.load(open(f))))
    return runs


def check():
    """Known positives: one message in two tasks must come out as ONE shape."""
    a = {"source": "BUILD", "signature": "the build fails: Foo.java: local variables referenced from a lambda expression must be final or effectively final"}
    b = {"source": "BUILD", "signature": "the build fails: Bar.java: local variables referenced from a lambda expression must be final or effectively final"}
    c = {"source": "BUILD", "signature": "the build fails: BuildOutcome has no stderr()"}
    d = {"source": "BUILD", "signature": "the build fails: PathMatcher has no pattern()"}
    e = {"source": "BUILD", "signature": "the build fails: X.java: cannot find symbol variable log in class X"}
    f = {"source": "BUILD", "signature": "the build fails: Y.java: cannot find symbol class Z in package a.b"}
    assert shape(a) == shape(b), (shape(a), shape(b))
    assert shape(c) == shape(d), (shape(c), shape(d))
    assert shape(e) != shape(f), "a variable and a class must stay apart"
    print("normaliser check: OK")


def main():
    check()
    runs = load()
    # first sighting per (task, signature) and per (task, shape); all sightings per shape
    sig_runs = collections.defaultdict(list)      # (task, sig) -> [run index]
    shape_sightings = collections.defaultdict(list)  # shape -> [(run index, task, family)]
    first_task_shape = {}                         # (task, shape) -> run index
    ended = collections.defaultdict(lambda: collections.defaultdict(list))  # shape -> task -> [ending q]
    for i, (run, target, entries) in enumerate(runs):
        task = task_of(target)
        fam = family_of(task)
        seen_here = set()
        for e in entries:
            s = shape(e)
            sig_runs[(task, e["signature"])].append(i)
            if s not in seen_here:
                shape_sightings[s].append((i, task, fam))
                seen_here.add(s)
            first_task_shape.setdefault((task, s), i)
            if e.get("status") == "ENDED":
                q = ending_question(e)
                if q:
                    ended[s][task].append(q)

    # R_w: exact signature recurs in a later run of the same task
    rw_n = len(sig_runs)
    rw_k = sum(1 for runs_ in sig_runs.values() if len(set(runs_)) > 1)

    def rx(contentful_only=True, by_family=False):
        n = k = 0
        for (task, s), first in first_task_shape.items():
            if contentful_only and s in CONTENTLESS:
                continue
            n += 1
            mine = family_of(task) if by_family else task
            if any(j > first and (fam if by_family else t) != mine for j, t, fam in shape_sightings[s]):
                k += 1
        return k, n

    def consistency(by_family=False):
        multi = 0
        same = 0
        for s, per_task in ended.items():
            if s in CONTENTLESS:
                continue
            groups = collections.defaultdict(list)
            for t, qs in per_task.items():
                groups[family_of(t) if by_family else t].extend(qs)
            if len(groups) < 2:
                continue
            multi += 1
            modal = [collections.Counter(qs).most_common(1)[0][0] for qs in groups.values()]
            top, cnt = collections.Counter(modal).most_common(1)[0]
            if cnt * 2 > len(modal):
                same += 1
        return same, multi

    print(f"corpus: {len(runs)} ledgers, {sum(1 for r in runs if r[2])} non-empty, "
          f"{sum(len(r[2]) for r in runs)} entries, {len({task_of(r[1]) for r in runs})} tasks, "
          f"{len({family_of(task_of(r[1])) for r in runs})} families")
    print(f"R_w  (task, exact signature) recurs later in same task: {rw_k}/{rw_n} = {rw_k / rw_n:.2f}")
    for lbl, fam in (("task", False), ("family", True)):
        k, n = rx(True, fam)
        s, m = consistency(fam)
        print(f"R_x  [{lbl}] contentful (task, shape) seen later in another {lbl}: {k}/{n} = {k / n:.2f}"
              f"   C: same ending question in {s}/{m} shapes ENDED in >=2 {lbl}s")
    k, n = rx(False, False)
    print(f"     (with contentless sources included, task: {k}/{n})")

    # descriptive: closed-ticket match on shape text
    try:
        road = open(ROADMAP, encoding="utf-8").read()
        closed = "\n".join(b for b in re.split(r"\n- \[", road) if b.startswith("x]"))
    except OSError:
        closed = ""
    matched = []
    for s in shape_sightings:
        if s in CONTENTLESS:
            continue
        core = s.split(": ", 1)[-1]
        probe = re.sub(r"<[^>]*>|\.\.\.", "", core).strip()
        words = [w for w in re.split(r"\s+", probe) if len(w) > 3]
        if len(words) >= 3 and all(w.lower() in closed.lower() for w in words) and probe.lower() in closed.lower():
            matched.append(s)
    print(f"(iv) contentful shapes whose text appears verbatim in a closed ticket: {len(matched)}/"
          f"{sum(1 for s in shape_sightings if s not in CONTENTLESS)}")

    if "--shapes" in sys.argv:
        print("\nshape  | runs | tasks | families | ended-in-tasks | ticket")
        for s, sights in sorted(shape_sightings.items(), key=lambda kv: -len({t for _, t, _ in kv[1]})):
            tasks = {t for _, t, _ in sights}
            fams = {f for _, _, f in sights}
            print(f"{len(sights):4d} {len(tasks):3d} {len(fams):3d} {len(ended.get(s, {})):3d} "
                  f"{'T' if s in matched else ' '}  {s[:110]}")


if __name__ == "__main__":
    main()
