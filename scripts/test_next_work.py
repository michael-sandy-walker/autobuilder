#!/usr/bin/env python3
"""
Durable tests for next_work.py (roadmap-as-work-queue digest). Pure stdlib:
run with `python3 scripts/test_next_work.py`.

The precision cases matter most. A first cut of the warning detector produced 44 hits on the real
roadmap, nearly all false — bodies routinely say "not built this scope", cite other tickets that
shipped, and ship one slice under an open umbrella. A detector that cries wolf gets ignored, so
each of those shapes is pinned here as a NON-warning.
"""
import contextlib
import importlib.util
import io
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "next_work", os.path.join(os.path.dirname(__file__), "next_work.py"))
nw = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(nw)


def ticket(marker, tid, title, *body):
    lines = ["- [%s] **%s: %s** (`pkg`) — filed" % (marker, tid, title)]
    lines += ["  - %s" % b for b in body]
    return "\n".join(lines)


class NextWorkTest(unittest.TestCase):
    def _roadmap(self, *tickets):
        path = os.path.join(tempfile.mkdtemp(), "roadmap.md")
        with open(path, "w") as fh:
            fh.write("# Roadmap\n\n" + "\n".join(tickets) + "\n")
        return path

    def _parse(self, *tickets):
        return nw.parse(self._roadmap(*tickets))

    def _digest(self, *tickets, **kw):
        path = self._roadmap(*tickets)
        buf = io.StringIO()
        argv = ["--roadmap", path] + list(kw.get("argv", []))
        with contextlib.redirect_stdout(buf):
            nw.main(argv)
        return buf.getvalue()

    # --- parsing ------------------------------------------------------------

    def test_parses_marker_id_title_and_body(self):
        ts = self._parse(ticket("x", "T1-AA", "Fix the thing", "Root cause: a bug", "DoD: tests"))
        self.assertEqual(len(ts), 1)
        self.assertEqual((ts[0]["id"], ts[0]["marker"]), ("T1-AA", "x"))
        self.assertEqual(ts[0]["title"], "Fix the thing")
        self.assertEqual(len(ts[0]["body"]), 2)

    def test_body_stops_at_the_next_ticket(self):
        ts = self._parse(ticket(" ", "T1-AA", "First", "note one"),
                         ticket(" ", "T1-AB", "Second", "note two"))
        self.assertEqual([t["id"] for t in ts], ["T1-AA", "T1-AB"])
        self.assertEqual(ts[0]["body"], ["  - note one"])

    def test_tier_is_read_from_the_id(self):
        self.assertEqual(nw.tier_of("T4-CP"), "4")
        self.assertEqual(nw.tier_of("T6-B"), "6")
        self.assertEqual(nw.tier_of("M-A"), "M")

    def test_sub_numbered_ids_parse(self):
        ts = self._parse(ticket("x", "T4-BN-2", "Incremental probe", "note"))
        self.assertEqual(ts[0]["id"], "T4-BN-2")

    # --- unflipped-closure warning (the T6-A drift) --------------------------

    def test_live_checkbox_with_shipped_body_is_flagged(self):
        ts = self._parse(ticket(" ", "T6-A", "Dual smoke scoring",
                                "**SHIPPED 2026-07-24 (scripts only).** Two pieces ..."))
        w = nw.find_warnings(ts)
        self.assertEqual(len(w), 1)
        self.assertEqual((w[0]["kind"], w[0]["id"]), ("unflipped-closure", "T6-A"))

    def test_closed_checkbox_with_shipped_body_is_not_flagged(self):
        ts = self._parse(ticket("x", "T6-A", "Dual smoke scoring", "**SHIPPED 2026-07-24.**"))
        self.assertEqual(nw.find_warnings(ts), [])

    def test_a_slice_shipping_under_an_open_umbrella_is_not_drift(self):
        # T4-CJ / T4-CI / T4-CL all legitimately read [ ] with a shipped slice or phase.
        for body in ("**SLICE 1 SHIPPED 2026-07-17 (`1f8780b`).**",
                     "**PHASE 2 SHIPPED same session (gate met):** `--report` mode",
                     "**TEST-ISOLATION HALF SHIPPED 2026-07-17.**",
                     "**Step 1 shipped 2026-06-10** — record criteria on the route."):
            ts = self._parse(ticket(" ", "T4-CJ", "Run identity", body))
            self.assertEqual(nw.find_warnings(ts), [], "false positive on: %s" % body)

    def test_citing_another_ticket_as_shipped_is_not_drift(self):
        ts = self._parse(ticket(" ", "T2-CR", "Criteria-preserving refinement",
                                "**Depends on:** T3-BR (cap-emission infrastructure — shipped)."))
        self.assertEqual(nw.find_warnings(ts), [])

    def test_body_declining_a_build_is_never_a_warning_on_a_closed_ticket(self):
        # A ticket closed won't-do on evidence is correctly [x]: T3-G, T4-CE, T4-CP all read this way.
        ts = self._parse(ticket("x", "T3-G", "Complexity-adaptive tolerance",
                                "**CLOSED — PREMISE FALSIFIED on 1151 samples; won't-do, G1 not built.**"))
        self.assertEqual(nw.find_warnings(ts), [])

    # --- closed-with-residual warning (the reverse direction, M-E) ----------

    def test_closed_body_asserting_its_own_remaining_work_reaches_the_digest(self):
        text = self._digest(ticket("x", "T2-EX", "Confound direction call",
                                   "**Still open, and no steering change may precede it:** the "
                                   "six-run measurement and the direction call on the confound"))
        self.assertIn("closed-with-residual", text)
        self.assertIn("T2-EX", text)

    def test_real_self_residual_lines_are_flagged(self):
        # All six are verbatim real lines drawn from the live roadmap (or, for T4-DK, its real
        # pre-flip shape reconstructed per Pitfall 3 — the live ticket is already [~] today).
        cases = [
            ("T2-EX", "**Still open, and no steering change may precede it:** the six-run "
                      "measurement and the direction call on the confound"),
            ("T6-N", "**STILL OPEN — the DoD is not met and cannot be today:** it needs "
                     "20 retained runs and there are 5."),
            ("T4-BZ-0", "**Still open (blocks T4-BZ-1 start):** (1) the per-emission-site "
                        "table"),
            ("T4-CK", "**Still open for a later pass:** nothing known on the read surface; "
                      "the remaining risk is unknown-unknowns"),
            ("T5-I", "**SUBSTRATE RE-READ 2026-07-16 — still open as filed, none of the v2 "
                     "events exist.**"),
            ("T4-DK", "**THE PLANNING-SIDE TWIN OF THIS TICKET'S OPEN HALF, code-confirmed "
                      "on clitarget 2026-08-29 — and the REPLAY says the capability already "
                      "exists and is simply unrouted.**"),
        ]
        for tid, body in cases:
            ts = self._parse(ticket("x", tid, "title", body))
            w = nw.find_warnings(ts)
            self.assertEqual(len(w), 1, "no fire on: %s" % body)
            self.assertEqual(w[0]["kind"], "closed-with-residual", "wrong kind on: %s" % body)

    def test_a_residual_phrase_downstream_of_a_ticket_id_is_not_flagged(self):
        # All four are verbatim real lines where the assertion refers to a DIFFERENT ticket's
        # openness, with that ticket's id appearing in the same line.
        cases = [
            ("T1-FS", "**The general lesson is bigger than this ticket: T4-CL is the audit "
                      "of exactly this class, and it is still open.**"),
            ("T3-BW", "**Residual:** the block tells evolution the difference exists; it "
                      "does not yet ROUTE a stuck subgoal to look at it — that is T2-ES, "
                      "still open."),
            ("T5-Y", "**Chain I — Pre-evaluation compile ground truth** (T1-EC; T2-DG "
                     "open). T2-DG (rollback-recurrence escalation, safety net) remains "
                     "open"),
            ("T4-DM", "T4-DK open half — is the seed met at the closure edge"),
        ]
        for tid, body in cases:
            ts = self._parse(ticket("x", tid, "title", body))
            self.assertEqual(nw.find_warnings(ts), [], "false positive on: %s" % body)

    def test_a_foreign_id_absorbed_by_the_leading_label_does_not_flag(self):
        # CR-01 leg 1. RESIDUAL_CLAIM is matched with re.match, so a successful match starts at
        # offset 0 and any subject id sits INSIDE the match, never before it. The narrative-label
        # prefix absorbs a digitless id such as the milestone `M-E`, so the openness asserted here
        # is M-E's, not T2-EX's. A pre-match prefix slice is always empty and cannot see this.
        ts = self._parse(ticket("x", "T2-EX", "title",
                                "**M-E still open, needs the census gate before this can close.**"))
        self.assertEqual(nw.find_warnings(ts), [],
                         "M-E's openness must not be read as T2-EX's own residual")

    def test_the_tickets_own_id_in_the_line_does_not_suppress_a_self_claim(self):
        # CR-01 leg 2. Restating your own id in prose is not a co-reference, so it must never
        # cancel a genuine self-residual. EXPLICIT_RESIDUAL is reflexive by construction
        # ("this ticket's ... open half"), so no id in the line can make it another ticket's.
        ts = self._parse(ticket("x", "T4-DK", "title",
                                "Filed as T4-DK: this ticket's open half remains unresolved."))
        kinds = [w["kind"] for w in nw.find_warnings(ts)]
        self.assertIn("closed-with-residual", kinds,
                      "a self-claim must survive the ticket restating its own id")

    def test_a_foreign_id_does_not_suppress_a_reflexive_claim(self):
        # EXPLICIT_RESIDUAL is reflexive by construction, so a foreign id elsewhere in the line
        # is not the subject and must not suppress. Guards the over-fire direction of CR-01.
        ts = self._parse(ticket("x", "T5-BD", "title",
                                "Per T4-CD, this ticket's open half is the restore path."))
        kinds = [w["kind"] for w in nw.find_warnings(ts)]
        self.assertIn("closed-with-residual", kinds,
                      "a foreign id must not cancel a reflexive self-claim")

    def test_an_ordinary_caps_hyphen_word_is_not_read_as_a_foreign_id(self):
        # SUBJECT_ID is broader than TICKET_REF so it can see milestone ids (M-C, M-D) that carry
        # no digit. Too broad and it reads ordinary all-caps hyphenated prose as a subject and
        # suppresses a genuine self-residual. It must match the two id shapes that actually exist
        # and nothing else.
        for word in ("AS-IS", "NO-OP", "DO-NOT"):
            ts = self._parse(ticket("x", "T9-ZZ", "title",
                                    "**%s still open: the census gate has not been run.**" % word))
            kinds = [w["kind"] for w in nw.find_warnings(ts)]
            self.assertIn("closed-with-residual", kinds,
                          "%s is not a ticket id and must not suppress a self-claim" % word)

    def test_spec_language_and_quoted_event_text_are_not_flagged(self):
        cases = [
            ("T4-BE", "**Intent still open:** candidate's intentGoalId (and "
                      "intentSubgoalId if present) must be ACTIVE — not COMPLETED, "
                      "GIVEN_UP, or SUBSUMED"),
            ("T4-BP", "**Step 3 remains open:** the read-path gate (EvaluationGraphFacade "
                      "query by criterionKey + presence check, promote on positive "
                      "corroboration)."),
            ("T2-EM", "**FIRST FIELD FIRING 2026-07-15 (corpus 2026-07-15_03-03-29):** the "
                      "designed marker fired 9 times on one structural-repair leaf "
                      "(\"scope-mismatch suppression\"), and its named gap is still open "
                      "(the gap is the scope)"),
        ]
        for tid, body in cases:
            ts = self._parse(ticket("x", tid, "title", body))
            self.assertEqual(nw.find_warnings(ts), [], "false positive on: %s" % body)

    def test_reverse_direction_is_silent_when_the_body_declines_the_build(self):
        ts = self._parse(ticket("x", "T3-G", "Complexity-adaptive tolerance",
                                "**CLOSED — PREMISE FALSIFIED on 1151 samples; won't-do, "
                                "G1 not built. The G1 half remains open as a question, not "
                                "as work.**"))
        self.assertEqual(nw.find_warnings(ts), [])

    def test_reverse_direction_reads_closed_markers_only(self):
        body = ("**Still open, and no steering change may precede it:** the six-run "
                "measurement and the direction call on the confound")
        for marker in ("x", "n", "o", "s", "r"):
            ts = self._parse(ticket(marker, "T2-EX", "title", body))
            kinds = [w["kind"] for w in nw.find_warnings(ts)]
            self.assertIn("closed-with-residual", kinds,
                          "marker [%s] should fire the reverse direction" % marker)
        for marker in (" ", "~", ">"):
            ts = self._parse(ticket(marker, "T2-EX", "title", body))
            kinds = [w["kind"] for w in nw.find_warnings(ts)]
            self.assertNotIn("closed-with-residual", kinds,
                             "marker [%s] should not fire the reverse direction" % marker)
            self.assertNotIn("unflipped-closure", kinds,
                             "marker [%s] should not fire the forward direction either" % marker)

    def test_the_two_directions_do_not_double_report(self):
        ts = self._parse(ticket("x", "T9-ZZ", "Both claims in one body",
                                "**SHIPPED 2026-01-01.**",
                                "**Still open, and no steering change may precede it:** "
                                "residual note"))
        w = nw.find_warnings(ts)
        self.assertEqual(len(w), 1)
        self.assertEqual(w[0]["kind"], "closed-with-residual")

    def test_warning_order_is_forward_then_reverse_in_file_order(self):
        ts = self._parse(
            ticket(" ", "T6-A", "Drifted", "**SHIPPED 2026-07-24.**"),
            ticket("x", "T2-EX", "Residual one",
                  "**Still open, and no steering change may precede it:** residual note "
                  "one"),
            ticket("x", "T6-N", "Residual two",
                  "**STILL OPEN — the DoD is not met and cannot be today:** residual "
                  "note two"))
        w = nw.find_warnings(ts)
        self.assertEqual([x["kind"] for x in w],
                         ["unflipped-closure", "closed-with-residual", "closed-with-residual"])
        self.assertEqual([x["id"] for x in w], ["T6-A", "T2-EX", "T6-N"])

    # --- reviewed-marker acknowledgment (D-06/D-07) --------------------------

    def test_a_reviewed_marker_line_silences_the_forward_warning(self):
        closure_body = ("BOTH HALVES SHIPPED 2026-08-30, AND THE GATE WAS ANSWERED BY "
                        "REPLAY OVER ALL THIRTEEN RECORDED STALLS")
        ts = self._parse(ticket(" ", "T2-FH", "title", closure_body))
        self.assertEqual(len(nw.find_warnings(ts)), 1)
        ts = self._parse(ticket(" ", "T2-FH", "title",
                                "**MARKER REVIEWED 2026-09-07 — open by design; the field "
                                "clause below is the remaining work.**",
                                closure_body))
        self.assertEqual(nw.find_warnings(ts), [])

    def test_a_reviewed_marker_line_silences_the_reverse_warning(self):
        residual_body = ("**Still open, and no steering change may precede it:** the "
                         "six-run measurement and the direction call on the confound")
        ts = self._parse(ticket("x", "T2-EX", "title", residual_body))
        self.assertEqual(len(nw.find_warnings(ts)), 1)
        ts = self._parse(ticket("x", "T2-EX", "title",
                                "**MARKER REVIEWED 2026-09-07 — open by design.**",
                                residual_body))
        self.assertEqual(nw.find_warnings(ts), [])

    def test_ordinary_open_prose_does_not_silence_a_warning(self):
        closure_body = "**SHIPPED 2026-07-24.**"
        ts = self._parse(ticket(" ", "T6-A", "title", closure_body,
                                "FIELD DoD OPEN — deferred verification, unrelated to this "
                                "claim."))
        self.assertEqual(len(nw.find_warnings(ts)), 1)
        ts = self._parse(ticket(" ", "T6-A", "title", closure_body,
                                "Note for later: the marker reviewed check should catch "
                                "this eventually."))
        self.assertEqual(len(nw.find_warnings(ts)), 1)

    def test_the_new_kind_appears_in_warnings_only_output(self):
        text = self._digest(ticket("x", "T2-EX", "title",
                                   "**Still open, and no steering change may precede it:** "
                                   "residual note"),
                            argv=["--warnings-only"])
        self.assertIn("closed-with-residual", text)

    def test_the_new_kind_appears_in_json_output(self):
        import json as _json
        text = self._digest(ticket("x", "T2-EX", "title",
                                   "**Still open, and no steering change may precede it:** "
                                   "residual note"),
                            argv=["--json"])
        payload = _json.loads(text)
        kinds = [w["kind"] for w in payload["warnings"]]
        self.assertEqual(kinds.count("closed-with-residual"), 1)

    # --- dead dependencies (the T4-E -> T4-CE trap) -------------------------

    def test_precondition_closed_without_building_is_flagged(self):
        ts = self._parse(
            ticket(" ", "T4-E", "Dynamic heuristics",
                   "**DEFERRED — re-entry condition: T4-CE slice 2's replay machinery exists.**"),
            ticket("x", "T4-CE", "Outer-loop internalization",
                   "**SLICE-2 GATE MEASURED: NO-BUILD — the negative result is the deliverable.**"))
        deps = nw.find_dead_deps(ts)
        self.assertEqual(len(deps), 1)
        self.assertEqual((deps[0]["id"], deps[0]["depends_on"]), ("T4-E", "T4-CE"))
        self.assertEqual(deps[0]["kind"], "closed-without-building")

    def test_precondition_closed_and_built_is_satisfied_and_silent(self):
        ts = self._parse(
            ticket(" ", "T2-CR", "Refinement", "**Depends on:** T3-BR — the cap infrastructure."),
            ticket("x", "T3-BR", "Criteria-aware guard", "**SHIPPED 2026-06-10.** As filed."))
        self.assertEqual(nw.find_dead_deps(ts), [])

    def test_precondition_still_deferred_is_flagged_as_blocked(self):
        ts = self._parse(
            ticket("~", "T4-BA", "Package fragmentation", "steps 2-3 are gated on T4-AW"),
            ticket(">", "T4-AW", "Output reviewer SPI", "deferred pending demand"))
        deps = nw.find_dead_deps(ts)
        self.assertEqual([d["kind"] for d in deps], ["blocked-on-deferred"])

    def test_a_reference_outside_a_precondition_line_is_ignored(self):
        ts = self._parse(
            ticket(" ", "T4-X", "Something", "Historical note: T4-CE produced a nice report."),
            ticket("x", "T4-CE", "Outer loop", "**NO-BUILD.**"))
        self.assertEqual(nw.find_dead_deps(ts), [])

    def test_closed_tickets_are_not_scanned_for_dead_deps(self):
        ts = self._parse(
            ticket("x", "T4-DONE", "Finished", "was gated on T4-CE"),
            ticket("x", "T4-CE", "Outer loop", "**NO-BUILD.**"))
        self.assertEqual(nw.find_dead_deps(ts), [])

    # --- gate extraction ----------------------------------------------------

    def test_gate_lines_rank_entry_conditions_above_design_prose(self):
        gates = nw.gate_lines([
            "  - **Fix:** requires a new service and a config toggle",
            "  - **Re-entry:** only once a batch of dual-scored run directories exists",
        ], limit=2)
        self.assertIn("Re-entry", gates[0])

    def test_gate_lines_respect_the_limit(self):
        body = ["  - re-entry note %d" % i for i in range(6)]
        self.assertEqual(len(nw.gate_lines(body, limit=3)), 3)

    # --- digest / CLI -------------------------------------------------------

    def test_digest_reports_census_and_queue(self):
        text = self._digest(ticket(" ", "T4-AA", "Open one", "**Re-entry:** measure first"),
                            ticket("x", "T4-AB", "Closed one", "**SHIPPED.**"),
                            ticket(">", "T4-AC", "Deferred one", "deferred"))
        self.assertIn("open [ ] 1", text)
        self.assertIn("deferred [>] 1", text)
        self.assertIn("built [x] 1", text)
        self.assertIn("T4-AA", text)
        self.assertIn("Re-entry", text)
        self.assertNotIn("T4-AC —", text)          # deferred is not in the actionable queue

    # --- closure kinds ------------------------------------------------------

    def test_closure_kind_markers_parse(self):
        ts = self._parse(ticket("n", "T4-CP", "Declined one", "note"),
                         ticket("o", "T4-C", "Obsoleted one", "note"),
                         ticket("s", "T4-AL", "Subsumed one", "note"),
                         ticket("r", "T1-FH", "Retracted one", "note"))
        self.assertEqual([t["marker"] for t in ts], ["n", "o", "s", "r"])
        for t in ts:                                # closure kinds are not actionable work
            self.assertNotIn(t["marker"], nw.LIVE_MARKERS)

    def test_census_breaks_closed_out_by_kind(self):
        text = self._digest(ticket("x", "T4-CA", "Built", "note"),
                            ticket("n", "T4-CP", "Declined", "note"),
                            ticket("o", "T4-C", "Obsoleted", "note"))
        self.assertIn("closed 3", text)
        self.assertIn("built [x] 1", text)
        self.assertIn("declined [n] 1", text)
        self.assertIn("obsoleted [o] 1", text)
        self.assertIn("2 ticket(s) closed with NO capability delivered", text)

    def test_dependency_on_a_nothing_built_kind_needs_no_prose(self):
        # The whole point of the marker: no regex over closure prose, which collides with the
        # product's own vocabulary (goals legitimately become SUBSUMED).
        for marker in ("n", "o", "r"):
            ts = self._parse(ticket(" ", "T4-X", "Live", "gated on T4-CE"),
                             ticket(marker, "T4-CE", "No capability here", "no verdict prose"))
            deps = nw.find_dead_deps(ts)
            self.assertEqual([d["kind"] for d in deps], ["closed-without-building"],
                             "marker [%s] should read as nothing-built" % marker)

    def test_dependency_on_a_subsumed_ticket_is_satisfied(self):
        # Subsumed means another ticket owns the capability, so the precondition is met.
        ts = self._parse(ticket(" ", "T4-X", "Live", "gated on T4-AL"),
                         ticket("s", "T4-AL", "Folded elsewhere", "note"))
        self.assertEqual(nw.find_dead_deps(ts), [])

    def test_duplicate_ids_are_reported(self):
        text = self._digest(ticket(" ", "T3-BT", "First heading", "note"),
                            ticket("x", "T3-BT", "Second heading", "note"))
        self.assertIn("headed more than once: T3-BT", text)

    def test_tier_filter_restricts_the_queue(self):
        text = self._digest(ticket(" ", "T2-AA", "Steering item", "note"),
                            ticket(" ", "T4-AA", "New system", "note"),
                            argv=["--tier", "4"])
        self.assertIn("T4-AA", text)
        self.assertNotIn("T2-AA", text)

    def test_warnings_only_skips_the_queue(self):
        text = self._digest(ticket(" ", "T6-A", "Thing", "**SHIPPED 2026-07-24.**"),
                            argv=["--warnings-only"])
        self.assertIn("unflipped-closure", text)
        self.assertNotIn("LIVE QUEUE", text)

    def test_json_mode_is_machine_readable(self):
        import json as _json
        text = self._digest(ticket(" ", "T4-AA", "Open", "**Re-entry:** measure"), argv=["--json"])
        payload = _json.loads(text)
        self.assertEqual(payload["census"][" "], 1)
        self.assertEqual(payload["live"][0]["id"], "T4-AA")

    def test_missing_roadmap_exits_quietly(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = nw.main(["--roadmap", "/nonexistent/roadmap.md"])
        self.assertEqual(rc, 0)

    def test_unknown_id_is_not_fatal(self):
        path = self._roadmap(ticket(" ", "T4-AA", "Open", "note"))
        self.assertEqual(nw.main(["--roadmap", path, "--id", "T9-ZZ"]), 0)



class AuditedPrecisionTest(unittest.TestCase):
    """2026-09-21 audit: 19 warnings on the real roadmap, 1 genuine. These pin the shapes that
    accounted for the noise, because a detector at 95% false is one nobody reads — which is how a
    real mis-routed fix (T4-DO) sat unnoticed for three weeks in plain sight."""

    def warn_kinds(self, body_lines, marker="x", tid="T9-A"):
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as fh:
            fh.write(ticket(marker, tid, "t", *body_lines))
            path = fh.name
        try:
            tickets = nw.parse(path)
            return [w["kind"] for w in nw.find_warnings(tickets)]
        finally:
            os.unlink(path)

    def test_residual_superseded_by_a_later_resolution_is_not_a_warning(self):
        # feedback_append_dont_rewrite_tickets puts the newest truth at the BOTTOM.
        kinds = self.warn_kinds([
            "**Still open:** the six-run measurement and the direction call.",
            "**✅ DoD MET AND THE DIRECTION CALL IS: DO NOT STEER ON IT (2026-08-24).**",
        ])
        self.assertNotIn("closed-with-residual", kinds)

    def test_residual_stated_after_the_resolution_still_warns(self):
        # Order is the whole discriminator: a ✅ above does not mute a residual below it.
        kinds = self.warn_kinds([
            "**✅ DoD MET 2026-08-24.**",
            "**Still open:** the six-run measurement and the direction call.",
        ])
        self.assertIn("closed-with-residual", kinds)

    def test_enumerating_three_or_more_tickets_is_not_a_dependency(self):
        deps = self.deps_of(
            "The same bypass exists verbatim in all seven closure gates — `T2-EI`, `T2-DR`, `T4-AN-8`.")
        self.assertEqual(deps, [], "peer enumeration read as a precondition")

    def test_a_ticket_that_gates_others_is_not_blocked_by_them(self):
        deps = self.deps_of(
            "**Sequencing:** this GATES the expensive half — only then invest in `T2-EI`.")
        self.assertEqual(deps, [], "direction inverted: the arrow points the wrong way")

    def test_an_explicit_disclaimer_is_not_a_dependency(self):
        deps = self.deps_of(
            "**⚠ NOT the same ticket as the discovery work.** That is `T2-EI`'s gated remainder.")
        self.assertEqual(deps, [], "a line denying the link was read as asserting it")

    def test_a_real_precondition_is_still_reported(self):
        # The guards must not mute the one shape the checker exists for.
        deps = self.deps_of("**re-entry condition:** `T2-EI` slice 2's replay machinery exists.")
        self.assertEqual(deps, ["T2-EI"], "a genuine gated precondition was suppressed")

    def deps_of(self, line):
        """find_dead_deps over one live ticket whose body is `line`, against a deferred T2-EI."""
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as fh:
            fh.write(ticket(">", "T2-EI", "dep") + "\n" + ticket(" ", "T9-A", "live", line))
            path = fh.name
        try:
            return [d["depends_on"] for d in nw.find_dead_deps(nw.parse(path))]
        finally:
            os.unlink(path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
