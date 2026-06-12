---
name: project-completion-edge-gates
description: "The 2026-06-12 pattern: every claim of 'done' needs a deterministic external proof at the completion edge, and every diagnosis needs an actuator — the day's 8 ships all reduce to these two rules."
metadata: 
  node_type: memory
  type: project
  originSessionId: e5ead51e-ff28-4bd5-8310-673469c4a385
---

**The completion edge is now a three-proof sequence** (shipped 2026-06-12, all field-born from real false-dones the same week): coverage gate ("spec features implemented?", LLM-verified against code) → package gate T4-AN-8 ("assembles into a startable artifact?", `mvn package -DskipTests` — deterministic, build tool owns the definition) → test gate T4-AN-2 ("its own tests pass?", `mvn test`, abstains when no tests exist). Each runs ONCE per completion claim, never per-cycle; failures inject `[BUILD_FIX]` work items via `injectCoverageGaps` and refuse completion. Field proof the pattern converges: 8 consecutive package-gate refusals on 2026-06-12 drove the loop to fix broken tests AND create the missing entry point, then completed (first verified-packageable GOAL_FULFILLED).

**The dual rule — a diagnosis without an actuator is a leak.** User question that triggered it: "do we really need unit tests for error identification?" — no; the verifier had already diagnosed the missing 404 statically, and the loop banked the diagnosis then let the goal die, blocking root closure. T4-BP-4 (residue re-scope) gave verification failures the same actuator every other diagnosed failure class has (compile→BUILD_FIX, gap→inject, packaging→gate). Check any new verdict-producing mechanism: where does its negative verdict become a work item?

**Corollary on brakes:** termination measures must count every evidence class the loop produces. The T4-I brake counted only completions-with-merge and fired in the same second a 3/3-verified rescue promotion landed; fixed to count verified-banked criteria too. When adding a new evidence channel (like the rescue), audit which brakes/measures should consume it.

Caveats that remain open: "packageable" ≠ "boots and responds" (no runtime smoke yet); gates only fire on completion claims, so runs that halt earlier never exercise them. See [[project-introspection-as-north-star]] — these gates are the "consult external proof before believing your own claim" fragment of the self-model.
