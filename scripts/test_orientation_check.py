#!/usr/bin/env python3
"""
Durable tests for orientation_check.py (the orientation-surface reading). Pure stdlib:
run with `python3 scripts/test_orientation_check.py`.

`test_live_index_matches_the_audited_numbers_2026_09_08` is pinned to the real
`.claude-memory/MEMORY.md`. Re-audited 2026-09-08 after plan 02-02 Task 2: 96 index lines
(unchanged), 0 entry-scoped pointers (the four former markers at lines 21/31/32/33 are demoted),
1 document-wide pointer at line 6 (the Orientation section's routing statement, which indexes no
single file). The test carries the audit date in its name rather than a plain description — a
later change to the index should make this test fail loudly rather than silently go stale.
"""
import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "orientation_check", os.path.join(os.path.dirname(__file__), "orientation_check.py"))
oc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(oc)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIVE_MEMORY = os.path.join(REPO_ROOT, ".claude-memory", "MEMORY.md")


def write_index(tmpdir, lines, targets=None, skip_targets=None):
    """Write a synthetic MEMORY.md carrying exactly the given index lines. Two header lines
    (a title and a blank line) precede the index, matching the shape of the real file.

    Every backtick-quoted `*.md` target referenced in `lines` is auto-stubbed with trivial
    content in `tmpdir`, so a test that is not exercising target resolution does not
    accidentally trip `missing-target`. `targets` supplies real content for a target (to
    exercise header-region checks); `skip_targets` names a target to leave absent (to
    exercise `missing-target`) or one that must never be created at all (an escaping path).
    """
    path = os.path.join(tmpdir, "MEMORY.md")
    with open(path, "w") as fh:
        fh.write("# Memory\n\n")
        fh.write("\n".join(lines) + "\n")
    targets = targets or {}
    skip_targets = skip_targets or set()
    for name, content in targets.items():
        with open(os.path.join(tmpdir, name), "w") as fh:
            fh.write(content)
    referenced = set()
    for line in lines:
        m = oc.INDEX_LINE.match(line)
        if m:
            referenced.add(m.group(1))
    for name in referenced - set(targets) - set(skip_targets):
        with open(os.path.join(tmpdir, name), "w") as fh:
            fh.write("stub content for tests, no tokens of interest\n")
    return path


