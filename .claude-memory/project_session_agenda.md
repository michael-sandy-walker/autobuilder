---
name: project-session-agenda
description: "⚠ SUPERSEDED ORDERING — read the 2026-08-23 REORDER section at the END of this file first; the S1-S6 order below predates T1-FS and wave 2's measurements and is kept for its reasoning, not its sequence. Original: Sequenced agenda for the next ~6 sessions (written 2026-08-06). Orders EXISTING roadmap tickets plus the user's 2026-08-06 backlog; is rationale, never a parallel queue. Each session carries a first action, a done-when, an explicit do-not, and what it unblocks. S1 closes the verification thread; S2 pays the cleanliness/prose debt the user raised three ways; S3 cuts the first T4-DD seam; S4 builds layer D's first consumer (T4-CT); S5 runs T6-I, the only thing that can falsify the whole accumulating-system thesis; S6 is the second target's yardstick. Standing rules and the deliberately-not-doing list at the end."
metadata: 
  node_type: memory
  type: project
  originSessionId: 3178f937-7c5a-43f5-9312-bcd7c7aa0d08
  modified: 2026-08-06T02:18:10.748Z
---

# Session agenda — next ~6 sessions

**This file is RATIONALE, not a queue** ([[feedback-roadmap-is-the-queue]]). Every action below names a
ticket that already exists in `roadmap.md`, or says explicitly that a ticket must be filed first.
The roadmap decides what is real; this decides only the ORDER, and why.

**State it was written from (2026-08-06):** 29 open · 7 in-progress · 31 deferred · 385 closed.
Suite 2465/0/5. Bookkeeping 0 warnings. Per-attempt 78% over the last ten runs against a 75% target.

---

## S1 — Close the verification thread

**Goal:** the loop can see its own defects, and we know the number.

**First action:** launch a run; while it goes, measure why `404`/`409` shapes decline. That is the
one real build left in `T4-DB` — measured on a HEALTHY artifact, the residual abstentions are
10 creates · 3×422 · 3×409-param · 2×200-param, and ~15 of 18 share ONE cause: no request body the
application will accept.

**Work, in order:**
1. `T4-DB` — field-confirm `MAX_MODEL_FALLBACKS_PER_SWEEP`. It is pinned in tests but has never
   fired in the field; the edge is only reached on a root-closure attempt, and the run after the
   one that died (109 cycles) never got there. **If two runs pass without reaching closure, record
   NO-OPPORTUNITY and stop waiting** — do not read a missing breaker trip as evidence the cap works.
2. `T4-DB` — decide the create-probe question. Either a valid body becomes obtainable (model
   fallback at the edge, now capped) or creates stay unprobed permanently. Say which.
3. `T2-EX` — restate the premise with current numbers. The executable share is **29–78%, mostly
   30–56%**, so half of every run is unverifiable. I wrongly called this dissolved on 2026-08-05;
   `unparsed=0` measures whether a criterion NAMES an endpoint, which is narrower.
4. `T2-FD` / `T2-FE` — accrue field opportunities from S1's runs. Neither shape has occurred since
   shipping. `T2-FE` carries a falsifiable prediction: unwrapped follow-through is **15%** against
   the member block's **94%**, and should rise if scope was the blocker.

**Done when:** the probed share is re-measured after the change, the cap has fired or been declared
no-opportunity, and `T2-EX` states its premise in current numbers.

**Do NOT:** build the bounded-count 422 probe. It is 6.6% of criteria, needs 9 requests against a
cap of 6, and its one comparable precedent (slice 1) made two checks probeable and both got worse.

**Unblocks:** S3 — the probe shapes ARE the domain knowledge, so the seam cut is cheaper once the
verification chain is settled.

---

## S2 — Pay the cleanliness debt

**Goal:** the user raised this three ways (class comments, memory files, drift incidents). It is a
real defect in how this project records itself, and it compounds every session's reading cost.

**First action:** decide `scripts/signal_boundary_report.py` — **0 references**, genuinely orphaned.
Delete it or state why it stays.

**Work, in order:**
1. **Script inventory.** For each of `drift_aggregate`, `next_work`, `session_usage`, `run_profile`,
   `detector_replay`, `outcome_ledger`, `attention_report`, `smoke_strict`,
   `steering_followthrough`, `smoke_chain_report`, `rescore_preserved`: one line saying what calls
   it and when. Retire anything nothing calls.
2. **`project_reliability_plan`** — stale (RECURRING_DEFECT_DOMINANT is gone, the 7 GB worktrees are
   deleted). Rewrite to current state or retire it.
