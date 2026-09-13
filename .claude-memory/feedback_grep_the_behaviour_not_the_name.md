---
name: feedback-grep-the-behaviour-not-the-name
description: "A grep finds a SPELLING; a claim is about a BEHAVIOUR. Four times in one session I searched for a name, a call form, or a token I had invented, and reported its absence as the absence of the thing."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: e28f5711-fbbe-4a45-b2f2-a4804dfd3c7f
  modified: 2026-09-13T13:27:35.010Z
---

Searching for a name answers "does this string appear". It does not answer "does this behaviour
exist", "who reads this quantity", or "how often did this happen". Whenever a grep returns nothing
or returns a tidy number, the next question is whether the SPELLING is the only way the thing could
have shown up.

**Why:** four instances on 2026-09-12/13, all the same shape, each one producing a confident and
wrong published claim:

- **A dead knob generalised to a dead behaviour.** Audited `autobuilder.evaluation.heuristic-threshold`,
  found nothing read it, and concluded no threshold compared against the LLM score at all. The real
  boundary is `0.75` hardcoded in four places. *A dead knob is evidence about that knob, never about
  whether the behaviour lives elsewhere.* Grep the VALUE, not the name.
- **One call form mistaken for all readers.** Enumerated a signal's consumers with
  `hasStrongRequest(KIND, …)` and reported "two thresholds, three outcomes". `DefaultProblemDetector`
  reads the same signal via `requestUrgency(KIND)` and compares it itself: three thresholds, four
  outcomes, and the design changed once that was known. *A quantity can be consumed through any
  accessor; enumerate by the quantity, not by the convenience method.*
- **A token that does not exist, whose absence read as zero.** Grepped `COMPILE_FAILED` and reported
  "14 COMPILE_OK and zero COMPILE_FAILED". The enum value is `COMPILE_ERROR`, which occurred 90
  times. *A search for an invented token always returns zero. Confirm the vocabulary from the
  source (the enum, the schema, a known-positive sample) before counting its absence.*
- **A field dismissed because of its name.** Declared a question unmeasurable because "confidence is
  not emitted"; the behaviour record's `weight` field IS the confidence. *Check what a field
  CONTAINS before concluding a quantity is unrecorded.*

The failure is asymmetric and that is what makes it dangerous: a grep that finds something is
usually right, while a grep that finds nothing is only as good as the guess about spelling. Absence
of a string is the weakest evidence in the repo and it keeps getting reported as the strongest.

**How to apply:**
- Before reporting a zero, run the same predicate against a case known to be POSITIVE. If it cannot
  find the thing where the thing certainly is, the zero means nothing. This is the same
  absent-and-present check [[feedback-monitor-every-terminal]] demands of watchers, applied to
  one-off searches.
- Source the vocabulary rather than recalling it: read the enum, the record definition, the payload
  keys of one real sample. `python3 -c` over one behaviour file beats a remembered token name.
- Enumerate consumers by the QUANTITY (who can reach this value at all) before narrowing to a call
  form, and say which accessors the enumeration covers.
- State the search that produced a negative claim in the claim itself, so the next reader can see
  its blast radius — "0 hits for `T4-BX`, and the sibling block greps 24, so the log does carry
  these" is a finding; "it never fired" is a guess.
- Related: [[feedback-count-events-not-log-lines]] (counting the record instead of the event) and
  [[feedback-verify-population-before-claim]] (the corpus behind an all/most/never claim).
