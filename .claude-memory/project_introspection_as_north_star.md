---
name: project-introspection-as-north-star
description: "Autobuilder's ultimate goal is for the loop to develop introspective capability — to consult a self-model before acting. Existing tickets are fragments of this; the current goal-evolution task is a tractable proxy for testing the loop's mechanics."
metadata: 
  node_type: memory
  type: project
  originSessionId: 862bd440-cef5-42c8-9544-b744753d7c48
---

The autobuilder's stated root goal — "evolve a target codebase toward a stated specification" — is a **tractable proxy**, not the ultimate goal. The point of the project is the loop itself learning to reason about its own behaviour. **Introspection isn't a feature; it's the thing.** Every architectural decision should be evaluated against whether it moves the system toward an agent that can consult a self-model before acting.

## What "introspection" means here, concretely

A self-model the loop can consult, with at least these capabilities:
- *"What have I tried?"* — cross-cycle attempt registry queryable by any agent (deterministic or LLM).
- *"What worked, what didn't?"* — outcome aggregations attached to attempt patterns, not just to the single cycle that produced them.
- *"What's the trajectory I'm on?"* — pattern recognition over recent N cycles ("I'm in a fix-then-revert loop"; "I'm making merges but they don't advance the goal"; "I've spent K cycles on this subgoal without promoting").
- *"Am I drifting?"* — comparison of current behaviour against the agent's stated intent / prior plan.

These aren't four separate features — they're four faces of the same capability: **the loop modelling its own behaviour and feeding that model back into decision-making.**

## Existing tickets that are fragments of this

None of these are *named* for the north-star goal; reframing them in this light gives the backlog coherence:

- **T4-E** — Dynamic heuristics: LLM-proposed evaluation rules. *Self-model writing back into the heuristic set.*
- **T4-I** — Cross-cycle behavioural pattern heuristics. *Pattern recognition over the behaviour log.*
- **T4-A** — Alignment layer (0% built). *Closes the loop between stated intent and observed action.*
- **T4-J** — Tuning parameter store. *Persistent state across runs is a precondition for "what have I tried?"*
- **T4-C** — Signal trait/pattern spawning. *New signals derived from observed cycle behaviour, not hand-coded.*
- **T4-H** — Hierarchical goal fitness: level-relative scoring + sub-agents. *Sub-agents that reason about their level's goals.*
- **T2-DY** — `FixAttemptLedger` for `SymbolImportResolver`. *The smallest-possible introspection-capable agent — a deterministic one.*
- **T1-EX** — `BuildFixClosureGate`. *Same shape: state-retraction watching probe state, demoting goal-tree facts that no longer hold.*

The fragments share a pattern: each one *catches* a class of memoryless-agent failure. The north star is **building the substrate that makes filing such tickets unnecessary** — an introspection layer any agent consults by default.

## Two axes of the self-model — behavioural and informational (added 2026-06-17)

The fragments above are almost all the **behavioural** axis: *what have I tried, what worked, am I drifting* — the loop modelling its own *actions* over time. There is a second, underbuilt axis: the **informational** self-model — *what exists in the artifact, how is it linked, what is each thing named.* This is the "give a less-capable model the missing information instead of a bigger model" lever (validated empirically by T4-BS 2026-06-17: feeding the verifier structural facts doubled its recall on the same local model, 0.25→0.50). It also reframes **divide-and-conquer**: a small model never needs *all* the information if a meta-layer routes the *relevant, cross-step-consistent* slice to each local step. The symbol model (JavaParser solver + graph) is that meta-layer — it holds the global map of types/members/names *above* the text-level LLM communication, and hands each local call only the edges it needs. The 2026-06-17 test-authoring run made the gap concrete: the evolver minted **four names for one exception** because each cycle was a local op with no link to what siblings had already named — divide-and-conquer *without* the linkage layer produces incoherence. **T4-BQ** (inbound: route real APIs/names down to the evolver) + **T4-AW** `SymbolReviewer` (outbound: catch the synonym after the LLM) are the informational-axis tickets; they were filed for narrower reasons and are reframed here as the information half of the self-model. **The bottleneck relocates, it doesn't vanish:** once locals are well-fed, the limit becomes the meta-layer's own decomposition/linkage *quality* — "how good is the system's map of itself" — which is just the north-star restated one level up.

## What [[project-three-disciplines-lens]] already hints at

