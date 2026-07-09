---
name: project-meta-concrete-target-hint-idea
description: "NOTE (not a ticket) — hypothesis that meta's per-step request text gives evolution a concrete target, reducing wasted #NoDiff/empty-diff attempts; not confirmed, not blocking T4-AI-2+5 deletion, worth a look before that deletion removes MetaIntention rendering."
metadata:
  node_type: memory
  type: project
  originSessionId: 5661e26d-d787-4634-abae-4806a4d508e8
---

# Idea: cheaply reproduce meta's "concrete target" effect without the full meta lane

Surfaced 2026-07-09 during the T4-AI-2+5 signal-rate re-diagnosis (see `project_resume_state.md`'s
2026-07-09 blocks). Recorded as a NOTE per [[feedback-ticket-definition-of-done]] — not filed as a
ticket, no prose committing to build it. Read this before actually deleting `MetaIntention` rendering
(the T4-AI-2+5 scope) in case someone wants to extract this piece first — otherwise it disappears
with the rest of the meta lane.

## The observation

Under meta-ON (8-pair aggregate, `/tmp/t4bo_aggregate/{on,off}_arm_all.ndjson`), evolution attempts
are both more frequent (`mutation_proposed` 1.36× OFF) AND more likely to produce a real diff:
`EVOLUTION_NOOP` (LLM declares `#NoDiff`) falls to 0.76×, `STAGNATION`'s empty-diff detector falls to
0.82×, relative to OFF.

## The hypothesis (NOT confirmed)

Meta's per-step `request` text is rendered into the evolution prompt as `MetaIntention` — a concrete,
specific instruction each cycle (confirmed sample: `"Implement the GET /books endpoint with proper
HTTP method and path mapping."`) vs. the fitness-driven path's more generic subgoal-level prompt.
A sharper target plausibly makes the LLM less likely to conclude "already done" and claim `#NoDiff`.

**Open question, not resolved:** is meta's request text meaningfully MORE specific than the current
subgoal's own description, or is it substantially just restating the subgoal? If planner/analysis
already produce subgoal descriptions this concrete, there may be nothing here to extract — the
apparent effect could come from something else about the meta-plan sequencing instead (e.g. issuing
evolution+evaluation back-to-back in the same cycle). Not isolated with a direct comparison of ON vs
OFF prompt text; this is a correlation-derived hypothesis, not a traced mechanism like the signal
findings in the same investigation were.

## Why this does NOT block the T4-AI-2+5 deletion

Even taking the hypothesis at face value, its downstream effect nets to zero on both metrics that
matter: final smoke score is a tie (OFF 17.29 vs ON 17.43) and wall-clock duration is a tie (OFF
49.1min vs ON 51.2min — the "fewer wasted attempts" saving, if real, is fully offset by the extra
LLM call's overhead). Whatever this mechanism buys, it doesn't show up in outcome or efficiency.
Nothing is lost by deleting the whole lane as scoped.

## The idea, if ever picked up

If evolution's prompt genuinely benefits from a concrete "next specific thing to do" line, that could
plausibly be produced WITHOUT an LLM deliberation call — e.g. derive it deterministically from the
current subgoal's structure (next uncovered endpoint/criterion) the same way the signal-driven path
already knows which subgoal is active. Cheap to test in isolation once T4-AI-2+5 has landed and the
comparison baseline is clean (meta-off is now the default). Not scoped, not prioritized — a "maybe
someday" note, surfaced so it isn't silently lost when `MetaIntention` rendering is deleted.
