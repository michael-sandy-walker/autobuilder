#!/usr/bin/env python3
"""Why did this run's artifact fail to build or boot? (T6-M)

39% of recorded runs end with an artifact that will not build or boot, and until now only a
couple had ever been looked at individually -- so the largest failure class in the corpus had
no measured composition, only anecdote. This assigns each failing run a FAMILY, so the
distribution can be stated with counts instead of guessed from whichever run was examined last.

Families:
  dependency-missing      a package nothing on the classpath provides -- the artifact's pom does
                          not declare the library it imports, so every type in it is unresolvable
  compile-missing-type    a type the code references was never written (the T1-FR shape)
  compile-missing-member  the type exists, a member it is called on does not (the T1-FQ shape)
  compile-other           it did not compile, for some other reason
  wiring                  compiles and packages, fails at context startup (no qualifying bean)
  packaging               compiles, cannot be packaged (no main class / repackage failure)
  boot-other              starts, then fails for some other reason
  healthy                 no failure found
  unknown                 nothing left to read

PROVENANCE MATTERS AND IS ALWAYS REPORTED. The authoritative source is the scoring-time
artifact-build.log / artifact-boot.log, which records what actually failed when the tree was
built. Once those are gone the only fallback is the run's OWN last diagnostics, which can
disagree -- a run can end its final cycle COMPILE_OK and still produce a tree that fails to
package. A fallback classification is a weaker claim and says so.

Usage:  scripts/failure_family.py DIR [DIR ...]
Last line is machine readable:
    FAMILY: runs=<n> classified=<n> unknown=<n> top=<family|none>
"""
import argparse, gzip, os, re, sys
from collections import Counter

MISSING_PACKAGE = re.compile(r"package ([\w.]+) does not exist")
SOURCE_PATH = re.compile(r"src/main/java/([\w/]+)/\w+\.java")
MISSING_TYPE = re.compile(r"symbol:\s+class\s+(\w+)")
MISSING_MEMBER = re.compile(r"symbol:\s+(?:method|variable)\s+([\w.<>,() ]+)")
WIRING = re.compile(r"No qualifying bean|UnsatisfiedDependencyException|"
                    r"Error creating bean with name")
PACKAGING = re.compile(r"no main manifest attribute|Unable to find main class|repackage failed")