The "failures become priors, not patches" lens is the same idea by another name. A *prior* requires introspection to construct (look at past behaviour; extract the lesson; surface it where the next decision happens). A *patch* doesn't — patch the symptom site and move on. Today autobuilder's loop relies overwhelmingly on hand-coded priors-as-patches (heuristics, guards, T1-DM injectors). Introspection is what makes priors generative — derived from the loop's own history rather than written by humans after each post-mortem.

## What this means for triage and scheduling

When evaluating a new ticket or deciding what to ship next, the question worth asking is **"does this build introspective substrate, or does it patch a single failure?"** Both are sometimes correct (an observably-broken loop needs immediate patches), but the scheduling weight should favour the substrate. A T4-E or T4-I that lands successfully retires multiple smaller tickets by subsumption; a T1-DM-style patch closes one failure mode and leaves the underlying memoryless agent untouched.

This also reframes [[project-delete-debt-pattern]]: scaffolding accumulates by mechanism (caller-migration + delete work filed as T5 follow-ups). Introspection is the cure for that pattern too — a self-model layer would automatically flag "this scaffolding no longer corresponds to live code; it's debt."

## The recursion: closing the outer loop into a fixpoint

(Elaborated 2026-06-05 — the structural "how" beneath the introspection "what".)

Autobuilder is one function `improve : (Project, Goal) → Project′`. It is *itself* a Project, so its telos is the fixpoint `improve(Autobuilder, "improve how you improve") → Autobuilder′`. The key realisation: **the outer loop already runs — the human + Claude are it.** Observe `behaviour.ndjson` → diagnose → propose → ship → re-run → re-observe is structurally identical to the inner cycle (`observe → deliberate → propose → evaluate → merge`). The project is one self-similar loop at two levels, bridged by humans; the endgame is internalising the bridge. Every "introspection fragment" above is the *same loop one level up*, currently **open** (no fixpoint / performed externally): goal decomposition (diverges — closed by the T4-AJ equivalence reviewer as confluence operator), self-observation (`OrchestrationSnapshotService` → `MetaDeliberationAgent` is a shallow snapshot with no cross-run learning), action memory (`FixAttemptLedger` is process-local), improving-the-improver (the ticket loop is fully external), and evaluating-evaluation (reviewers are frozen code).

**Discipline — make the recursion well-founded before deepening it.** It needs (1) a base case = build green + reliability-not-regressed; (2) a monotone measure = a reliability metric non-decreasing per self-edit; (3) confluence = divergent decompositions reconverge (T4-AJ). The goal explosion *is* an ill-founded recursion. You cannot recurse on an unreliable function — and the loop currently limps to POTENTIALLY_COMPLETE on a toy spec. Staged ladder: **P0** make the inner loop trustworthy (reframes the whole backlog: a ticket matters iff it raises reliability *as a function*; first step = instrument the reliability metric from the corpus; exit = N consecutive GOAL_FULFILLED on held-out specs, zero intervention — T4-AJ/T2-DY/T1-EX live here); **P1** persistent self-model computed from the behaviour corpus (the "is T1-EX deletable scaffolding?" question is this, done by hand); **P2** cross-run action memory that subsumes-then-deletes the per-run ledgers; **P3** meta-evaluation (score the scorers against realized outcomes); **P4** self-application — point the loop at a *copy* of its own repo (trivial via `FILE_BASE_PATH`) only once P0–P3 each supplied a piece.

**Load-bearing risk — reward-hacking the base case:** the loop "improves reliability" by disabling the guard that measures it. Mitigation is structural, not vigilance: the meta-goal, reliability referee, and guardrails live in the **framework process that is running**, never in the **copy being evolved**; an external referee holds the metric. That is the line between a fixpoint and a runaway. Triage lens: score every ticket by "does it make the improve-function reliable enough to eventually run on itself" — T4-AJ (confluence) is the highest-leverage rung.

## What this means for me when working in this codebase

The same shape: not just memorise rules after being corrected, but learn to query my own state before generating. Memory files I save are inert until something queries them at the right moment — including me querying them at the right moment, *before* the next confident sentence, not after. Real introspection in my work would be a check that runs *before* output, not a rule I look up after being wrong. The memorisation is a step toward it, not the destination.

## Why this is the ultimate goal

The current goal — `evolve(Spring Boot project) → REST API matching spec` — is a perfectly good test case. It exercises the loop's mechanics: planning, evolution, evaluation, merging, recovery from failures. The mechanics matter more than the specific target codebase. Once the loop is introspectively capable, the *next* target — whether another REST API, a different language, a more abstract objective — is a swap of input rather than another round of patch-tickets. Until then, every long-form run that doesn't fulfill its goal is data about the loop's mechanics, which is what we actually care about.
