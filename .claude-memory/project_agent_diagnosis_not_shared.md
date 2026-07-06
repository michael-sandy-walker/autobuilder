---
name: project-agent-diagnosis-not-shared
description: "When one agent rejects work it writes the reason, then throws it away — the next agent never learns why. The rejectionFeedback field is set to null. Root cause of the 2026-07-05 stall → T4-BX."
metadata: 
  node_type: memory
  type: project
  originSessionId: 689aea34-e955-4dec-9bae-89ff1d147ffd
---

**What broke (2026-07-05 run, ended GOAL_EXHAUSTED).** The loop kept rewriting Loan tests while the real missing piece was the `LoanRepository` class. Each agent had one piece of the answer and none reached the agent that could act: the analysis agent knew the class was missing, the evaluator knew the mutations were test-only, the planner saw it going in circles — but the give-up brake saw only "7 cycles, no merge" and stopped. The build compiled fine the whole run, so the problem was aiming at the wrong file, not a broken build (a different terminal wall than [[project_completion_binding_constraint]]'s red-build one).

**The concrete hole.** When the evaluator rejects a mutation it writes the reason (e.g. "only edits a test file, doesn't implement the rule") and then discards it — the next evolution attempt never sees it and guesses the same wrong way again. There's a field meant to carry it, `CycleContextData.rejectionFeedback`, and it's hardwired to `null` at `CycleContextCoordinator:130` (verify line before citing). An earlier version passed the reason via `EvaluationOutcome.feedbackMessage`; T4-BD deleted it for being fragile text-matching and nothing replaced it. Between cycles the only thing passed is a name + a number (`REPETITION_DETECTED = 3.0`), which can't say "write the repository instead of tests."

**Fix → T4-BX.** Fill in `rejectionFeedback` and the analysis finding with a small structured value (what kind of problem + which class), feed it to the evolution and planner prompts, and have the give-up brake check for an unaddressed missing thing before halting. Do NOT paste the reason back as a sentence — that's the text-matching T4-BD removed. Not a new subsystem: it's wiring up a field that already exists. Lens: [[project-introspection-as-north-star]], [[feedback-one-invariant-one-home]], [[feedback-activity-not-progress]].
