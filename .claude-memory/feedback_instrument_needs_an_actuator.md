---
name: feedback-instrument-needs-an-actuator
description: "An observability ticket must ship its actuator in the same scope — a mechanism that only computes and renders is not done, and the readout-first sequencing is the exception that has to be argued for"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: f54dc5b0-d9fb-4267-bae8-a3bce5956657
---

A ticket that computes a fact must, in the same scope, wire something that ACTS on that fact. Building the detector and filing the actuator as a follow-up is not a ticket half-done; it is a ticket that will read as done and change nothing.

**Why:** Raised by the user 2026-07-31 after they asked why 261 `docs` commits against 111 `feat` had produced 2 perfect runs in 45. The measured composition: 84 files under `analysis/`, 5 under `observability/`, 28 offline Python instruments — against 33 agent classes. Instruments are cheap to build, always "succeed", and each generates its own documentation, so the ratio drifts on its own without anyone choosing it.

The same session produced three independent confirmations that observation was never the binding constraint. `T4-DC` computed the right fact for months and delivered it at cycle 24 of 25; fixing the timing moved the failure from "too late" to "no owner". `T4-CU` put a validation-trigger fact in twelve prompts while the loop's own bookkeeping asserted the opposite. `T2-EY` relayed a correct rejection reason into 28 prompts and the generator re-emitted the same bytes 33 times. In each case the detector existed and the wiring was the defect. This is the same shape as [[feedback-activity-not-progress]] one level up: there, a mechanism firing is not the outcome moving; here, a mechanism *reporting* is not the loop *acting*.

It is also why [[feedback-ship-full-scope]] does not already cover this. That rule is about migration debt — build, rewire callers, delete. This one is about a different half-ticket: build the detector, never wire a consumer at all.

**How to apply:** When filing or reviewing any ticket whose deliverable is a readout, a count, a block in a prompt, or a summary field, ask what changes its behaviour as a result, and put that in the same scope. Prose relayed into another agent's prompt does NOT count as an actuator — it is context the generator may ignore, and measurably does; an actuator constrains what the next step may do, or changes what state the loop records. Where readout-first sequencing is genuinely right (`T4-DA` was, because the user asked for communication explicitly), say so on the ticket and name the condition under which the actuator gets wired — otherwise the unwired half becomes permanent. When a measurement ships, record both the exposure and what the actuator changed about it: a mechanism that logs only what it saved reads as a free win and hides whether the problem shrank.
