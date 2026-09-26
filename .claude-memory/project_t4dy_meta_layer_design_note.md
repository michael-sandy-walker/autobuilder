---
name: project-t4dy-meta-layer-design-note
description: "T4-DY design (2026-09-26): the 'more intelligent meta layer' the user asked for is a DIAGNOSTIC layer on the informational axis -- a stuck signature is a question about a missing fact, answered by an authority (structure first, observation second, a model only to propose which fact), with a ledger of questions asked and whether the signature stopped. Read before building anything under T4-DY."
metadata:
  type: project
---

# T4-DY: a meta layer that reasons about a stuck loop -- design note (2026-09-26, no code)

**The user's direction (2026-09-26):** not the narrowed ladder, not a close -- "we really need a more
intelligent meta layer". This note says what "more intelligent" means, grounded in what ended real
grinds, and proposes the smallest first slice with a falsifiable replay DoD. Nothing here is built.

## What the archive says a stuck loop is missing

- **Slice 1 (`scripts/grind_reaction_replay.py`, 11 runs, 32 signatures):** 25 faded, 7 outlived every
  in-loop reaction, all 7 in unfulfilled runs. Subgoal switches (up to 8) and replans (up to 7)
  followed and did not stop them; no rewind was ever applied. **Each outlived grind was ended by a
  FACT built in from outside** (`DeclaredAccessorResolver`: the type declares `id()`, not `getId()`;
  `SubgoalSourcePromptBlock`: the record's real constructor). The model repeated one move for want of a
  fact; diverging, replanning or rewinding supplies none.
- **Every one of the 32 signatures is one question family -- "what does the code actually declare?"**
  22 missing-member (`X has no getY()`), 3 constructor mismatch, 4 bare `cannot find symbol`, 2
  inference, 1 protected-path refusal. All answerable from the tree without a model.
- **A second stuck shape GrindDetector does not count: the same closure refusal repeated.** 19x
  (participation-b `2026-09-25_17-43-13`, halt), 12x (rung1 `2026-09-24_15-26-47`, halt; structdiff
  `2026-09-25_06-26-20`, deadline). Its question is different: "what does the verifier need to see
  that the evidence does not show?" -- sometimes a missing assertion, sometimes cropped evidence
  (T2-FI), sometimes a criterion the seed never stated (T3-CC).

**So the intelligence that was missing is on the INFORMATIONAL axis of the self-model**
([[project-introspection-as-north-star]] -- "give a less-capable model the missing information"),
not the behavioural one. A ladder of generic moves is behavioural; that is why the gate read negative.

## The design: a stuck signature is a QUESTION, and questions have authorities

([[project-invented-facts-have-authorities]]: ask whether structure can answer before asking a model.)

1. **One home for "stuck"** (the ticket's first point survives): every detector -- GrindDetector's
   repeated failure signature, a repeated closure refusal, RootConvergenceTracker's flat root, the
   subgoal-exhaustion backstop -- reports a typed `StuckObservation(signature, site, lines, count,
   first/last cycle)`. Detectors stop owning reactions.
2. **Question, not countermeasure.** For each observation the layer derives the QUESTION the signature
   poses, deterministically where it can: a compile signature naming `X.m()` asks "what does X declare?";
   a repeated refusal asks "which requirement, and what evidence would answer it?"; an assertion
   `expected A but was B` asks "what does the entry point return?". Where no rule applies, the local
   model may PROPOSE the question ("which fact would let this move succeed?") -- proposing, never
   answering.
3. **Authorities answer, in a fixed order:** (a) structure -- declarations in the tree and on the
   classpath, the seed and its obligations; (b) observation -- run the test / boot / probe and record
   what happened (`ValueObservationProbe`, `ObservedBehaviour`, `BootGate`); (c) only then the model,
   and its answer is marked as a model's reading. Existing capabilities become authorities rather than
   ad-hoc prompt blocks: `DeclaredAccessorResolver`, `SubgoalSourcePromptBlock`, `ValueObservationProbe`,
   `TestRunGate`'s failure lines, `SeedStatusSupport`.
4. **The answer is delivered where the next move is made, and the delivery is recorded.** A fact is bound
   to the signature and to the subgoal; the evolution prompt consults the layer's open diagnoses for
   its subgoal BEFORE it is built (this is what T4-AI's meta lane lacked: nothing consulted it).
5. **Ledger and escalation (the ticket's third point, re-aimed):** keyed by signature -- question asked,
   authority consulted, fact delivered, and whether the signature recurred after delivery. A recurrence
   AFTER a delivered fact means the question was wrong or the fact insufficient: ask the next question
   (another authority, or a model-proposed question) -- not the same move again. When no authority
   answers, the layer does the two things slice 1 supported: **a DIAGNOSIS REQUEST to the operator**
   (signature, failing lines, questions asked and answers given) and **an early in-loop halt**, which
   today only `scripts/early_stop.sh` does from outside.
6. **What the layer may decide alone vs hand to the user:** alone -- ask, fetch, deliver, record,
   escalate to the next question, halt with a diagnosis request. Never alone -- change the seed, mark a
   requirement as not binding, rewrite a protected path, or declare a goal met. Those stay with the
   operator (T3-CC's lesson: authority over binding force belongs to the specification).
7. **Deterministic core.** The layer's control flow (which question next, when to halt) is rules over
   the ledger. A model fills in question CONTENT where rules cannot, and answers only as the last
   authority, labelled. The local model's judgement is the binding constraint
   ([[project-local-first-constraint]]); the layer must not depend on it being good.

**What is deleted when this lands** ([[project-three-disciplines-lens]]): GrindDetector's prose-only
reaction (it becomes a reporter), the repeated-refusal handling scattered in closure code, and each
detector's private "what to do when stuck". Not stacked beside them.

## Smallest first slice, with its DoD stated in advance

**Slice A -- the question router, offline, no loop change.** A pure function
`StuckObservation -> Question` plus `Question x tree -> Answer?` over the structural authority only,
replayed over the archive.
- **Replay:** for each of the 32 archived grind signatures (and the 3 repeated-refusal runs), freeze the
  tree nearest the first notice (checkpoint archive or the kept after-run tree), derive the question,
  and ask the structural authority.
- **DoD, falsifiable:** (1) for the 7 OUTLIVED grinds, the answer produced from the frozen tree at first
  notice is the same fact the out-of-loop fix later supplied (checked against `DeclaredAccessorResolver` /
  the record source) in **at least 6 of 7**; (2) on the 25 faded signatures it produces **no wrong fact**
  (an answer contradicting the tree is a failure; "no answer" is allowed); (3) for the repeated-refusal
  runs it names the requirement and states what evidence is missing in at least 2 of 3, judged against
  the session's own diagnosis recorded in the roadmap. **If (1) fails, the design is wrong** (the missing
  thing was not a structural fact) and the next slice is not built.
- Pure and Spring-free from the start (T5-BR): the router and the structural authority in one small
  module -- itself a candidate for the ticket-driven toy-case pipeline.

**Slice B (only after A passes):** in the loop -- detectors report `StuckObservation`s, the router's
answers reach the evolution prompt for the subgoal, the ledger records delivery and recurrence, and an
unanswerable signature produces a diagnosis request plus a halt. DoD: a replayed grind shape
(participation-totals' `ChannelRequest` constructor grind, with the source block turned off to recreate
the missing fact) is ended in-loop by a delivered fact, and the ledger shows it.

## Decisions needed from the user

1. **Is the informational reading right?** The design bets that "more intelligent" = finding the missing
   fact, not choosing a better generic move. The evidence (32/32 signatures one family; every outlived one
   ended by a fact) supports it, but it is two targets and ~2 days of runs.
2. **The diagnosis request's channel.** A file in the run dir, a behaviour event, the mission-control UI,
   or a spoken narration (T5-BI)? It is the layer's hand-off to you and should land where you look.
3. **May the layer halt a run by itself?** Slice 1 supported an early in-loop halt; today only
   `early_stop.sh` (an operator tool) pauses. Halting on "no authority can answer" would be the first
   self-initiated stop.
4. **Model-proposed questions:** allowed from slice B onwards, or only after a replay shows the local
   model proposes useful questions on archived signatures?
5. **(added 2026-09-26 after slice A was measured) Criterion (1) failed: 4 of 7.** The three misses are bare
   `cannot find symbol` signatures -- the detector drops the symbol and location the question needs. Either
   (a) the signature format is the defect: keep the compiler's symbol/location lines and re-measure against
   the same bar of 6; or (b) take the stated rule at its word and revisit the design. Slice B waits on this.
**User decisions 2026-09-26 (evening):** #4 -- yes, the model may propose questions; a proposed question may be
answered by a terminal execution agent (read-only for now), and questions may be kept to question the model in
later sessions. #5 -- (a): the dropped symbol is the defect; fixed, and slice A re-measured at 7 of 7 against the
same bar (ambiguous `List`; the adopted tree must be an authority beside the current one in slice B). #1 answered
yes in substance; #2 (diagnosis channel) and #3 (self-initiated halt) still open.

## Slice B built (2026-09-26, `7da6f73f`) -- what exists now

- **Home:** `core/stuck/` -- `StuckObservation` (typed report: source, signature, subgoal, cycle, detail),
  `StuckLedger` (pure: counts once per cycle unless an answer was delivered in between; WATCHING -> PENDING
  at the 3rd occurrence, 1st for the brakes -> DELIVERED -> ENDED on a green build, or back to PENDING at the
  NEXT rung on a recurrence -> UNANSWERED with a diagnosis request when no question is left),
  `StuckDiagnosis` (pure question ladder), `StuckLayer` (Spring wiring, `meta/stuck-ledger.json`).
- **Reporters:** GrindDetector (build signatures + protected-write refusals, every occurrence), closure refusals
  (`GoalPlannerService.recordClosureRefusal`, keyed by goal description + reason, no ids), RootConvergenceTracker
  (flat root at half its window), the exhaustion runaway. GrindDetector's prompt paragraph is DELETED.
- **Questions:** BUILD -- (0) what the code declares for the name (StuckQuestions over the current tree AND the
  adopted baseline, differences stated) then (1) the declaring file whole, adopted first (never cut; a file over
  12k chars is no answer). PROTECTED_WRITE -- the protected file as it is. CLOSURE_REFUSAL (criterion 3's first
  rule) -- the refused requirements and the gate's reason, plus the code-like names in each criterion the seed
  never mentions; such a name makes the answer ALSO a diagnosis request (only the operator binds or releases a
  requirement). FLAT_ROOT / EXHAUSTION -- no question: an immediate diagnosis request.
- **Delivery:** the evolution prompt asks every due question while it is built and carries the answers (one answer
  once, under every signature it answers; within half the free headroom, a non-fitting answer left out whole).
  Build answers stand until a green build; others only while their subgoal is current.
- **Decisions #2 and #3, taken autonomously for this slice (the user said "act fully autonomously"; override
  freely):** #2 the diagnosis request lands as a `diagnosis_request` BEHAVIOUR EVENT (runs are analysed from the
  behaviour log first) plus the ledger file; #3 the layer does NOT halt a run -- the existing brakes keep their
  own halts and early_stop stays the outside cut. Reason: the smallest reversible step; a self-initiated halt
  should wait until diagnosis requests have been seen in the field and judged.
- **2026-09-26 late evening: the user CONFIRMED #2 and #3 as taken ("No, they're ok").** Chose structure before the
  terminal agent: two lookups added (`8cff94ab`) -- ambiguous names resolved by imports; a new second BUILD question
  setting the failing line's constructions beside their adopted declarations (the detector now receives the failed
  change's files). BUILD ladder is now: declared names -> the failing line -> the declaring file whole.
