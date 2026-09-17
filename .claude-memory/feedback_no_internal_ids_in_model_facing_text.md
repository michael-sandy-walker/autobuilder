---
name: feedback-no-internal-ids-in-model-facing-text
description: Project-internal identifiers (ticket ids, gate slugs, signal names) are meaningless to the LLM; translate or drop them at any prompt boundary.
metadata:
  type: feedback
---

Ticket ids, gate slugs and signal names are the project's vocabulary, not the model's. `T4-BV`
identifies a gate precisely for anyone holding the roadmap and says nothing to a model that is not.
So an internal identifier may live in logs, events, metadata and dashboards freely, and must be
translated or dropped wherever text crosses into a prompt. Where a gate has both an id and a prose
description of what it observed, the prose is the part that travels.

**Why:** raised by the user on 2026-09-17 against `closure_refused`'s `gate` field, as a question
rather than a correction -- *"is the gate String communicated to the LLM? Because if it is, the LLM
probably does not know anything regarding that."* The field was not prompt-bound (behaviour events
sink to the NDJSON log and the dashboard's SSE stream; agents only emit; no contract reads one), and
an audit found no ticket id in any prompt-bound literal across `protocol/`. But nothing ENFORCED
that, and relaying a gate's diagnosis into a prompt is a move already made once -- `T4-BX` relays
evaluator rejection reasons verbatim into the evolution and planner prompts -- so the same route
exists for refusal information.

**How to apply:** when adding a field that names an internal thing, decide its audience in the same
change and say so where it is declared. When relaying a diagnosis to an agent, send the description
of what was observed, never the id of what observed it. This is the inverse direction of
[[feedback-no-target-domain-in-framework-strings]] and
[[feedback-no-ecosystem-jargon-in-framework-code]]: those keep the target's vocabulary out of
framework code, this keeps the framework's vocabulary out of the model's context.