3. **Prose density.** Class comments carry multi-paragraph narrative. Set a rule — the WHY and the
   evidence that bought a constant stay; restatement of what the code plainly does goes — and apply
   it to the files touched most recently (`BootGate`, `DeterministicProbeSynthesizer`,
   `CriterionProbeSynthesizer`, `MissingMemberExtractor`).
4. **Drift incidents** — the user asked for no prose there; make them data.
5. **Confirmed alive, leave alone:** `FrozenCycleObserver`, `FrozenStateFixtures`,
   `SignalPerturbationSweepTest`, `AgentPromptRenderer`, `log_derived_states.json` are all
   `src/test` — a deliberate test-only lab, 8 refs. `SymbolImportCache` is live (9 refs).

**Done when:** no orphaned script, `project_reliability_plan` current or retired, and a stated
comment rule applied to at least the four files above.

**Do NOT:** rewrite every class comment in the codebase in one pass. Apply the rule where the code
is being touched anyway.

---

## S3 — First T4-DD seam cut

**Goal:** one piece of web-service knowledge moves behind a boundary, with a test proving a
non-REST target abstains cleanly instead of guessing.

**First action:** `BehaviouralCriterionParser` (HTTP status codes) — the cheapest of the six sites
the user inventoried on 2026-08-06.

**The six sites, from the user:** `BehaviouralCriterionParser` (status codes) ·
`CriterionProbeSynthesizer` (REST verbs) · `SpecEndpointBehaviourQuery` (status codes) ·
`BootGate.probeDeclaredEndpoints` and its `>=400`/`>=500` classification ·
`GoalPlannerService.coverageItemCriteria` · `BehaviouralSelfCheck`.

**Done when:** one site is behind the seam, the default implementation is the existing REST
behaviour (no outcome change), and a test shows a target with no HTTP surface produces abstention
rather than a wrong answer.

**Do NOT:** design the whole plug-in first. `T4-CU`'s `ClosureClaimVerifier` port is the precedent —
one port, one implementation, proven by use.

---

## S4 — Layer D's first consumer (`T4-CT`)

**Goal:** something in the running loop CONSULTS accumulated cross-run knowledge. Today nothing does.

**Why this and not more plumbing:** layers A–C of wave 2 exist on the assumption that accumulating
experience helps. `T6-G` already closed layer C on measured evidence (1.0% overlap). Layer D is the
point of the wave and is empty.

**Done when:** one consumer reads cross-run knowledge and changes ONE decision, with the decision
named before the code is written.

**Do NOT:** build `T4-CR`/`T4-CN`/`T4-CM`/`T4-CS` first. They are the expensive half and are gated
on this working.

---

## S5 — `T6-I`: does any of it help?

**Goal:** the only experiment that can falsify the accumulating-system thesis.

**Fix in advance, before the first run** ([[feedback-replay-over-batches]]): N, the decision rule,
and the validity precondition. **Learn from the T2-EZ boundary:** its rule said "10 runs carrying
enough of both families", twelve landed and nine qualified, and the run that completed the sample
moved the mean AWAY from the stronger criterion. Also state the power: the per-run smoke delta has
**sd ≈ 31 points**, so a paired design needs the effect to be large or the N to be big.

**Done when:** the verdict is recorded, including the negative case — which would be the most
valuable outcome the wave could produce.

---

## S6 — Second target's yardstick

**Goal:** a second target with its own smoke suite, so generality stops being an assumption.

**Now cheaper than it was:** the harness reports several scores rather than one sacred one
(`smoke`, `smoke_preserved`, `smoke_strict`, `smoke_preserved_strict`), so adding a suite is another
column rather than a re-base event. That was the main objection on record and it is gone.

**Note:** `T4-CK` was filed for exactly this and delivered lineage/graph work instead — the target
and its suite were never built. Check what exists before planning.

**Trigger to bring this forward:** if a block of ten runs averages ≥85% per attempt, the current
suite has stopped discriminating and this becomes the bottleneck.

---

## Needs a ticket before it can be scheduled

- **Dynamic breadth-first vs depth-first planning.** Raised 2026-08-06. `T2-EI` fixed depth-first;
  nothing chooses per situation. File with the measurement that would justify it.
- **`T4-BZ` end-to-end verification**, and `AdaptiveExecutionEngine` ~L190–205 (`T4-BZ-4`) reads
  oddly — file as a correctness review with the specific lines.
- **`smoke_preserved` can UNDER-report** a run that improved past its last snapshot (23/23 final vs
  19/23 preserved, 2026-08-04). The reverse of `T6-A`'s rescue watch, still unfiled.
- **Autonomous dev mode** — currently little used. Decide: invest, or retire the framing.
- **`symbol-imports.json`** — live (9 refs) but the user asks whether a more elegant solution exists;
  relates to `T4-BM`. File as a design question, not a cleanup.

## Standing, every session

