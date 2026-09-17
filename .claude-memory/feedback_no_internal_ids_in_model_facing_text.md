---
name: feedback-no-internal-ids-in-model-facing-text
description: An identifier belongs in a prompt only if that prompt DEFINES it. Ticket ids and gate slugs never do and are noise; three signal names are defined and emitted back, so they are an interface.
metadata:
  type: feedback
---

The test is not whether a token is internal. It is whether the prompt **defines the token before
using it**, and whether the model is expected to send it back.

- **Defined, therefore an interface.** `InterpretationProtocolContract` lists *"Allowed values:
  REPLAN_REQUESTED, LOW_CONFIDENCE, DIVERGENCE_REQUEST"*, says when to emit each, shows a
  WRONG/CORRECT pair, and `InterpretationResponseParser` parses them back out. Three of roughly
  thirty-five signal types are exposed this way. Those three are model vocabulary because the
  contract teaches them; the other thirty-two are not, and neither is a signal name that merely
  happens to be in scope.
- **Never defined, therefore noise.** Ticket ids (`T4-BV`) and gate slugs (`criteria-gate`) appear
  in no prompt-bound literal and nothing would explain them if they did. Where a gate has both an id
  and a prose description of what it observed, the prose is the part that travels.

**Why:** the first version of this memory said *"ticket ids, gate slugs, signal names"* were all the
project's vocabulary and not the model's. The user challenged the third -- *"I don't know if the same
goes for signal names"* -- and the challenge was right: signal names are the counter-example, taught
in the contract and parsed back, which makes the internal-versus-external framing wrong. Written
without checking, in a memory sitting next to
[[feedback-grep-the-behaviour-not-the-name]], which is the rule it broke. Behind the original
question: `closure_refused`'s `gate` field is NOT prompt-bound (behaviour events sink to the NDJSON
log and the dashboard's SSE stream, agents only emit, no contract reads one), but nothing enforced
that, and `T4-BX` already relays evaluator rejection reasons verbatim into the evolution and planner
prompts -- so the route exists.

**How to apply:** when a token is about to cross into a prompt, find the line that defines it for the
model. If there is none, send the description of what was observed instead of the id of what
observed it, and say in the declaration who the field is for. Adding a token to a contract's allowed
values is what makes it shared; being useful to a script is not. This is the inverse direction of
[[feedback-no-target-domain-in-framework-strings]] and
[[feedback-no-ecosystem-jargon-in-framework-code]]: those keep the target's vocabulary out of
framework code, this governs what may cross into the model's context.