class ParseIndexTest(unittest.TestCase):
    def test_parses_target_and_text_with_both_punctuations(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_index(d, [
                "- `a.md` -- em-dash-style punctuation",
                "- `b.md`: colon punctuation",
            ])
            entries = oc.parse_index(path)
        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0]["target"], "a.md")
        self.assertEqual(entries[1]["target"], "b.md")
        self.assertIn("colon punctuation", entries[1]["text"])

    def test_line_numbers_are_one_based_and_in_file_order(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_index(d, ["- `a.md` -- one", "- `b.md` -- two"])
            entries = oc.parse_index(path)
        # write_index precedes the index with a title line and a blank line.
        self.assertEqual([e["line"] for e in entries], [3, 4])

    def test_a_line_not_matching_the_index_shape_is_not_a_record(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_index(d, ["- `a.md` -- a real entry", "  not an index line"])
            entries = oc.parse_index(path)
        self.assertEqual(len(entries), 1)


class PointerContractTest(unittest.TestCase):
    def _findings_for(self, tmpdir, lines):
        path = write_index(tmpdir, lines)
        entries = oc.parse_index(path)
        return entries, oc.find_findings(entries, path)

    def test_lowercase_pointer_phrase_counts(self):
        with tempfile.TemporaryDirectory() as d:
            entries, _ = self._findings_for(d, ["- `a.md` -- start here for something"])
        pointers = [e for e in entries if oc.POINTER_PHRASE.search(e["text"])]
        self.assertEqual(len(pointers), 1)

    def test_exactly_one_pointer_emits_no_multiple_finding(self):
        with tempfile.TemporaryDirectory() as d:
            _, findings = self._findings_for(d, [
                "- `a.md` -- Start Here for triage",
                "- `b.md` -- an ordinary line",
            ])
        self.assertNotIn("multiple-next-pointers", [f["kind"] for f in findings])

    def test_zero_pointers_emits_no_next_pointer_finding(self):
        with tempfile.TemporaryDirectory() as d:
            _, findings = self._findings_for(d, ["- `a.md` -- an ordinary line"])
        self.assertIn("no-next-pointer", [f["kind"] for f in findings])

    def test_two_pointers_emit_one_finding_naming_both_lines(self):
        with tempfile.TemporaryDirectory() as d:
            _, findings = self._findings_for(d, [
                "- `a.md` -- start here for one thing",
                "- `b.md` -- start here for another thing",
            ])
        multi = [f for f in findings if f["kind"] == "multiple-next-pointers"]
        self.assertEqual(len(multi), 1)
        self.assertIn("3", multi[0]["detail"])
        self.assertIn("4", multi[0]["detail"])

    def test_a_plain_prose_line_carries_the_pointer_without_indexing_a_file(self):
        # A routing statement (the Orientation section's marker) is a next-work pointer even
        # though it is not itself a backtick-filename index line -- deviation, Task 2: widened
        # from entry-scoped to document-wide, see find_pointer_lines.
        with tempfile.TemporaryDirectory() as d:
            entries, findings = self._findings_for(d, [
                "**START HERE** -- not an index line, just a routing sentence.",
                "- `a.md` -- an ordinary line",
            ])
        self.assertEqual(len(entries), 1)  # the prose line is not counted as an index entry
        self.assertNotIn("no-next-pointer", [f["kind"] for f in findings])
        self.assertNotIn("multiple-next-pointers", [f["kind"] for f in findings])


class LiveIndexTest(unittest.TestCase):
    def test_live_index_matches_the_audited_numbers_2026_09_08(self):
        # Re-audited after plan 02-03 Task 3 landed the freshness convention statement (four
        # lines prepended to the top-of-file index-only line, shifting the Orientation section's
        # pointer from line 6 to line 10). A later change to the index should make this fail
        # loudly rather than silently go stale.
        #
        # 96 -> 97 on 2026-09-08: one index line added for
        # feedback_flag_ceremony_cost_early.md. Counted, not loosened -- this assertion earned
        # its keep the same day by catching that the line had first been written in the
        # auto-memory's markdown-link format, which INDEX_LINE does not match, so the checker
        # could not see it at all. A silent-coverage hole is exactly what a pinned count is for.
        #
        # 97 -> 96 later on 2026-09-08: the GSD isolation-sentinel line was de-indexed when the
        # milestone cut deleted the apparatus it described, and its memory file removed with it.
        # The pin did its job again -- it is what surfaced that the index had moved at all.
        #
        # 96 -> 97 the same evening: project_ticket_premises_expire.md indexed. Third movement in
        # one day, each one caught here rather than noticed later, which is the whole argument for
        # pinning a count nobody would otherwise check.
        #
        # 97 -> 98 on 2026-09-10: feedback_baseline_before_accuracy.md indexed by the T6-R session,
        # which did not update this pin -- so the suite carried a failing test into 2026-09-11 and
        # the movement was recorded nowhere. Identified by diffing the index against the commit
        # that last set this number rather than by bumping it: six of the seven changed lines were
        # restamped review dates and exactly one was a new entry. The pin did its job; the session
        # that moved the index did not do its half, which is the failure mode worth naming.
        entries = oc.parse_index(LIVE_MEMORY)
        self.assertEqual(len(entries), 98)
        entry_pointers = [e["line"] for e in entries if oc.POINTER_PHRASE.search(e["text"])]
        self.assertEqual(entry_pointers, [])  # the four former markers are all demoted now
        with open(LIVE_MEMORY, errors="replace") as fh:
            doc_pointers = oc.find_pointer_lines(fh.read())
        self.assertEqual(doc_pointers, [10])  # the Orientation section's routing statement

    def test_the_what_s_next_quote_is_not_a_pointer(self):
        # Was pinned to a hardcoded line number (110); re-pinned by content after plan 02-03
        # Task 3 shifted every line number by +4 (the freshness convention statement), since a
        # hardcoded number silently drifts onto an unrelated line instead of failing loudly.
        entries = oc.parse_index(LIVE_MEMORY)
        quote_line = next(e for e in entries if "what's next" in e["text"])
        self.assertFalse(oc.POINTER_PHRASE.search(quote_line["text"]))


class MainCliTest(unittest.TestCase):
    def _run(self, argv):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = oc.main(argv)
        return rc, buf.getvalue()

    def test_default_exit_is_zero_regardless_of_findings(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_index(d, ["- `a.md` -- an ordinary line"])  # zero pointers -> a finding
            rc, _ = self._run(["--memory", path])
        self.assertEqual(rc, 0)

    def test_strict_exits_1_when_a_blocking_finding_exists(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_index(d, ["- `a.md` -- an ordinary line"])  # no-next-pointer, blocking
            rc, _ = self._run(["--memory", path, "--strict"])
        self.assertEqual(rc, 1)

    def test_strict_exits_0_when_no_blocking_finding(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_index(d, [
                "## Orientation",
                "- `a.md` -- start here: names .claude-memory/roadmap.md, "
                ".claude-memory/meta_process.md, the product queue, and the development-process axis"
                " (reviewed 2026-01-01)",
                "## Project",
            ])
            rc, _ = self._run(["--memory", path, "--strict"])
        self.assertEqual(rc, 0)

    def test_missing_index_path_does_not_raise_and_exits_zero(self):
        rc, _ = self._run(["--memory", "/nonexistent/MEMORY.md"])
        self.assertEqual(rc, 0)

    def test_json_output_is_idempotent_over_an_unchanged_index(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_index(d, ["- `a.md` -- start here for the one thing"])
            _, out1 = self._run(["--memory", path, "--json"])
            _, out2 = self._run(["--memory", path, "--json"])
        self.assertEqual(out1, out2)

    def test_json_reports_index_lines_and_next_pointers(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_index(d, [
                "- `a.md` -- start here for the one thing",
                "- `b.md` -- an ordinary line",
            ])
            _, out = self._run(["--memory", path, "--json"])
        payload = json.loads(out)
        self.assertEqual(payload["index_lines"], 2)
        self.assertEqual(payload["next_pointers"]["count"], 1)
        self.assertEqual(payload["next_pointers"]["lines"], [3])


class HeaderRegionTest(unittest.TestCase):
    def test_front_matter_plus_body_up_to_first_heading_after_it_closes(self):
        text = ("---\n"
                "name: x\n"
                "description: \"SUPERSEDED note lives in front matter\"\n"
                "---\n"
                "\n"
                "intro line\n"
                "\n"
                "## First Heading\n"
                "body text SUPERSEDED here does not count\n")
        region = oc.header_region(text)
        self.assertIn("SUPERSEDED note lives in front matter", region)
        self.assertNotIn("body text SUPERSEDED here does not count", region)

    def test_token_below_the_first_heading_is_excluded(self):
        text = ("# Title\n"
                "\n"
                "intro, no token here\n"
                "\n"
                "## Heading\n"
                "SUPERSEDED appears only below the heading\n")
        region = oc.header_region(text)
        self.assertNotIn("SUPERSEDED", region)

    def test_no_front_matter_runs_to_first_heading_from_the_top(self):
        text = ("# Title\n"
                "SUPERSEDED at the top, no front matter\n"
                "\n"
                "## Heading\n"
                "more text below\n")
        region = oc.header_region(text)
        self.assertIn("SUPERSEDED at the top, no front matter", region)
        self.assertNotIn("more text below", region)

    def test_no_heading_at_all_makes_the_whole_file_the_header_region(self):
        text = ("# Title\n"
                "some text\n"
                "more text SUPERSEDED near the end\n"
                "the final line\n")
        region = oc.header_region(text)
        self.assertIn("SUPERSEDED near the end", region)
        self.assertIn("the final line", region)


class TargetResolutionTest(unittest.TestCase):
    def test_missing_target_is_reported(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_index(d, ["- `ghost.md` -- points at nothing"],
                               skip_targets={"ghost.md"})
            entries = oc.parse_index(path)
            findings = oc.find_findings(entries, path)
        self.assertIn("missing-target", [f["kind"] for f in findings])

    def test_escaping_targets_are_refused_and_never_opened(self):
        with tempfile.TemporaryDirectory() as d:
            path = write_index(d, [
                "- `../secret.md` -- parent-directory escape",
                "- `/etc/oc-test-canary.md` -- absolute-path escape",
            ], skip_targets={"../secret.md", "/etc/oc-test-canary.md"})
            entries = oc.parse_index(path)
            # If the implementation ever opened either target, this would raise instead of
            # returning a finding -- neither file exists, by construction.
            findings = oc.find_findings(entries, path)
        kinds = [f["kind"] for f in findings]
        self.assertEqual(kinds.count("escaping-target"), 2)

    def test_a_symlink_escaping_the_directory_is_refused_and_its_content_never_read(self):
        # The string escape the two cases above cover is visible in the target text. This one
        # is not: `link.md` is spelled as an ordinary local target and only the filesystem
        # knows it leads out of the directory. Asserting `escaping-target` alone would pass on
        # an implementation that opened the file first and refused afterwards, so the canary
        # assertion -- the linked file's content appears in NO finding, not merely not in
        # `detail` -- is what pins the "never opened" half of the contract.
        canary = "CANARY-CONTENT-THAT-MUST-NEVER-BE-READ-BY-THE-CHECKER"
        with tempfile.TemporaryDirectory() as outside:
            secret = os.path.join(outside, "secret.md")
            # SUPERSEDED_TOKEN is matched case-sensitively, and the header-region line carrying
            # it is what gets copied into the finding's `detail`. Putting the canary on THAT
            # line is what makes the assertion below able to fail: with the token lowercased,
            # or on a different line, no finding quotes the file and the canary check passes
            # vacuously against the very implementation it is meant to catch.
            with open(secret, "w") as fh:
                fh.write("# Secret\n\n%s ordering, and here is the %s\n"
                         % (oc.SUPERSEDED_TOKEN, canary))
            with tempfile.TemporaryDirectory() as d:
                path = write_index(
                    d, ["- `link.md` -- spelled local, resolves out of the directory"],
                    skip_targets={"link.md"})
                try:
                    os.symlink(secret, os.path.join(d, "link.md"))
                except (OSError, NotImplementedError) as exc:
                    self.skipTest("filesystem refused symlink creation: %s" % exc)
                entries = oc.parse_index(path)
                findings = oc.find_findings(entries, path)
        self.assertIn("escaping-target", [f["kind"] for f in findings])
        self.assertFalse([f for f in findings if canary in json.dumps(f)],
                         "linked file content reached a finding; the target was opened")

    def test_a_symlink_staying_inside_the_directory_is_still_resolved(self):
        # The guard rejects where a link LEAVES the directory, not that a link exists. Without
        # this, tightening `resolve_target` could silently start refusing ordinary in-directory
        # links and no test would notice.
        with tempfile.TemporaryDirectory() as d:
            path = write_index(d, [
                "## Orientation",
                "- `a.md` -- start here: names .claude-memory/roadmap.md, "
                ".claude-memory/meta_process.md, the product queue, and the development-process axis"
                " (reviewed 2026-01-01)",
                "## Project",
                "- `alias.md` -- a link to a sibling inside the directory (reviewed 2026-01-01)",
            ], targets={"real.md": "# Real\n\nnothing of interest here\n"},
               skip_targets={"alias.md"})
            try:
                os.symlink(os.path.join(d, "real.md"), os.path.join(d, "alias.md"))
            except (OSError, NotImplementedError) as exc:
                self.skipTest("filesystem refused symlink creation: %s" % exc)
            entries = oc.parse_index(path)
            findings = oc.find_findings(entries, path)
        self.assertEqual(findings, [])

    def test_a_present_target_with_no_superseded_token_is_quiet(self):
        with tempfile.TemporaryDirectory() as d:
            # A pointer line inside a complete Orientation section is included so neither the
            # pointer-count contract nor the boundary-record contract fires, isolating this
            # assertion to the target-resolution findings. Both lines carry a stamp (targets
            # have no dated entry of their own, so no stamp value can be stale here) to isolate
            # the assertion from the freshness findings this task adds.
            path = write_index(d, [
                "## Orientation",
                "- `a.md` -- start here: names .claude-memory/roadmap.md, "
                ".claude-memory/meta_process.md, the product queue, and the development-process axis"
                " (reviewed 2026-01-01)",
                "## Project",
                "- `plain.md` -- an ordinary target (reviewed 2026-01-01)",
            ], targets={"plain.md": "# Plain\n\nnothing of interest here\n"})
            entries = oc.parse_index(path)
            findings = oc.find_findings(entries, path)
        self.assertEqual(findings, [])


class LiveTargetResolutionTest(unittest.TestCase):
    """Pins the two real superseded-ordering instances by name, against the live
    `.claude-memory/` directory, per the read_first precision distinction: the pointer target
    (`project_session_agenda.md`) is blocking, the ordinary-index target
    (`project_tests_rotate_live_logs.md`) is not, and the two body-only mentions
    (`roadmap.md`, `project_resume_state.md`) stay quiet."""

    def _entries(self):
        return [
            {"line": 1, "target": "project_session_agenda.md",
             "text": "- `project_session_agenda.md`: START HERE for what to do next"},
            {"line": 2, "target": "project_tests_rotate_live_logs.md",
             "text": "- `project_tests_rotate_live_logs.md`: an ordinary index line"},
            {"line": 3, "target": "roadmap.md",
             "text": "- `roadmap.md`: an ordinary index line"},
            {"line": 4, "target": "project_resume_state.md",
             "text": "- `project_resume_state.md`: an ordinary index line"},
        ]

    def test_pointer_target_is_the_blocking_kind(self):
        findings = oc.find_findings(self._entries(), LIVE_MEMORY)
        by_target = {f["target"]: f["kind"] for f in findings}
        self.assertEqual(by_target.get("project_session_agenda.md"),
                         "superseded-pointer-target")

    def test_ordinary_index_target_is_the_non_blocking_kind(self):
        findings = oc.find_findings(self._entries(), LIVE_MEMORY)
        by_target = {f["target"]: f["kind"] for f in findings}
        self.assertEqual(by_target.get("project_tests_rotate_live_logs.md"),
                         "superseded-index-target")

    def test_body_only_mentions_stay_quiet(self):
        # Isolated to the superseded-detection kinds this test is actually about -- Task 1
        # (plan 02-03) adds unrelated freshness findings (unstamped-index-line) that would
        # otherwise also name these targets, since none of the four synthetic entries carries a
        # stamp.
        findings = oc.find_findings(self._entries(), LIVE_MEMORY)
        superseded_kinds = ("superseded-pointer-target", "superseded-index-target")
        superseded_targets = {f["target"] for f in findings if f["kind"] in superseded_kinds}
        self.assertNotIn("roadmap.md", superseded_targets)
        self.assertNotIn("project_resume_state.md", superseded_targets)

    def test_live_counts_by_kind_are_zero_two_zero_zero(self):
        # Re-audited after plan 02-02 Task 2: project_session_agenda.md is no longer a pointer
        # target (its marker is demoted), so it reclassifies from superseded-pointer-target to
        # the non-blocking superseded-index-target -- joining project_tests_rotate_live_logs.md.
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            oc.main(["--memory", LIVE_MEMORY, "--json"])
        counts = json.loads(buf.getvalue())["counts_by_kind"]
        self.assertEqual(counts.get("superseded-pointer-target", 0), 0)
        self.assertEqual(counts.get("superseded-index-target", 0), 2)
        self.assertEqual(counts.get("missing-target", 0), 0)
        self.assertEqual(counts.get("escaping-target", 0), 0)


class WarningsOnlyTest(unittest.TestCase):
    def test_names_the_real_superseded_pointer_target(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            oc.main(["--memory", LIVE_MEMORY, "--warnings-only"])
        self.assertIn("project_session_agenda.md", buf.getvalue())

    def test_does_not_name_a_body_only_mention(self):
        # Isolated to the superseded-kind lines only -- every line in the live index is
        # currently unstamped (plan 02-03 Task 1, before the backfill), so the raw
        # --warnings-only output also names project_resume_state.md via its own unrelated
        # unstamped-index-line finding. That is not what this test is checking.
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            oc.main(["--memory", LIVE_MEMORY, "--warnings-only"])
        superseded_lines = [ln for ln in buf.getvalue().splitlines() if "superseded" in ln]
        self.assertFalse(any("project_resume_state.md" in ln for ln in superseded_lines))


class BlockingKindsTest(unittest.TestCase):
    def test_strict_exits_0_on_the_live_index_after_the_freshness_backfill_2026_09_08(self):
        # Plan 02-03 Task 1 added unstamped-index-line/malformed-stamp/stale-index-line to
        # BLOCKING_KINDS; the live index went fully unstamped (briefly, --strict exited 1),
        # then Task 2 stamped the 26-line candidate set and Task 3 backfilled the other 70 from
        # recorded provenance -- every line now carries a stamp, so --strict is back to 0. A
        # later change to the index should make this fail loudly rather than silently go stale.
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = oc.main(["--memory", LIVE_MEMORY, "--strict"])
        self.assertEqual(rc, 0)


class BoundarySectionTest(unittest.TestCase):
    """Pins boundary_section(text)'s contract in isolation, before find_findings ever calls it."""

    def test_returns_body_up_to_the_next_level_two_heading(self):
        text = ("# Title\n\n"
                "## Orientation\n"
                "line one\n"
                "line two\n"
                "## Project\n"
                "not part of the section\n")
        section = oc.boundary_section(text)
        self.assertIn("line one", section)
        self.assertIn("line two", section)
        self.assertNotIn("not part of the section", section)

    def test_runs_to_end_of_file_when_no_later_heading_exists(self):
        text = "# Title\n\n## Orientation\nonly line\n"
        section = oc.boundary_section(text)
        self.assertIn("only line", section)

    def test_returns_none_when_no_boundary_heading_is_present(self):
        text = "# Title\n\nsome other content, no boundary heading here\n"
        self.assertIsNone(oc.boundary_section(text))

    def test_a_heading_that_is_not_an_exact_match_does_not_count(self):
        text = "# Title\n\n## Orientation and more\nshould not be found\n"
        self.assertIsNone(oc.boundary_section(text))


class BoundaryFindingsTest(unittest.TestCase):
    """One case per <behavior> bullet for the three new finding kinds, built on synthetic
    index files so each of the four heading-present-or-absent combinations is pinned before
    Task 2 writes the real section."""

    def _findings_for(self, tmpdir, lines):
        path = write_index(tmpdir, lines)
        entries = oc.parse_index(path)
        return oc.find_findings(entries, path)

    def test_missing_boundary_record_is_emitted_and_is_blocking(self):
        with tempfile.TemporaryDirectory() as d:
            findings = self._findings_for(d, ["- `a.md` -- start here for the one thing"])
        kinds = [f["kind"] for f in findings]
        self.assertEqual(kinds.count("missing-boundary-record"), 1)
        self.assertIn("missing-boundary-record", oc.BLOCKING_KINDS)

    def test_incomplete_boundary_record_names_every_missing_token(self):
        with tempfile.TemporaryDirectory() as d:
            findings = self._findings_for(d, [
                "## Orientation",
                "product queue only, no process axis token or either path here",
                "- `a.md` -- start here for the one thing",
            ])
        incomplete = [f for f in findings if f["kind"] == "incomplete-boundary-record"]
        self.assertEqual(len(incomplete), 1)
        for tok in (".claude-memory/roadmap.md", ".claude-memory/meta_process.md",
                    "development-process axis"):
            self.assertIn(tok, incomplete[0]["detail"])
        self.assertNotIn("missing-boundary-record", [f["kind"] for f in findings])
        self.assertIn("incomplete-boundary-record", oc.BLOCKING_KINDS)

    def test_pointer_outside_the_section_is_reported_when_tokens_are_all_present(self):
        with tempfile.TemporaryDirectory() as d:
            findings = self._findings_for(d, [
                "## Orientation",
                "names .claude-memory/roadmap.md and .claude-memory/meta_process.md, the product queue "
                "and the development-process axis",
                "## Project",
                "- `a.md` -- start here for the one thing",
            ])
        outside = [f for f in findings if f["kind"] == "pointer-outside-boundary-section"]
        self.assertEqual(len(outside), 1)
        kinds = [f["kind"] for f in findings]
        self.assertNotIn("missing-boundary-record", kinds)
        self.assertNotIn("incomplete-boundary-record", kinds)
        self.assertIn("pointer-outside-boundary-section", oc.BLOCKING_KINDS)

    def test_no_boundary_finding_when_the_pointer_is_inside_a_complete_section(self):
        with tempfile.TemporaryDirectory() as d:
            findings = self._findings_for(d, [
                "## Orientation",
                "- `a.md` -- start here: names .claude-memory/roadmap.md, "
                ".claude-memory/meta_process.md, the product queue, and the development-process axis",
                "## Project",
                "- `b.md` -- an ordinary line",
            ])
        kinds = {f["kind"] for f in findings}
        self.assertFalse(kinds & {"missing-boundary-record", "incomplete-boundary-record",
                                   "pointer-outside-boundary-section"})

    def test_pointer_outside_boundary_section_is_suppressed_when_pointer_count_is_not_one(self):
        with tempfile.TemporaryDirectory() as d:
            findings = self._findings_for(d, [
                "## Orientation",
                "names .claude-memory/roadmap.md and .claude-memory/meta_process.md, the product queue "
                "and the development-process axis",
                "## Project",
                "- `a.md` -- start here for one thing",
                "- `b.md` -- start here for another thing",
            ])
        kinds = [f["kind"] for f in findings]
        self.assertIn("multiple-next-pointers", kinds)
        self.assertNotIn("pointer-outside-boundary-section", kinds)


class LiveBoundaryRecordTest(unittest.TestCase):
    def test_live_index_has_a_complete_boundary_record_2026_09_08(self):
        # Re-audited after plan 02-02 Task 2 landed the '## Orientation' section: no
        # missing-boundary-record, no incomplete-boundary-record, no
        # pointer-outside-boundary-section. A later change to the index should make this fail
        # loudly rather than silently go stale.
        entries = oc.parse_index(LIVE_MEMORY)
        findings = oc.find_findings(entries, LIVE_MEMORY)
        kinds = [f["kind"] for f in findings]
        self.assertEqual(kinds.count("missing-boundary-record"), 0)
        self.assertEqual(kinds.count("incomplete-boundary-record"), 0)
        self.assertEqual(kinds.count("pointer-outside-boundary-section"), 0)

    def test_warnings_only_does_not_name_a_missing_boundary_record(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            oc.main(["--memory", LIVE_MEMORY, "--warnings-only"])
        lines = [ln for ln in buf.getvalue().splitlines() if "missing-boundary-record" in ln]
        self.assertEqual(len(lines), 0)


class StampOfTest(unittest.TestCase):
    """stamp_of(text)'s contract in isolation, before find_findings ever calls it."""

    def test_returns_the_date_from_a_well_formed_trailing_stamp(self):
        self.assertEqual(
            oc.stamp_of("- `a.md` -- summary text (reviewed 2026-09-08)"), "2026-09-08")

    def test_returns_none_when_there_is_no_stamp(self):
        self.assertIsNone(oc.stamp_of("- `a.md` -- summary text with no stamp"))

    def test_a_date_elsewhere_in_the_line_is_not_read_as_a_stamp(self):
        # The real feedback_stabilize_before_tuning_signals.md line: a 2026-07-09 gate date
        # inside the prose, not a "(reviewed ...)" token.
        text = ("- `feedback_stabilize_before_tuning_signals.md`: stabilize the substrate "
                "before signal-dynamics redesign; tune only when convergence is reproducible "
                "(gate met 2026-07-09).")
        self.assertIsNone(oc.stamp_of(text))

    def test_two_stamps_on_one_line_return_none_from_stamp_of(self):
        text = "- `a.md` -- summary (reviewed 2026-01-01) (reviewed 2026-01-02)"
        self.assertIsNone(oc.stamp_of(text))

    def test_a_malformed_date_inside_the_stamp_returns_none(self):
        self.assertIsNone(oc.stamp_of("- `a.md` -- summary (reviewed not-a-date)"))

    def test_a_shape_valid_but_calendar_invalid_date_returns_none(self):
        # The gap the shape regex left: "2026-99-99" is four digits, two, two, so it satisfies
        # ISO_DATE and used to come back as a date the caller would trust -- and being greater
        # than every real token, nothing could ever be newer than it. The two cases above only
        # ever covered wrong FORMAT ("09/01/2026") and non-numeric text ("not-a-date").
        self.assertIsNone(oc.stamp_of("- `a.md` -- summary (reviewed 2026-99-99)"))
        self.assertIsNone(oc.stamp_of("- `a.md` -- summary (reviewed 2026-13-40)"))
        self.assertIsNone(oc.stamp_of("- `a.md` -- summary (reviewed 2026-02-30)"))

    def test_a_real_leap_day_is_still_accepted(self):
        # The boundary either side of the rejection above: 2024 is a leap year, 2026 is not.
        self.assertEqual(oc.stamp_of("- `a.md` -- s (reviewed 2024-02-29)"), "2024-02-29")
        self.assertIsNone(oc.stamp_of("- `a.md` -- s (reviewed 2026-02-29)"))


class IsCalendarDateTest(unittest.TestCase):
    """is_calendar_date(text)'s contract in isolation -- the single home the stamp reader, the
    malformed-stamp branch and the newest-entry scan all resolve this question through."""

    def test_a_real_date_is_accepted(self):
        self.assertTrue(oc.is_calendar_date("2026-09-08"))

    def test_shape_valid_but_impossible_dates_are_rejected(self):
        for token in ("2026-99-99", "2026-13-01", "2026-00-10", "2026-02-30", "2026-04-31"):
            self.assertFalse(oc.is_calendar_date(token), token)

    def test_wrong_shape_is_rejected_before_the_calendar_is_consulted(self):
        for token in ("09/01/2026", "not-a-date", "2026-9-8", "20260908", ""):
            self.assertFalse(oc.is_calendar_date(token), token)


class NewestEntryDateTest(unittest.TestCase):
    """newest_entry_date(text)'s contract in isolation."""

    def test_returns_the_lexicographically_greatest_iso_token(self):
        text = "first entry 2026-01-05, then 2026-08-20, then 2026-03-01"
        self.assertEqual(oc.newest_entry_date(text), "2026-08-20")

    def test_returns_none_when_no_dated_entry_exists(self):
        self.assertIsNone(oc.newest_entry_date("no dates anywhere in this text"))

    def test_a_calendar_invalid_token_is_not_treated_as_an_entry_date(self):
        # Sorting is lexicographic, so an impossible token like "2026-99-99" outranks every
        # real entry and would make the index line pointing here look permanently stale. The
        # real entry beside it is what the reading must fall back to.
        text = "genuine entry 2026-06-01, and a typo 2026-99-99 in the prose"
        self.assertEqual(oc.newest_entry_date(text), "2026-06-01")

    def test_a_text_whose_only_token_is_calendar_invalid_reads_as_undated(self):
        self.assertIsNone(oc.newest_entry_date("the only token here is 2026-13-40"))


class FreshnessFindingsTest(unittest.TestCase):
    """One case per freshness <behavior> bullet, built on synthetic index files so the tie, the
    undated target, the double stamp and the malformed stamp all have pinned answers before the
    real index is ever touched."""

    def _kinds_for(self, tmpdir, plain_line_suffix, target_content):
        lines = [
            "## Orientation",
            "- `a.md` -- start here: names .claude-memory/roadmap.md, "
            ".claude-memory/meta_process.md, the product queue, and the development-process axis"
            " (reviewed 2026-01-01)",
            "## Project",
            "- `plain.md` -- an ordinary target" + plain_line_suffix,
        ]
        path = write_index(tmpdir, lines, targets={"plain.md": target_content})
        entries = oc.parse_index(path)
        findings = oc.find_findings(entries, path)
        return [f["kind"] for f in findings if f["target"] == "plain.md"]

    def test_no_stamp_yields_unstamped_index_line(self):
        with tempfile.TemporaryDirectory() as d:
            kinds = self._kinds_for(d, "", "# Plain\nno dated entry here\n")
        self.assertEqual(kinds, ["unstamped-index-line"])

    def test_two_stamps_on_one_line_yield_malformed_stamp(self):
        with tempfile.TemporaryDirectory() as d:
            kinds = self._kinds_for(
                d, " (reviewed 2026-01-01) (reviewed 2026-01-02)", "# Plain\nno dated entry\n")
        self.assertEqual(kinds, ["malformed-stamp"])

    def test_a_non_iso_stamp_date_yields_malformed_stamp(self):
        with tempfile.TemporaryDirectory() as d:
            kinds = self._kinds_for(d, " (reviewed 09/01/2026)", "# Plain\nno dated entry\n")
        self.assertEqual(kinds, ["malformed-stamp"])

    def test_a_calendar_invalid_stamp_date_yields_malformed_stamp(self):
        # End-to-end counterpart to StampOfTest's unit case: the branch in find_findings has
        # its own shape check, so fixing stamp_of alone would leave this path still silent.
        # The target carries a dated entry, so a stamp wrongly accepted as "2026-99-99" would
        # emit no finding at all -- the exact zero-findings reading the verifier reproduced.
        with tempfile.TemporaryDirectory() as d:
            kinds = self._kinds_for(
                d, " (reviewed 2026-99-99)", "# Plain\nupdated 2026-06-01\n")
        self.assertEqual(kinds, ["malformed-stamp"])

    def test_stamp_strictly_earlier_than_the_targets_newest_entry_is_stale(self):
        with tempfile.TemporaryDirectory() as d:
            kinds = self._kinds_for(d, " (reviewed 2026-01-01)", "# Plain\nupdated 2026-06-01\n")
        self.assertEqual(kinds, ["stale-index-line"])

    def test_a_stamp_equal_to_the_targets_newest_entry_is_fresh_the_tie_case(self):
        with tempfile.TemporaryDirectory() as d:
            kinds = self._kinds_for(d, " (reviewed 2026-06-01)", "# Plain\nupdated 2026-06-01\n")
        self.assertEqual(kinds, [])

    def test_a_stamp_later_than_the_targets_newest_entry_is_fresh(self):
        with tempfile.TemporaryDirectory() as d:
            kinds = self._kinds_for(d, " (reviewed 2026-09-01)", "# Plain\nupdated 2026-06-01\n")
        self.assertEqual(kinds, [])

    def test_a_target_with_no_dated_entry_is_never_stale_whatever_the_stamp_says(self):
        with tempfile.TemporaryDirectory() as d:
            kinds = self._kinds_for(
                d, " (reviewed 2026-01-01)", "# Plain\nno dated entry anywhere in this file\n")
        self.assertEqual(kinds, [])

    def test_an_unstamped_line_does_not_also_yield_stale_one_finding_per_line(self):
        with tempfile.TemporaryDirectory() as d:
            kinds = self._kinds_for(d, "", "# Plain\nupdated 2026-06-01\n")
        self.assertEqual(kinds, ["unstamped-index-line"])

    def test_all_three_new_kinds_are_blocking(self):
        for kind in ("unstamped-index-line", "malformed-stamp", "stale-index-line"):
            self.assertIn(kind, oc.BLOCKING_KINDS)


class LiveFreshnessAtTaskStartTest(unittest.TestCase):
    def test_live_index_is_fully_stamped_after_task_3_2026_09_08(self):
        # Re-pinned after plan 02-03 Task 3's backfill landed: the 70 lines Task 2 did not
        # stamp (96 - 26) are now stamped from recorded provenance evidence, so zero lines are
        # unstamped, stale, or malformed. This test replaces the Task-1 "entirely unstamped" and
        # Task-2 "26 stamped" pins, neither of which is the live state any more -- a later
        # change to the index should make this fail loudly rather than silently go stale.
        entries = oc.parse_index(LIVE_MEMORY)
        findings = oc.find_findings(entries, LIVE_MEMORY)
        counts = {}
        for f in findings:
            counts[f["kind"]] = counts.get(f["kind"], 0) + 1
        self.assertEqual(counts.get("unstamped-index-line", 0), 0)
        self.assertEqual(counts.get("stale-index-line", 0), 0)
        self.assertEqual(counts.get("malformed-stamp", 0), 0)


class ProvenanceMapTest(unittest.TestCase):
    """provenance_map(memory_path, rev)'s contract, against the live index at a real revision."""

    def test_maps_target_base_name_to_the_last_edited_date_at_a_real_revision(self):
        mapping = oc.provenance_map(LIVE_MEMORY, "49a65ab2")
        self.assertEqual(mapping.get("project_signal_dispositions.md"), "2026-07-09")

    def test_returns_an_empty_mapping_for_an_unresolvable_revision(self):
        self.assertEqual(oc.provenance_map(LIVE_MEMORY, "nosuchrev"), {})

    def test_returns_an_empty_mapping_rather_than_raising_on_a_bad_memory_path(self):
        self.assertEqual(oc.provenance_map("/nonexistent/MEMORY.md", "49a65ab2"), {})


class ProvenanceReportTest(unittest.TestCase):
    """provenance_report's candidate rule: a target that moved after its provenance date, or a
    line with no provenance date at all."""

    def _rows(self):
        return oc.provenance_report(oc.parse_index(LIVE_MEMORY), LIVE_MEMORY, "49a65ab2",
                                     io.StringIO(), as_json=True)

    def test_marks_a_line_a_candidate_when_the_target_moved_after_its_provenance_date(self):
        by_target = {r["target"]: r for r in self._rows()}
        self.assertTrue(by_target["roadmap.md"]["candidate"])

    def test_marks_a_line_a_candidate_when_it_has_no_provenance_date_at_all(self):
        by_target = {r["target"]: r for r in self._rows()}
        self.assertIsNone(by_target["tier_definitions.md"]["provenance"])
        self.assertTrue(by_target["tier_definitions.md"]["candidate"])

    def test_prints_one_row_per_index_line_and_a_trailing_candidate_count(self):
        buf = io.StringIO()
        entries = oc.parse_index(LIVE_MEMORY)
        oc.provenance_report(entries, LIVE_MEMORY, "49a65ab2", buf, as_json=False)
        out = buf.getvalue()
        self.assertEqual(out.count("line="), len(entries))
        self.assertIn("candidate set:", out)


class ProvenanceRevCliTest(unittest.TestCase):
    def test_returns_0_when_candidates_exist(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = oc.main(["--memory", LIVE_MEMORY, "--provenance-rev", "49a65ab2"])
        self.assertEqual(rc, 0)

    def test_returns_0_for_an_unresolvable_revision_without_raising(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = oc.main(["--memory", LIVE_MEMORY, "--provenance-rev", "nosuchrev"])
        self.assertEqual(rc, 0)

    def test_json_output_carries_a_provenance_key_with_one_row_per_index_line(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            oc.main(["--memory", LIVE_MEMORY, "--provenance-rev", "49a65ab2", "--json"])
        payload = json.loads(buf.getvalue())
        entries = oc.parse_index(LIVE_MEMORY)
        self.assertEqual(len(payload["provenance"]), len(entries))


if __name__ == "__main__":
    unittest.main(verbosity=2)
