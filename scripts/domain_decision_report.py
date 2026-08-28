#!/usr/bin/env python3
"""Count DECISION SITES in core code that branch on target-domain concepts.

T4-DD clause 4 originally counted MENTIONS, and the ticket refuted its own metric:
"the 66-hit ranking identified the right FILE for the wrong REASON — hit counts
measured MENTIONS; this measures what BLOCKS." A mention in a comment or a log
string cannot make the loop behave differently on a target with no HTTP surface.
A CONDITIONAL can.

So this counts conditions only: `if`/`else if`/`while`/ternary/`switch` whose test
text references a domain concept. Comments, javadoc, log calls and string literals
are stripped first, because rewording a comment must not improve the score.

SEAM files are reported separately and are not debt: the point of T4-DD is that
this knowledge lives BEHIND a seam, not that it ceases to exist. Debt is a decision
site in a CORE file — one the goal tree, cycle, merge or evaluation path runs.

Usage:  python3 scripts/domain_decision_report.py [--list]
Exit 0 always; this reports, it does not gate.
"""
import re
import sys
from pathlib import Path

SRC = Path("src/main/java/com/walpro/autobuilder")

# The seam as T4-DD names it: requirement extraction, surface discovery, probe
# synthesis, execution+observation, structural risk facts.
SEAM = (
    "analysis/structure/", "core/build/", "goal/SpecTraceability",
    "protocol/", "analysis/wiring/",
)

# Case-INSENSITIVE terms are ones no ordinary Java identifier collides with.
# SUBSTRING, not \b-bounded: the first version missed `spec.hasEndpoints()` because
# a word boundary cannot fall inside camelCase. Every term here is long and
# distinctive enough that a substring hit is not a collision.
DOMAIN_ANY = re.compile(
    r"(endpoint|http|servlet|controller|requestmapping|requestbody|"
    r"pathvariable|queryparam|statuscode|restcontroller)",
    re.IGNORECASE,
)
# HTTP verbs must be UPPERCASE to count. Lowercased they are ordinary Java —
# `delete(e)`, `row.get(...)`, `map.put(...)` — and matching those inflated the
# first version of this report by roughly a third before it was checked.
DOMAIN_VERB = re.compile(r"\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\b")
CONDITION_START = re.compile(r"\b(?:if|else\s+if|while|switch)\s*\(")


def condition_text(line):
    """The text inside a condition's parentheses, balanced — not to end of line.

    Capturing `(.+)$` swept the statement body in with the test, so `if (x != null)
    doHttpThing();` scored as a decision ON http when http appears only in the
    consequent. Balancing the parens is the difference between measuring the test
    and measuring the line.
    """
    m = CONDITION_START.search(line)
    if not m:
        return None
    depth, out = 1, []
    for ch in line[m.end():]:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return "".join(out)
        out.append(ch)
    return "".join(out)  # unbalanced (condition wraps lines): use what we have


def domain_in(text):
    return bool(DOMAIN_ANY.search(text) or DOMAIN_VERB.search(text))


def strip_noise(text):
    """Remove comments only.

    String literals are KEPT. Stripping them deleted the literal in
    `if ("POST".equals(verb))` — a genuine decision on an HTTP verb — and the
    detector then reported zero core sites, which read as a clean core rather
    than a broken instrument. Literals matter only inside conditions here, and a
    log call is not a condition, so keeping them costs nothing.
    """
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


# autobuilder's OWN REST API. The 2026-07-30 inventory excluded it explicitly and
# so must this: a status check in our own controller says nothing about whether the
# LOOP decides differently for a target with no HTTP surface.
# Both directions of autobuilder's OWN HTTP: the inbound REST API it serves
# (`controller/`) and the outbound client it calls the model with (`client/`).
# `httpStatus == 429` in a circuit breaker is our transport, not the target's
# surface, and counting it would make the loop look domain-coupled for talking
# to its own model.
OWN_WEB_LAYER = ("/controller/", "/client/")


def is_own_web_layer(rel):
    return any(s in rel for s in OWN_WEB_LAYER)


def is_seam(rel):
    return any(s in rel for s in SEAM)


def main(argv):
    show = "--list" in argv
    core, seam = [], []
    for path in sorted(SRC.rglob("*.java")):
        rel = str(path.relative_to(SRC.parent.parent.parent.parent.parent))
        code = strip_noise(path.read_text(encoding="utf-8", errors="replace"))
        hits = []
        for lineno, line in enumerate(code.splitlines(), 1):
            cond = condition_text(line)
            if cond is not None and domain_in(cond):
                hits.append((lineno, cond.strip()[:110]))
        if not hits or is_own_web_layer(rel):
            continue
        (seam if is_seam(rel) else core).append((rel, hits))

    def report(title, group):
        total = sum(len(h) for _, h in group)
        print(f"\n{title}: {total} decision site(s) in {len(group)} file(s)")
        for rel, hits in sorted(group, key=lambda kv: -len(kv[1])):
            print(f"  {len(hits):3}  {rel}")
            if show:
                for lineno, txt in hits:
                    print(f"         :{lineno}  {txt}")
        return total

    core_n = report("CORE (T4-DD clause 4 debt)", core)
    seam_n = report("SEAM (expected, not debt)", seam)
    print(f"\nclause-4 measure: {core_n} core decision site(s); "
          f"{seam_n} behind the seam")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