def _read(path):
    if not path or not os.path.exists(path):
        return ""
    try:
        opener = gzip.open if path.endswith(".gz") else open
        with opener(path, "rt", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return ""


def _first(d, *names):
    for n in names:
        p = os.path.join(d, n)
        if os.path.exists(p):
            return p
    return None


def project_packages(text):
    """The artifact's OWN package prefixes, read from the compiler's file paths. Needed to tell
    an undeclared library apart from a package the artifact simply has not written yet -- the
    diagnostic text is identical for both and the owning defect is not."""
    return {p.replace("/", ".") for p in SOURCE_PATH.findall(text)}


def project_namespaces(text, depth=2):
    """The leading segments the artifact's own packages share with each other -- `com.example`
    for `com.example.library.domain.repository`. Compared at this depth deliberately: a failing
    build often names ONE source file, so the artifact's full package is not its root, and a
    sibling package it has not written yet would then read as an undeclared library."""
    return {".".join(p.split(".")[:depth]) for p in project_packages(text)}


def last_build_block(text):
    """Just the LAST failing compilation in the text. The scorer's own log holds one build, but
    the run-log fallback holds every build the run attempted, and an early one names defects the
    run went on to repair -- one such diagnostic sat 116k lines before the failure that actually
    ended run 2026-08-05_22-14-13."""
    marks = [m.start() for m in re.finditer(r"COMPILATION ERROR", text)]
    return text[marks[-1]:] if marks else text


def classify_build(text):
    """Family for a failed BUILD, plus the symbol that names it."""
    if not text.strip():
        return None, None
    text = last_build_block(text)
    own = project_namespaces(text)
    for pkg in MISSING_PACKAGE.findall(text):
        # Cause before cascade: an undeclared dependency makes every type it holds report as a
        # missing symbol, so reading those symbols first names the effect and the wrong ticket.
        if own and not any(pkg == ns or pkg.startswith(ns + ".") for ns in own):
            return "dependency-missing", pkg
    types = MISSING_TYPE.findall(text)
    if types:
        return "compile-missing-type", types[-1]
    members = MISSING_MEMBER.findall(text)
    if members:
        return "compile-missing-member", members[-1].strip()
    if PACKAGING.search(text):
        return "packaging", None
    if re.search(r"\[ERROR\]|COMPILE_ERROR|BUILD FAILURE", text):
        return "compile-other", None
    return None, None


def classify_boot(text):
    if not text.strip():
        return None, None
    m = re.search(r"No qualifying bean of type '([\w.$]+)'", text)
    if m:
        return "wiring", m.group(1).split(".")[-1]
    if WIRING.search(text):
        return "wiring", None
    # A packaging failure is by nature a LAUNCH-time symptom: the build succeeds and the archive
    # cannot be started, so its signature lands in the boot log while the build log is empty.
    # Checked only against the build log until 2026-09-21, which sent run 2026-09-21_14-29-16 --
    # artifact-build.log 0 bytes, artifact-boot.log "no main manifest attribute" -- down to the
    # run-log fallback. It reached the right family there by luck and carried a weaker provenance
    # than the evidence deserved, which is the reading this tool exists to make trustworthy.
    if PACKAGING.search(text):
        return "packaging", None
    if "APPLICATION FAILED TO START" in text:
        return "boot-other", None
    return None, None


def outcome(d):
    """The run's scored outcome as (passed, of) -- (0, None) for a build/boot failure, None if
    unknown. Without this the run-log fallback happily classifies a HEALTHY run from its
    mid-run diagnostics -- every run hits missing symbols on the way, so that would put
    successful runs into a failure distribution and inflate whichever family is loudest.

    THE DENOMINATOR IS READ, NEVER ASSUMED. It was pinned to 23 -- firestarter's suite -- while
    every other target scores against its own yardstick (clitarget 19, an earlier 12). Their
    scores therefore matched nothing, read as 0, and entered the failure distribution anyway:
    measured over the 96-run archive, 23 runs that SCORED ABOVE ZERO were classified as
    failures, seven of them perfect. The guard above was correct and its denominator was not,
    so it protected exactly the one target its tests were written on. A target's check count is
    not a property of this tool."""
    txt = _read(_first(d, "summary.txt"))
    for line in txt.splitlines():
        if line.startswith("smoke="):
            raw = line.split("=", 1)[1].strip()
            m = re.search(r"(\d+)/(\d+)", raw)
            return (int(m.group(1)), int(m.group(2))) if m else (0, None)
    return None


def classify(d):
    """(family, detail, provenance) for one run directory or archive."""
    build = _read(_first(d, "artifact-build.log"))
    boot = _read(_first(d, "artifact-boot.log"))
    fam, detail = classify_build(build)
    if fam:
        return fam, detail, "artifact-build.log"
    fam, detail = classify_boot(boot)
    if fam:
        return fam, detail, "artifact-boot.log"
    # Fallback: the run's own diagnostics. Weaker -- the run's last cycle and the scored tree
    # are not the same thing, so this says what the LOOP last saw, not what the scorer hit.
    log = _read(_first(d, "autobuilder.log", "autobuilder.log.gz"))
    if log:
        probes = re.findall(r"T1-EC: probe result=(\w+)", log)
        if probes and probes[-1] == "COMPILE_OK":
            return "healthy", None, "run-log (last probe COMPILE_OK — scorer may still disagree)"
        fam, detail = classify_build(log)
        if fam:
            return fam, detail, "run-log (weaker: the loop's view, not the scorer's)"
    return "unknown", None, "none"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("dirs", nargs="+", metavar="DIR")
    args = ap.parse_args(argv)

    counts, rows = Counter(), []
    for d in args.dirs:
        if not os.path.isdir(d):
            continue
        sc = outcome(d)
        if sc is not None and sc[0] > 0:
            # The artifact built and booted well enough to score. Not a failure, so its
            # diagnostics do not belong in a failure distribution. Reported against the
            # target's OWN denominator -- printing every row as /23 would be the same
            # defect one step later, a column whose meaning changes between rows.
            rows.append((os.path.basename(d.rstrip("/")),
                         f"not-a-failure ({sc[0]}/{sc[1]})", "", "summary.txt"))
            continue
        fam, detail, prov = classify(d)
        counts[fam] += 1
        rows.append((os.path.basename(d.rstrip("/")), fam, detail or "", prov))

    if not rows:
        print("FAMILY: runs=0 classified=0 unknown=0 top=none")
        return 1

    print(f"{'run':26s} {'family':24s} {'detail':22s} source")
    for name, fam, detail, prov in sorted(rows):
        print(f"{name:26s} {fam:24s} {detail[:22]:22s} {prov}")

    print("\ndistribution (failing runs only)")
    for fam, n in counts.most_common():
        print(f"  {n:3d}  {fam}")

    known = {f: n for f, n in counts.items() if f not in ("unknown", "healthy")}
    top = max(known, key=known.get) if known else "none"
    print(f"\nFAMILY: runs={len(rows)} classified={sum(known.values())} "
          f"unknown={counts['unknown']} top={top}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
