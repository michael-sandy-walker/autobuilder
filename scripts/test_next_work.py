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


if __name__ == "__main__":
    unittest.main(verbosity=2)
