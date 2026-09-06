#!/usr/bin/env python3
"""
orientation_check.py — a truthful reading of the repository's orientation surface.

`.claude-memory/MEMORY.md` is the index every session loads before it reads anything else. It
offers several competing entry points for "what to do next", and nothing today counts them or
checks what they point at. This reports what the index currently says, so ORIENT-01's contract
(exactly one next-work pointer, and no pointer targeting a file whose own header declares its
ordering superseded) can be measured rather than assumed.

Two things it reports, each one a real defect this instrument exists to catch:

  NEXT-WORK POINTERS   an index line whose text contains the phrase "start here" (matched
                        case-insensitively) is a candidate entry point. The contract is exactly
                        one. Zero is `no-next-pointer`; two or more is a single
                        `multiple-next-pointers` finding naming every offending line.

  TARGET RESOLUTION     every index line names a file under `.claude-memory/`. A target escaping
                        that directory is refused before it is opened (`escaping-target`); a
                        target that does not exist is `missing-target`; a target whose header
                        region declares its own ordering superseded is `superseded-pointer-target`
                        when reached by a next-work pointer, or `superseded-index-target`
                        (non-blocking; see BLOCKING_KINDS) when reached by an ordinary index
                        line.

  FRESHNESS             every index line ends with a review stamp, `(reviewed YYYY-MM-DD)`,
                        recording when the line was last checked against the file it points at.
                        A line with no stamp is `unstamped-index-line`; a line whose stamp does
                        not parse or that carries more than one is `malformed-stamp`; a stamp
                        strictly earlier than its target's newest dated entry is
                        `stale-index-line`. A target with no dated entry never makes a line
                        stale. `--provenance-rev <rev>` reports, per line, whether the line's own
                        last edit at that revision is recent enough to stamp without an
                        individual re-read (see `provenance_map`/`provenance_report`).

Usage:
  orientation_check.py                       human reading: counts, pointers, findings
  orientation_check.py --json                machine-readable
  orientation_check.py --warnings-only       just the findings (cheap consistency check)
  orientation_check.py --strict              exit 1 if a blocking finding exists (else exit 0)
  orientation_check.py --provenance-rev REV  per-line provenance reading as of REV (always exit 0)
Exit code is 0 by default; orientation must never be what breaks a session.
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import time

DEFAULT_MEMORY = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                              ".claude-memory", "MEMORY.md")
INDEX_LINE = re.compile(r"^- `([^`]+\.md)`")
POINTER_PHRASE = re.compile(r"start here", re.IGNORECASE)
SUPERSEDED_TOKEN = "SUPERSEDED"
# The section heading that must carry the scope boundary between the product queue and the
# development-process axis (D-15: headed "## Orientation", not with the pointer phrase itself,
# so the heading does not count as a second pointer under D-01's case-insensitive match).
BOUNDARY_HEADING = "## Orientation"
# The four strings the boundary section must contain: the two surface paths (matched exactly)
# and the two surface names (matched case-insensitively) -- see _boundary_token_present.
#
# 2026-09-08: the process surface was `.planning/ROADMAP.md` until the milestone was cut and the
# whole directory deleted. This check then FAILED ON ITS OWN SUBJECT -- it demanded a path that no
# longer existed, so removing the dead pointer from the index produced a finding instead of
# clearing one. A checker that names a surface outlives the surface; when one is retired, retire
# its token in the same change.
BOUNDARY_TOKENS = (
    ".claude-memory/roadmap.md",
    ".claude-memory/meta_process.md",
    "product queue",
    "development-process axis",
)
# Phase 5 requirement REC-03 owns the general case of a superseded file remaining a live pointer
# target; `superseded-index-target` is deliberately left out of this tuple (D-14) so an ordinary
# index line pointing at a superseded-ordering file is reported but does not close the strict gate.
#
# A four-digit year, two-digit month and two-digit day joined by hyphens. Comparing two ISO date
# strings lexicographically is the same order as comparing the dates themselves (D-06), so no
# date parsing is needed anywhere in this module -- a parse step would risk introducing a
# timezone or locale dependency that the plain string comparison cannot have.
# The format string a stamp is rendered with: a leading space, the literal keyword "reviewed",
# another space, the date, and a closing parenthesis. Defined first because STAMP (the reader)
# is derived from it, not written separately, so the reader and the writer (Task 3's backfill)
# cannot disagree about the grammar -- there is exactly one place this shape is spelled out.
STAMP_TEMPLATE = " (reviewed %s)"
ISO_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
# A trailing review stamp, built from STAMP_TEMPLATE by substituting a capturing group for the
# "%s" placeholder (validated separately against ISO_DATE so a malformed date is reported rather
# than silently accepted). Anchored at end of line so a date inside the summary prose -- e.g. the
# "(gate met 2026-07-09)" aside on the feedback_stabilize_before_tuning_signals.md line -- is
# never read as a stamp.
STAMP = re.compile(re.escape(STAMP_TEMPLATE).replace(re.escape("%s"), r"([^()]*)") + r"\s*$")
# Every "(reviewed ...)" token in a line, wherever it appears -- used to detect a line carrying
# more than one stamp, which STAMP alone (anchored at the end) cannot see past the last one.
_REVIEWED_TOKEN = re.compile(r"\(reviewed [^()]*\)")


def is_calendar_date(text):
    """True when `text` is a well-formed ISO year-month-day naming a day that exists. ISO_DATE
    matches digit SHAPE only, so "2026-99-99" satisfies it and would otherwise be read as a
    legitimate date that no real entry can ever be newer than. The date constructor is what
    rejects a month or day that does not exist. Every caller that needs "is this a date I can
    trust" goes through here -- the stamp reader, the malformed-stamp branch, and the
    newest-entry scan -- so the three cannot disagree about which tokens are well-formed.
    """
    if not ISO_DATE.fullmatch(text):
        return False
    try:
        datetime.date.fromisoformat(text)
    except ValueError:
        return False
    return True


BLOCKING_KINDS = ("multiple-next-pointers", "no-next-pointer",
                  "superseded-pointer-target", "missing-target", "escaping-target",
                  "missing-boundary-record", "incomplete-boundary-record",
                  "pointer-outside-boundary-section",
                  "unstamped-index-line", "malformed-stamp", "stale-index-line")


def resolve_target(memory_path, target):
    """Resolve an index target relative to the directory holding the index. Returns the
    resolved absolute path only when it stays under that directory (D-09); otherwise None,
    so the caller can emit `escaping-target` without ever opening the file. A leading path
    separator or any parent-directory segment is treated as escaping.
    """
    base_dir = os.path.abspath(os.path.dirname(memory_path))
    if target.startswith("/") or target.startswith("\\"):
        return None
    if ".." in re.split(r"[\\/]+", target):
        return None
    candidate = os.path.normpath(os.path.join(base_dir, target))
    if candidate != base_dir and not candidate.startswith(base_dir + os.sep):
        return None
    # The three checks above read the target as a STRING, and a string cannot show where a
    # symlink leads: a file sitting inside the directory whose link points out of it passes
    # every one of them, and the caller then opens it and echoes its content into a finding.
    # Resolving both sides is what makes the docstring's promise -- refused "without ever
    # opening the file" -- hold against a link and not merely against a written-out path.
    # realpath on a not-yet-existing candidate normalizes rather than raising, so a target
    # that is simply absent still reaches the `missing-target` branch below it.
    real_base = os.path.realpath(base_dir)
    real_candidate = os.path.realpath(candidate)
    if real_candidate != real_base and not real_candidate.startswith(real_base + os.sep):
        return None
    return candidate


def header_region(text):
    """The front-matter block plus everything up to, but excluding, the first line beginning
    with `## ` that occurs after the closing front-matter delimiter. When there is no front
    matter, run to the first such heading from the top. When there is no such heading at all,
    the whole file is the header region.
    """
    lines = text.splitlines()
    start = 0
    if lines and lines[0].strip() == "---":
        for i in range(1, len(lines)):
            if lines[i].strip() == "---":
                start = i + 1
                break
    for i in range(start, len(lines)):
        if lines[i].startswith("## "):
            return "\n".join(lines[:i])
    return "\n".join(lines)


def boundary_section(text):
    """The body of the section introduced by a line reading exactly BOUNDARY_HEADING, running
    to the next line beginning with a level-two heading or to end of file. None when no such
    heading exists (an exact match only -- "## Orientation and more" does not count).
    """
    lines = text.splitlines()
    heading_idx = None
    for i, line in enumerate(lines):
        if line == BOUNDARY_HEADING:
            heading_idx = i
            break
    if heading_idx is None:
        return None
    end_idx = len(lines)
    for i in range(heading_idx + 1, len(lines)):
        if lines[i].startswith("## "):
            end_idx = i
            break
    return "\n".join(lines[heading_idx + 1:end_idx])


def _boundary_token_present(section, token):
    """The two surface paths match exactly; the two surface names match case-insensitively."""
    if token.startswith(".claude-memory/") or token.startswith(".planning/"):
        return token in section
    return token.lower() in section.lower()


def stamp_of(text):
    """The ISO date from a trailing review stamp in one line of text, or None. A line carrying
    no "(reviewed ...)" token, more than one such token, or a single trailing one whose content
    is not a well-formed ISO year-month-day, all return None here -- `find_findings` is what
    tells those three apart (`unstamped-index-line` vs `malformed-stamp`); this function only
    ever returns a date it can trust. Anchored at end of line (D-06's own contract), so a date
    that happens to appear earlier in the line's prose is never mistaken for the stamp.
    """
    stripped = text.rstrip()
    if len(_REVIEWED_TOKEN.findall(stripped)) != 1:
        return None
    m = STAMP.search(stripped)
    if not m:
        return None
    date = m.group(1)
    if is_calendar_date(date):
        return date
    return None


def newest_entry_date(text):
    """The lexicographically greatest ISO year-month-day token anywhere in text, or None when
    the text carries no such token. String comparison over ISO dates is the same order as date
    comparison (see the comment on ISO_DATE), so the ORDERING needs no date parsing -- but
    admission does, since a token has to name a real day before its position in that order
    means anything.

    Shape-valid tokens that name no real day are discarded first. Without that, a stray
    "2026-99-99" anywhere in a target file sorts above every genuine entry and silently makes
    the index line pointing at it look stale forever -- the same invariant `is_calendar_date`
    exists for, applied at the reading end rather than the stamp end.
    """
    tokens = [t for t in ISO_DATE.findall(text) if is_calendar_date(t)]
    return max(tokens) if tokens else None


def provenance_map(memory_path, rev):
    """{target base name: last-edited-date} read from `git blame --porcelain <rev> -- <path>`
    against the index at revision `rev`. Keyed by the target's base name rather than its full
    path, because the index has moved targets between directories before (the pre-compaction
    index lived under `memory/`, the current one under `.claude-memory/`) and the base name is
    what survives that move. Returns an empty mapping, never raises, when git fails or `rev`
    does not resolve -- provenance is best-effort evidence, not a hard dependency.
    """
    directory = os.path.dirname(os.path.abspath(memory_path))
    filename = os.path.basename(memory_path)
    try:
        completed = subprocess.run(
            ["git", "blame", "--porcelain", rev, "--", filename],
            cwd=directory, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except OSError:
        return {}
    if completed.returncode != 0:
        return {}
    text = completed.stdout.decode("utf-8", errors="replace")
    lines = text.splitlines()
    commit_dates = {}
    mapping = {}
    current_hash = None
    i = 0
    header = re.compile(r"^([0-9a-f]{40}) \d+ \d+(?: \d+)?$")
    while i < len(lines):
        m = header.match(lines[i])
        if not m:
            i += 1
            continue
        current_hash = m.group(1)
        i += 1
        while i < len(lines) and not lines[i].startswith("\t"):
            if lines[i].startswith("committer-time "):
                try:
                    ts = int(lines[i].split(" ", 1)[1])
                    commit_dates[current_hash] = time.strftime("%Y-%m-%d", time.gmtime(ts))
                except (ValueError, IndexError):
                    pass
            i += 1
        if i < len(lines):
            content = lines[i][1:]
            entry_match = INDEX_LINE.match(content)
            date = commit_dates.get(current_hash)
            if entry_match and date is not None:
                base = os.path.basename(entry_match.group(1))
                if base not in mapping or date > mapping[base]:
                    mapping[base] = date
            i += 1
    return mapping


def provenance_report(entries, memory_path, rev, out, as_json=False):
    """One row per index line: its line number, target, the target's newest dated entry, the
    provenance date `provenance_map` records for it (or None), the line's current stamp (or
    None), and whether the line is a candidate the backfill may not stamp blind. A line is a
    candidate when its target's newest dated entry is strictly later than the line's provenance
    date, or when the line has no provenance date at all (D-07) -- the same rule `stale-index-line`
    uses for a stamp, applied here to the provenance date in place of the stamp. Writes the
    human or JSON reading to `out` and returns the row list; always leaves the caller free to
    return 0 (provenance is a report, not a gate).
    """
    prov = provenance_map(memory_path, rev)
    rows = []
    for e in entries:
        target = e["target"]
        base = os.path.basename(target)
        provenance_date = prov.get(base)
        resolved = resolve_target(memory_path, target)
        newest = None
        if resolved is not None and os.path.isfile(resolved):
            with open(resolved, errors="replace") as fh:
                newest = newest_entry_date(fh.read())
        if provenance_date is None:
            candidate = True
        else:
            candidate = newest is not None and newest > provenance_date
        rows.append({
            "line": e["line"], "target": target, "newest_entry_date": newest,
            "provenance": provenance_date, "stamp": stamp_of(e["text"]), "candidate": candidate,
        })
    candidate_count = sum(1 for r in rows if r["candidate"])
    if as_json:
        out.write(json.dumps({"provenance": rows, "candidate_count": candidate_count},
                              indent=1) + "\n")
    else:
        out.write("provenance as of revision %s:\n" % rev)
        for r in rows:
            out.write(
                "  line=%d target=%s newest=%s provenance=%s stamp=%s candidate=%s\n"
                % (r["line"], r["target"], r["newest_entry_date"] or "none",
                   r["provenance"] or "none", r["stamp"] or "none", r["candidate"]))
        out.write("candidate set: %d\n" % candidate_count)
    return rows


def find_pointer_lines(text):
    """1-based line numbers of every physical line in the document carrying the next-work
    pointer phrase, wherever it appears -- not only on an index-format (backtick-filename)
    line. The Orientation section's routing statement is a next-work pointer even though it
    does not itself index a single file, so the contract is document-wide, not entry-scoped.
    """
    return [n for n, line in enumerate(text.splitlines(), 1) if POINTER_PHRASE.search(line)]


def parse_index(path):
    """[{line, target, text}] in file order, one per index line matching INDEX_LINE."""
    with open(path, errors="replace") as fh:
        lines = fh.read().splitlines()
    entries = []
    for n, line in enumerate(lines, 1):
        m = INDEX_LINE.match(line)
        if m:
            entries.append({"line": n, "target": m.group(1), "text": line})
    return entries


def _stamp_finding_dict(kind, entry, stamp_date, newest):
    """Build the finding dict for one of the three review-stamp kinds, with a detail message
    naming the dates involved so a report reader does not have to re-derive them."""
    if kind == "unstamped-index-line":
        detail = "no review stamp on this index line"
    elif kind == "malformed-stamp":
        detail = "review stamp is missing, duplicated, or not a well-formed ISO date"
    else:
        detail = "stamp %s is earlier than the target's newest dated entry %s" \
                  % (stamp_date, newest)
    return {"kind": kind, "line": entry["line"], "target": entry["target"], "detail": detail}


def find_findings(entries, memory_path=DEFAULT_MEMORY):
    """[{kind, line, target, detail}] — the next-work pointer contract (D-01/D-08) plus, per
    entry, what its target actually resolves to.

    Exactly one *line in the document* (not only an index-format line -- see
    `find_pointer_lines`, widened for the Orientation section's routing statement, which is a
    pointer but indexes no single file) carrying POINTER_PHRASE is the contract. Zero emits
    `no-next-pointer`; two or more emits a single `multiple-next-pointers` finding whose
    `detail` lists every offending line number in file order.

    Then, in this order per entry: `escaping-target` for a target `resolve_target` refuses
    (never opened, D-09); `missing-target` for a resolved target that does not exist;
    `superseded-pointer-target` for a pointer entry whose target's header region carries
    SUPERSEDED_TOKEN; `superseded-index-target` for a non-pointer entry whose target's header
    region carries it (non-blocking, per D-14 -- see BLOCKING_KINDS).

    Also per entry, independent of target resolution: at most one of `unstamped-index-line` (no
    review stamp), `malformed-stamp` (a stamp that does not parse, or more than one on the line),
    or `stale-index-line` (a well-formed stamp strictly earlier than the target's newest dated
    entry), in that precedence order. A target with no dated entry, or one that could not be
    read (escaping/missing), never yields `stale-index-line` -- there is nothing to be stale
    against.

    Then, once for the whole document: `missing-boundary-record` when no BOUNDARY_HEADING
    section exists; otherwise `incomplete-boundary-record` when the section is missing one or
    more BOUNDARY_TOKENS, `detail` naming every missing token; otherwise
    `pointer-outside-boundary-section` when the pointer count is exactly 1 and that pointer's
    line falls outside the section's line span. The third kind is suppressed when the pointer
    count is not exactly 1, because `no-next-pointer`/`multiple-next-pointers` is already the
    finding and a second one would be noise.
    """
    findings = []
    try:
        with open(memory_path, errors="replace") as fh:
            memory_text = fh.read()
    except (IOError, OSError):
        memory_text = None

    if memory_text is not None:
        pointer_lines = find_pointer_lines(memory_text)
    else:
        # Degraded fallback if the index cannot be reread (already opened once by parse_index,
        # so this path is not expected in practice) -- entry-scoped, the pre-widening contract.
        pointer_lines = [e["line"] for e in entries if POINTER_PHRASE.search(e["text"])]

    if len(pointer_lines) == 0:
        findings.append({
            "kind": "no-next-pointer", "line": None, "target": None,
            "detail": "no next-work pointer found among %d index lines" % len(entries)})
    elif len(pointer_lines) > 1:
        findings.append({
            "kind": "multiple-next-pointers", "line": None, "target": None,
            "detail": "lines %s all carry the next-work pointer phrase"
                      % ", ".join(str(n) for n in pointer_lines)})

    if memory_text is not None:
        section = boundary_section(memory_text)
        if section is None:
            findings.append({
                "kind": "missing-boundary-record", "line": None, "target": None,
                "detail": "no '%s' section found in the index" % BOUNDARY_HEADING})
        else:
            missing_tokens = [t for t in BOUNDARY_TOKENS if not _boundary_token_present(section, t)]
            if missing_tokens:
                findings.append({
                    "kind": "incomplete-boundary-record", "line": None, "target": None,
                    "detail": "missing required token(s): %s" % ", ".join(missing_tokens)})
            elif len(pointer_lines) == 1:
                mem_lines = memory_text.splitlines()
                heading_idx = next(i for i, ln in enumerate(mem_lines) if ln == BOUNDARY_HEADING)
                section_end_line = len(mem_lines)
                for i in range(heading_idx + 1, len(mem_lines)):
                    if mem_lines[i].startswith("## "):
                        section_end_line = i
                        break
                section_start_line = heading_idx + 1
                pointer_line = pointer_lines[0]
                if not (section_start_line <= pointer_line <= section_end_line):
                    findings.append({
                        "kind": "pointer-outside-boundary-section", "line": pointer_line,
                        "target": None,
                        "detail": "next-work pointer at line %d falls outside the %s section "
                                  "(lines %d-%d)"
                                  % (pointer_line, BOUNDARY_HEADING, section_start_line,
                                     section_end_line)})

    for e in entries:
        target = e["target"]

        stripped_text = e["text"].rstrip()
        stamp_tokens = _REVIEWED_TOKEN.findall(stripped_text)
        stamp_finding = None
        stamp_date = None
        if len(stamp_tokens) == 0:
            stamp_finding = "unstamped-index-line"
        else:
            m = STAMP.search(stripped_text)
            if len(stamp_tokens) > 1 or not m or not is_calendar_date(m.group(1)):
                stamp_finding = "malformed-stamp"
            else:
                stamp_date = m.group(1)

        resolved = resolve_target(memory_path, target)
        if resolved is None:
            findings.append({
                "kind": "escaping-target", "line": e["line"], "target": target,
                "detail": "target escapes the directory holding the index; not opened"})
            if stamp_finding:
                findings.append(_stamp_finding_dict(stamp_finding, e, stamp_date, None))
            continue
        if not os.path.isfile(resolved):
            findings.append({
                "kind": "missing-target", "line": e["line"], "target": target,
                "detail": "target file not found under .claude-memory/"})
            if stamp_finding:
                findings.append(_stamp_finding_dict(stamp_finding, e, stamp_date, None))
            continue
        with open(resolved, errors="replace") as fh:
            target_text = fh.read()

        newest = newest_entry_date(target_text)
        if stamp_finding is None and newest is not None and stamp_date < newest:
            stamp_finding = "stale-index-line"
        if stamp_finding:
            findings.append(_stamp_finding_dict(stamp_finding, e, stamp_date, newest))

        region = header_region(target_text)
        if SUPERSEDED_TOKEN in region:
            matched_line = next((ln for ln in region.splitlines() if SUPERSEDED_TOKEN in ln), "")
            detail = matched_line.strip()[:200]
            is_pointer = POINTER_PHRASE.search(e["text"]) is not None
            kind = "superseded-pointer-target" if is_pointer else "superseded-index-target"
            findings.append({"kind": kind, "line": e["line"], "target": target, "detail": detail})
    return findings


def report(entries, findings, pointer_lines, out):
    """Write the human reading of entries and findings to `out`. `pointer_lines` is the
    document-wide line-number list from `find_pointer_lines` (widened; not entry-scoped -- a
    pointer line need not index a single file). Entries give a target to display when the
    pointer line happens to also be an index line; otherwise no target is shown.
    """
    targets_by_line = {e["line"]: e["target"] for e in entries}
    out.write("index lines: %d\n" % len(entries))
    out.write("next-work pointers: %d\n" % len(pointer_lines))
    for n in pointer_lines:
        out.write("  %d %s\n" % (n, targets_by_line.get(n, "")))
    counts_by_kind = {}
    for f in findings:
        counts_by_kind[f["kind"]] = counts_by_kind.get(f["kind"], 0) + 1
    out.write("findings: %d\n" % len(findings))
    for kind in sorted(counts_by_kind):
        out.write("  %s: %d\n" % (kind, counts_by_kind[kind]))
    for f in findings:
        out.write("  [%s] line=%s target=%s -- %s\n"
                  % (f["kind"], f["line"], f["target"], f["detail"]))


def main(argv):
    ap = argparse.ArgumentParser(description="Read the orientation surface in MEMORY.md.")
    ap.add_argument("--memory", default=DEFAULT_MEMORY)
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--strict", action="store_true", help="exit 1 if a blocking finding exists")
    ap.add_argument("--warnings-only", action="store_true", help="only the findings")
    ap.add_argument("--provenance-rev",
                     help="report, per index line, whether its last edit at this git revision "
                          "is recent enough to stamp without an individual re-read; always "
                          "exits 0, even when the revision does not resolve")
    args = ap.parse_args(argv)

    if not os.path.isfile(args.memory):
        sys.stderr.write("orientation_check: no memory index at %s\n" % args.memory)
        return 0

    entries = parse_index(args.memory)

    if args.provenance_rev:
        provenance_report(entries, args.memory, args.provenance_rev, sys.stdout, args.json)
        return 0

    findings = find_findings(entries, args.memory)
    with open(args.memory, errors="replace") as fh:
        pointer_lines = find_pointer_lines(fh.read())
    counts_by_kind = {}
    for f in findings:
        counts_by_kind[f["kind"]] = counts_by_kind.get(f["kind"], 0) + 1

    if args.json:
        payload = {
            "index_lines": len(entries),
            "next_pointers": {"count": len(pointer_lines), "lines": pointer_lines},
            "findings": findings,
            "counts_by_kind": counts_by_kind,
        }
        print(json.dumps(payload, indent=1))
    elif args.warnings_only:
        # Just the findings (per the flag's own docstring) -- no kind-count summary line, so a
        # kind's name appears at most once per finding rather than once per finding plus once
        # in a summary total that would double-count a single instance.
        for f in findings:
            sys.stdout.write("  [%s] line=%s target=%s -- %s\n"
                             % (f["kind"], f["line"], f["target"], f["detail"]))
    else:
        report(entries, findings, pointer_lines, sys.stdout)

    if args.strict and any(f["kind"] in BLOCKING_KINDS for f in findings):
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as exc:  # orientation must never be what breaks a session
        sys.stderr.write("orientation_check: %s\n" % exc)
        sys.exit(0)