- Start a run early; it accrues field DoDs while other work proceeds.
- Never a full `mvn` beside a live run ([[feedback-no-heavy-mvn-beside-a-live-run]]).
- Re-measure before building; three tickets changed shape in 08-04/05 when measured first.
- Every mechanism ships a marker at INFO. A fallback logged at DEBUG left a dying run unable to say
  whether it had fired.
- Assert the path is REACHED, not only bounded — `atMost(5)` passes vacuously at zero calls.

## Deliberately NOT doing

- Bounded-count 422 probe (`T4-DB` slice 2) — 6.6% of criteria, 9 requests against a cap of 6.
- `T4-BQ` member synthesis — flag off on a recorded field A/B; needs the Object-typing fix first.
- A flag-based A/B for the steering blocks — **sd ≈ 31 points** means ~39 runs per arm for a
  20-point effect. Use `steering_followthrough.py` (per firing) instead.
- `T4-DE` (graph regions as identities) — parked; preconditions are S4 and S5.

---

# 2026-08-23 REORDER — appended, and it supersedes the S1-S6 SEQUENCE above

**The S1-S6 sections are kept intact and are still worth reading for their REASONING.** Their ORDER is
stale: it was written 2026-08-06, before the qwen3.8 swap, before wave 2's measurements, and before
`T1-FS`. Nothing above is deleted; this section decides sequence from here.

## What changed underneath the old order

- **`T1-FS` closed 2026-08-23 — and it invalidates the measurement basis.** Every score read was
  unscoped, so one evaluation prompt carried 9,216 scores from 71 runs (~60% of its own context
  window) and 269 file paths of which 259 did not exist. **Every outcome number this project has
  predates the fix.** Fixed, field-proven (block down to 2 paths / 34 tokens), baseline re-measure
  handed to `T6-L`/`T6-M`.
- **Re-measuring is now CHEAP.** `T6-N` slice 1 ships a ~1 MB/run archive outside `/tmp`, and
  `scripts/failure_family.py` classifies failures automatically. The baseline costs RUNS, not build
  effort.
- **Closed/built since 2026-08-06:** `T6-O` (corpus 5→7 from field failures, saturation broken),
  `T3-BY` (mid-run preserve, field-proven `preserved_source=loop-preserved`), `T6-N` slice 1,
  `T1-FS`, active-day retention, two preflight G2 fixes. Filed: `T3-BY`, `T3-BZ`, `T6-M`, `T6-N`,
  `T6-O`, `T1-FS`.

## The order

**0. Bank clean runs — a standing action, not a ticket.** Each run now feeds FOUR tickets at once:
`T6-N`'s corpus (5 of 20 needed), `T6-M`'s failure families, `T6-L`'s baseline, `T3-BY`'s field
evidence. Nothing in the queue has that leverage; the analysis is already built.

**1. `T3-BZ`** — measured cause of **6 of 33 compile failures** in run `2026-08-23_02-12-34`. Rule
shape already shipped (`T3-BW`), calibration borrowed, self-gating settled. Best value-per-effort
in the queue.

**2. `T4-CL`** — the audit of per-run-disposable assumptions. `T1-FS` was ONE instance **found by
accident, because the user read a live prompt**. That is not a search strategy. Jumped hard.

**3. `T6-L` / `T6-M` / `T6-N`** — re-establish the baseline. Build work done; what remains is
periodic analysis over accruing runs.

**4. `T2-FA`** — most-fired resolver path fails 77% and burns the recurrence budget. In-progress,
adjacent to the symbol-import path.

**5. `T4-DB`** — S1's real work; this run already accrues its data (`unprobeable {404,409,422}`).

**6. `T4-DC`** — exceptions caught but never thrown. Seen twice on 2026-08-23: in `T6-M`'s
classification and as a new `T6-O` corpus case.

**7. `T3-BX`** · **8. `T2-EX`** — as argued in S1/S2 above; reasoning unchanged.

**Later, deliberately:** `T4-DD` (S3's seam) · `T4-CT` (S4) · `T5-BJ` · `T4-CY`.

## ⚠ MOVED BACK, with its reason

**`T6-I` was S5 and must NOT start yet.** It measures whether accumulated knowledge improves
outcomes — against a baseline that no longer means anything. Run now, it produces a confident and
worthless number. **Precondition: a post-`T1-FS` baseline exists.** The portability chain
(`T4-CQ`/`CR`/`CS`) stays parked, still gated on `T4-CT`.

## ⚠ Caveat on this ordering

Items 1-3 are weighted heavily by evidence gathered in a single night. That is exactly the recency
bias [[feedback-roadmap-is-the-queue]] and the S6 note warn about. The roadmap remains the queue;
this is order and rationale only. If items 1-3 look inflated on a colder read, they probably are.
