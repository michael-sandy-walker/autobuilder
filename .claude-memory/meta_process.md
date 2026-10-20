---
name: meta-process
description: "YOU+ME development-process tracking — the META axis (how we divide, sequence, and staff the work of building Autobuilder), separate from the product roadmap (T1-T6). 'Model' here = which Claude runs a session (Fable/Opus/Sonnet/Haiku), NOT the autobuilder's local model (qwen3-coder)."
metadata: 
  node_type: memory
  type: project
  originSessionId: d3671a81-d832-4ba2-a9df-4834d3f80226
---

# Meta / Process — YOU+ME work (not the Autobuilder product)

This file tracks the **development process**: how the user and the assistant divide, sequence, and staff the work of building Autobuilder. It is a DIFFERENT axis from the product roadmap — `T1-T6` are about what we build/measure IN the product; `M-` items are about how WE work. Nothing here is autobuilder code, and **"model" here means which Claude runs a session (Fable 5 / Opus / Sonnet / Haiku) — NOT the autobuilder's local model (qwen3-coder)**. The roadmap stays the single product queue; M-items never re-order or replace it — they annotate execution.

Status markers: use the roadmap's status legend (`roadmap.md` — currently `[ ]` open · `[~]` in progress · `[x]` done · `[>]` deferred), not a separate list here.

**Surface (2026-09-08, ORIENT-03):** The `M-` entries below are this axis's history and stay readable as filed. The boundary between this axis and the product queue is recorded once, in the Orientation section of `MEMORY.md`.

## M-A — Refactor roadmap-overview.md back to a thin current-status index

- [x] **M-A: the overview drifted from its own contract into an append-only narrative log — rephrase every single-ticket line to current status only, migrating any not-elsewhere-recorded prose into the roadmap body / resume-state rather than losing it.**
  - **Problem:** `roadmap-overview.md`'s own header states "thin index: one-liner per open ticket… recent-state narrative lives in `project_resume_state.md`, NOT here… roadmap wins on disagreement." In practice dated narrative blocks accumulated (some single lines run to thousands of words), so the overview became a SECOND narrative log that duplicates resume-state and carries frozen "filed"/stale mentions. That is the ambiguity/noise the user flagged: reconstructing current status means scanning "newest-mention-wins" across walls of text — exactly the operation an LLM botches under context load, and it burns tokens anchoring on stale lines.
  - **Not a conflict with [[feedback-append-dont-rewrite-tickets]]:** that rule governs ticket BODIES (the reasoning trail). The overview is the INDEX and is meant to be rewritten to current state. This ticket rewrites the index and PRESERVES the trail by migration, never deletion.
  - **DoD:** every overview line is one thin current-status pointer (ticket id + one-line state + tier); dated narrative removed; any prose not already in the ticket body or resume-state is migrated there FIRST; a no-information-loss check (diff removed prose against what now exists in body/resume-state); the header contract re-asserted; each line's status verified against the roadmap CHECKBOX, not the old narrative.
  - **Work nature:** mostly mechanical per-line rewriting, plus one judgment call (which prose is relevant and where it migrates). Delegating the trivial rewrites to a subagent on occasion is reasonable — but the staffing/model choice is M-B's call, not pre-assigned here. Multi-session OK (user: "in one or more following sessions").
  - **Filed 2026-07-22 (user).** Origin: the T4-CO deferral surfaced that the overview's status can lag the roadmap checkbox; root cause is the index-vs-trail drift above.
  - **DONE 2026-07-22 (Opus 4.8).** `roadmap-overview.md` rebuilt from a 336-line append-only narrative log into a **complete thin index of ALL 401 tickets** (id + short title + verbatim roadmap.md checkbox marker), grouped by tier, live tickets (`[ ]`/`[~]`/`[>]`) sorting before closed within each tier. Contract re-asserted; all dated narrative + closed-chain diagrams + reasoning removed. **Generated programmatically** from every `roadmap.md` ticket header (parser + fallback for odd-punctuation headers like `T2-CE/T2-CF:` and `T4-BN Phase 1…`), so status is read from the checkbox, never prose — this corrected large stale drift (T5-J/T/P/R/I, T3-BO/BQ, T4-AN-5/6/7, T4-AU, T4-J, T4-AL, T4-AR/AT, T2-CW/EC, T3-BU/G, T5-AC etc. were `[x]` but still listed open). Machine-verified: 0 marker mismatches vs roadmap.md, 0 tickets dropped, no dup ids. Census 20 open · 2 wip · 33 deferred · 346 closed. No-information-loss: strategic framing all durable elsewhere (wave-2 → resume-state 2026-07-18; "floor up, ceiling free" → [[project-reliability-plan]]; two-lane "P0 is an ongoing process not a gate" → resume-state:449); git preserves the verbatim old file. Nothing was overview-unique, so "migrate FIRST" reduced to verification.
  - **User steer applied same session (4 corrections):** (1) no chain mention in the contract (redundant); (2) checkbox markers shown as an *example* ("e.g. `[x]` = closed") with roadmap.md authoritative — not restated as a definitive legend/fact; (3) "what's next"/wave-2 lives ONLY in resume-state — the overview keeps just a pointer, no plan; (4) **index ALL tickets, not only open ones** — an index references every body (the first cut listed only the ~55 live tickets; the user asked for all 401).

## M-B — YOU+ME model-usage plan (advisory, capability-vs-cost)

- [x] **M-B: an advisory, cost-aware plan for which Claude runs which work + wave-2 session bundling — annotating the roadmap's order, never replacing it.**
  - **Capability order (user's assessment, snapshot 2026-07-22):** Fable 5 > Opus 4.8 > Sonnet > Haiku. Fable 5 is the most capable, including for the complex/intelligent/autonomous-reasoning part of a task (design, hard diagnosis, architecture). Point-in-time — new model releases can reorder this; re-confirm before relying on it.
  - **But capability is not the only axis — COST is, and as of 2026-07-22 it pulls the other way.** Fable 5 requires far more resources: it bills by CREDITS (API tokens) rather than the paid-account tokens the other models draw on, so it is expensive both monetarily and environmentally. **This is a 2026-07-22 snapshot, not a durable fact** — likely not permanent; re-weight the plan as it changes, and if this reads as stale, re-check the live billing/capability picture before applying it.
  - **So the heuristic is capability-vs-cost and ADVISORY (this is why the user asked for a plan, not a rule):** do NOT default to the most capable model. Prefer the least-expensive model that can do the task well, and RESERVE Fable 5 for the genuinely-hardest intelligent work where its edge is worth the cost. A single ticket usually SPLITS — the intelligent part may merit Fable/Opus, the routine coding part drops to Sonnet/Haiku. Generalizes [[feedback-sonnet-for-batch-tickets]] (mechanical → cheaper model, design/validation → more capable) into a full ladder with cost as a first-class input. Subagents occasionally, not by default.
  - **Revisable from outcomes AND cost:** calibrate from the session-outcome record already in memory (the dated Fable-5 vs Opus-4.8 blocks — what each produced on which task type) together with the current billing/resource picture, not a frozen ranking.
  - **Not a parallel queue ([[feedback-roadmap-is-the-queue]]):** M-B is EXECUTION metadata layered on the roadmap's existing order — HOW to run the queue (which model, batched how), never WHAT'S next. The roadmap ordering stays authoritative.
  - **Deliverable:** the written capability-vs-cost model heuristic + split-within-a-ticket rule; an advisory session-bundling for wave 2 (which slices batch into one autonomous session vs need a human checkpoint between them); tentative model suggestions for the near-term items (wave 2: T4-CN slice 1, T4-CM slice 1 / slice 2 + slice 3) marked "re-decide at pickup."
  - **Filed 2026-07-22 (user).**
  - **DONE 2026-07-22 (Opus 4.8).** Deliverable written as the "**M-B deliverable — the capability-vs-cost model plan**" section below: (1) the four-rung model ladder (Fable 5 / Opus 4.8 / Sonnet / Haiku) with per-rung reservation criteria, each calibrated against the dated session-outcome record in [[project-resume-state]] (what each model actually produced on which task type, Jul 7→22); (2) the cost-first default + split-within-a-ticket rule; (3) an advisory wave-2 session bundling with the two natural human checkpoints; (4) per-item model suggestions for T4-CN slice 1 and T4-CM slice 1 / slice 2 + slice 3, each stamped "re-decide at pickup." Stays advisory execution-metadata, never a parallel queue ([[feedback-roadmap-is-the-queue]]). Cost snapshot honored as filed-same-day (fresh, not stale); re-confirm capability order + billing before relying on it.

## M-B deliverable — the capability-vs-cost model plan

**Advisory, not a rule; annotates the roadmap's order, never sets it ([[feedback-roadmap-is-the-queue]]).** The roadmap decides WHAT is next; this decides HOW to staff and batch it. Everything below is a 2026-07-22 snapshot — re-confirm the capability order and the billing/cost picture at pickup before relying on it ([[feedback-capability-gap-not-prohibition]]: this is a revisable read, not a stored permanent fact).

> **⚠ SNAPSHOT AGE CHECKED 2026-09-08 — 48 DAYS OLD, AND KNOWN INCOMPLETE. NOT RE-CONFIRMED.**
> The ladder names Fable 5 / Opus 4.8 / Sonnet / Haiku. **Opus 5 is not on it**, and the session
> recording this line was itself running Opus 5 — so the ranking cannot be applied as written to
> the model actually doing the work. The rung ordering and the credits-vs-tokens cost picture are
> the **user's own assessment**, so a session cannot re-confirm them by inspection; only the user
> can restate the order. What is recorded here is the audit, not a renewal: **treat the four-rung
> ordering as unverified until the user restates it, and do not read Opus 5 into a rung by
> analogy.** The cost-first default below is unaffected — start at the cheapest rung that can
> plausibly clear the task — because that rule does not depend on which models occupy which rung.

### The cost-first default (the one rule the ladder serves)

**Do not default to the most capable model.** Start at the *cheapest rung that can plausibly clear the task*, and escalate only when the work itself reveals it needs more. Reserve Fable 5 for where its edge is *demonstrably* worth the credit cost — not for anything that merely *looks* hard. Cost (monetary + environmental; Fable 5 bills CREDITS, the others draw paid-account tokens) is a first-class input alongside capability, not a tiebreaker.

### The four-rung ladder (capability high→low, cost high→low)

Calibrated against what each model *actually produced* in the dated [[project-resume-state]] blocks (Jul 7→22), not the bare ranking:

- **Fable 5 — reserve for the genuinely-hardest intelligent work.** Novel architecture / decomposition, opening a new phase or tier, multi-factor diagnosis where *localizing the mechanism* (not just naming the symptom) is the deliverable, and rigorous frozen-protocol methodology design. *Track record:* T4-BZ signal re-architecture, T4-J audit + perturbation lab, T4-AI meta-lane deletion, the whole Phase-I internalization design (T4-CD/CE/CF/CG), the 5-model frozen-gate experiment, T4-CI health-baseline reframe, the wave-2 + T6-tier design. *Anti-pattern seen:* Fable also ran project-wide vocabulary renames — mechanical work that should have dropped ≥2 rungs. Do not let the expensive rung absorb routine coding just because it happened to be in-session.
- **Opus 4.8 — the default workhorse for hard-but-bounded work.** Field diagnosis + root-cause tracing over a corpus, correctness-edge bug fixes, implementing a slice from a written design, live-write-path wiring where correctness matters, verification / status review, and the M-process items themselves. Pick this whenever the intelligent core is real but *scoped* and Fable's marginal edge isn't clearly worth the credits. *Track record:* the post-wave-1 stability root-cause trace, Phase-G 5-ticket batch-ship, T1-FL fix, T2-EH/T4-I implementation, M-A, this M-B.
- **Sonnet — well-specified implementation and batch/mechanical work.** Coding a slice from a design someone else wrote, batch/mechanical tickets, A/B run-driving + tallying, string/vocab/caller-migration sweeps. Best consumed as a subagent (Agent-tool model override) so the design-owner keeps the judgment ([[feedback-sonnet-for-batch-tickets]]). *Track record:* T5-AG mechanical sweep (delegated as a Sonnet subagent per user steer), the meta-lane A/B run batches, T4-BO implementation.
- **Haiku — truly trivial, correctness-by-construction only.** Exact-spec single-file renames, checkbox flips, index regeneration from a template — work whose output is verifiable mechanically. No autobuilder track record yet, so keep on a short leash and verify the output; do not hand it anything whose correctness needs judgment.

### The split-within-a-ticket rule (generalizes [[feedback-sonnet-for-batch-tickets]])

A ticket is *not one difficulty.* Decompose it and staff each part at the cheapest rung that clears it:

1. **Design pass / hard diagnosis** → Fable 5 (only if genuinely hard) or Opus 4.8.
2. **Well-scoped implementation from a written design** → Sonnet subagent, or Opus inline when the design and code are cheaper to keep in one head.
3. **Mechanical wiring / sweeps / caller migration / renames** → Sonnet or Haiku subagent.
4. **Validation, replay grading, and the decision-to-close** → **always stays on the rung that owns the design** — never delegated below the level that can judge correctness ([[feedback-verify-diff-is-substantive]], [[feedback-ticket-definition-of-done]]). This is the guardrail that keeps cost-cutting from eroding quality: cheap models *produce*, the design-owner *judges*.

### Advisory session-bundling for wave 2

Wave 2 is a strict dependency chain (feedback layer → read surface → guarded write → self-model observation), each step independently closeable per its own DoD, and — importantly — **validated by unit tests + graph fixtures + deterministic replay, not live runs** ([[feedback-replay-over-batches]]), so the old "no `mvn` while a live JVM holds the port" constraint that shaped earlier sessions barely binds here. That leaves two *natural* human checkpoints, and they are the bundling seams:

- **Checkpoint A — after the T4-CN connection-kind table is designed, before it's implemented.** The table (what a goal↔goal merge *means*: status rules, lineage/ownership, similarity ceiling, T1-DD integrity pre-checks, and the honest `consequences` semantics) is the one genuine modeling judgment in the whole chain. Surface it for user eyes before building on it.
- **Checkpoint B — before the T4-CM slice 2 + slice 3 guarded connect goes live.** This is the first UI-triggered *write* into the loop's own graph. Even though it routes exclusively through `GoalConsolidationService` under `runExclusively` (never a second write path, by design), a human should eye the connect-gesture wiring before it's live — it's outward-facing and hard-to-reverse in exactly the sense that warrants confirmation.

Resulting bundles (advisory):

- **Session 1 (one autonomous session):** T4-CN slice 1 *table design* → **Checkpoint A** → T4-CN slice 1 *implementation* (`checkConnection` + headless REST endpoint + unit tests/fixtures) **+** T4-CM slice 1 explorer (read-only; zero write risk; testable headlessly on `graph.html` + the dump format). These bundle because both are no-write-risk and the explorer is the natural fill-in whenever anything else blocks. The table-design step can even run in an earlier session since it needs no build access.
- **Checkpoint B** between the sessions.
- **Session 2 (one autonomous session):** T4-CM slice 2 + slice 3 guarded connect end-to-end **+** Step 4 (`checkConnection` wired recorded-not-applied at the loop's own consolidation decision point). Coupled: both are about the connect path, and Step 4 is low-blast-radius (recorded-not-applied, T4-CC principle) so it rides safely behind the guarded gesture.

### Per-item model suggestions (all "re-decide at pickup")

- **T4-CN slice 1 — connection-kind table (design):** **Opus 4.8.** Real modeling over the loop's own data model, but *bounded* — it maps existing mechanisms (GoalSimilarityService, T4-CJ stamps, T1-FM ownership, T2-DR reopen semantics, T1-DD integrity), it doesn't invent them. Escalate to Fable 5 only if the merge semantics turn out genuinely thorny at pickup.
- **T4-CN slice 1 — `checkConnection` + REST + tests (implementation):** **Sonnet subagent** from the written table, or Opus inline. Design-owner reviews.
- **T4-CM slice 1 explorer:** **Sonnet** (candidate subagent). Read-only UI on existing scaffolding — well-scoped, cheap, low-risk.
- **T4-CM slice 2 + slice 3 guarded connect:** **Opus 4.8, not delegated unbabysat.** This is the risk-bearing slice (live-graph write, `runExclusively` correctness) — the correctness edge is exactly where Opus's record is strongest and where a cheaper unsupervised model is the wrong economy.
- **Step 4 — recorded-not-applied consolidation observation:** **Opus 4.8.** Design-sensitive placement (it's where CN becomes part of the loop's self-model — [[project-introspection-as-north-star]]), but recorded-not-applied keeps the blast radius small.

Net: wave 2 needs **no Fable 5 session** on current reads — its intelligent cores are bounded enough for Opus, with Sonnet taking the read-only/well-specified implementation. That is the cost-first default working as intended: reserve the credits, and only reach for them if a pickup surfaces a genuinely-hardest problem.

## M-C — Mechanism for more autonomous continuation (work selection + the development cycle)

- [x] **M-C: a session can validate autonomously (`AUTONOMOUS_TEST_LOOP.md`) but cannot yet PICK work autonomously — build the selection + bookkeeping mechanism, and make orientation cheap enough that the budget goes to the work.**
  - **Filed + DONE 2026-07-25 (Opus 5), user request:** "work on a mechanism which lets you continue the work on Autobuilder even more autonomously… maybe also by looking at the token limits."
  - **The gap, precisely:** `scripts/AUTONOMOUS_TEST_LOOP.md` drives run validation and stops at code changes by design ("the driver does not write code"). Everything upstream of that — read resume-state, derive what is next, check the ticket's gate, build, append the closure, update the index + census, write the note, commit — was carried in-session by judgment each time. Two failure modes had already been paid for: (a) **orientation cost** — `roadmap.md` is >5000 lines and reading it to find the next item spends the budget the work needs; (b) **stale plans** — the 2026-07-24 wave-2 plan named "T4-CE slice 2 → T4-E" as its fallback spine while T4-CE had already closed NO-BUILD, so a session had to re-derive the spine by hand.
  - **Built:** (1) `scripts/next_work.py` — reads `roadmap.md` as a queue and emits a compact digest: census from the headers · **unflipped-closure warnings** (live checkbox over a body claiming the ticket finished — the T6-A drift, caught the same day) · **dead dependencies** (a live ticket whose precondition is deferred, or closed WITHOUT being built — it reproduces the T4-E → T4-CE trap independently) · the live queue with each ticket's **ranked gate lines**. `--id` / `--tier` / `--warnings-only` / `--json`; never throws, exit 0. (2) `scripts/test_next_work.py` — 23 stdlib cases. (3) `scripts/AUTONOMOUS_DEV_LOOP.md` — the development-cycle runbook: cheap orientation order, the measurement-first rule (a negative gate result is a deliverable, `[>]` not a failed session), build discipline, the 5-step closure checklist, explicit stop-and-ask conditions, and a token-discipline section where every line is a measured cost.
  - **Precision was the hard part, and it is pinned by tests.** The first warning cut produced **44 hits on the real roadmap, nearly all false** — bodies legitimately say "not built this scope", cite other tickets that shipped, and ship one slice/phase under an open umbrella. Tightened to 1 reviewable hit (T2-EO) and 6 real dead deps by: requiring a closure claim to LEAD its line, excluding slice/phase/half/part/step qualifiers, and — the key insight — **treating a closed-AND-BUILT precondition as satisfied rather than dead**, so only `blocked-on-deferred` and `closed-without-building` are reported. The opposite drift direction was dropped entirely: a ticket closed won't-do on measured evidence is correctly `[x]` (T3-G, T4-CE, T4-CP all read that way), so flagging it was never right.
  - **On token limits (the user's second half), answered honestly:** the assistant cannot read the account's usage/plan limits, so this does not budget against them. What it does is cut the *controllable* cost — orientation from a digest plus one resume-state block instead of whole files; corpora summarised by script rather than read into context (behaviour files run 100 KB–1.7 MB each); dedupe before measuring (4132 file copies → 648 distinct runs); replay over live runs; one surface per commit so regressions bisect. Long sessions are handled by context compaction, so the practical ceiling is the account's usage window, not the context window.
  - **Deliberately NOT built (needs user authorization, [[feedback-no-auto-commit]] shape):** unattended TRIGGERING — a `/loop` self-paced session or a `/schedule` cron routine that starts sessions with no one watching. That is outward-facing and hard to reverse, so it stays a user decision; the mechanism above is what makes such a trigger safe to point at something, and the runbook's stop-and-ask list is what it would obey.

## M-D — Closure-kind retrofit: make the roadmap stop overstating what exists

- [x] **M-D: `[x]` meant five different things, so the roadmap read as a capability inventory it is not. Extend the status marker vocabulary and audit every closed ticket.**
  - **Filed + DONE 2026-07-25 (Opus 5 + 11 delegated Sonnet batches), user direction.** Origin: the user observed that "some of the 'done' tickets are pretty ambitious, but were implemented very superficially." Investigating confirmed something sharper — for a minority of tickets **nothing was implemented at all**, and the index could not show it.
  - **The trigger was a live failure of my own.** Asked which tickets cover the introspection north-star, I read the `[x]` markers as delivered capability and reported that the loop already had "signals derived from observed behaviour" (`T4-C`) and "persistent state across runs" (`T4-J`). Neither exists: `T4-C` was closed as OBSOLETE because every mechanism it named had been deleted, and `T4-J`'s filed `TuningParameterStore` was never built (an audit shipped instead). The roadmap — the project's own self-model — overstated its capability, which is the north-star problem one level up ([[project-introspection-as-north-star]]).
  - **User's design decision (rejecting my proposal):** I suggested adding a separate "closure kind" field to each closed ticket. The user rejected that and said to **extend the status vocabulary itself**, then regenerate the other lists from it — explicitly invoking the rule against parallel status lists, which exists for exactly this reason ([[feedback-roadmap-is-the-queue]]). Their way keeps one source of truth AND made the tooling simpler: the dead-dependency check now reads a marker instead of a prose heuristic that could never have worked.
  - **Vocabulary (defined in `roadmap.md` "Status markers", authoritative):** `[x]` built · `[n]` declined on measured evidence · `[o]` obsoleted · `[s]` subsumed · `[r]` retracted. `[ ]`/`[~]`/`[>]` unchanged. `[n]/[o]/[r]` = no capability delivered; `[s]` = capability exists under another id (so a dependency on `[s]` is satisfied, on `[n]/[o]/[r]` is not).
  - **Result over all 360 closed tickets: 336 built · 9 subsumed · 8 declined · 6 obsoleted · 1 retracted — 93% built, 15 with nothing delivered.** The unbuilt share concentrates in tier 4 ("new systems"), exactly where ambition lives; tier 1 correctness is 125/125 built. Declining on measured evidence is a good outcome and now stays visible as one instead of being disguised as delivery.
  - **Method (why it needed judgment, not regex):** FOUR of my own pattern-matching attempts produced unusable classifications, because closure vocabulary collides with the product's own domain terms — goals legitimately transition to `SUBSUMED`, mutations get `REVERTED`, and a ticket's body may retract a *finding* while the ticket's work shipped (`T4-AI`: stays `built`). Delegated to 11 Sonnet subagent batches ([[feedback-sonnet-for-batch-tickets]]) against a spec carrying the judgment rules + that worked example; agents reported TSVs only (no concurrent edits), every non-`built` call was re-verified by me against the ticket body, and weak-evidence `built` calls were confirmed by codebase presence (`BuildSystemBootstrap`, `BehaviourLogger`, `ScopeAssessment`, … all present).
  - **Agent behaviour worth recording:** when a path bug left the batch lists missing, agents **refused to proceed and asked** rather than guessing which tickets were theirs; several flagged borderline cases (`T1-FJ`, `T2-DZ`, `T2-DN`, `T3-BT`) for review instead of deciding silently. That is what made delegation safe here. One reclassification on review: `T2-DG` declined→obsoleted (its premise was removed by `T1-EC`, not judged not-worth-building).
  - **Known residue (not defects of this work):** 4 ids are headed twice in `roadmap.md` (`T2-CU`, `T2-DD`, `T2-DV`, `T3-BT`) — pre-existing, both headers audited, left as-is. `scripts/next_work.py` keeps a prose fallback for any ticket not yet carrying a kind, so future closures degrade gracefully.

- [x] **M-E: the bookkeeping check is one-directional, so a `[x]` ticket that admits its own unfinished half is invisible to the queue forever** (`scripts/next_work.py:find_warnings`, roadmap markers) — **filed 2026-08-29, found by appending a live finding to a closed ticket and noticing it would never resurface.**
  - **THE CERTAIN PART — a documented asymmetry, quoted from the checker's own docstring:** *"Only this direction is reported. The reverse ('[x] but the body declines the build') is not an error at all — a ticket closed won't-do on measured evidence is correctly `[x]`."* That reasoning is right about the case it names and **wrong about a second case it silently folds in with it**. Two different things live in the reverse direction:
    - `[x]` + the body **declines to build** (`T3-G`, `T4-CE`, `T4-CP`) — legitimately closed. **Correctly not a warning.**
    - `[x]` + the body **asserts its own remaining work** (*"OPEN HALF"*, *"still open"*) — **not legitimate.** The residual is real, nothing points at it, and `next_work.py` cannot see it because the ticket is closed. It was dismissed along with the first case.
  - **CONFIRMED INSTANCE (the one that exposed it): `T4-DK`.** Marked `[x]`, its own body says *"THE OPEN HALF"*, and it does not appear in the live queue. A code-confirmed `clitarget` finding was appended to it on 2026-08-29 and would have sat dormant. **Flipped to `[~]` the same day** — that is the immediate case, not the fix.
  - **⚠ THE POPULATION IS A CANDIDATE SET, NOT A COUNT — do not quote "17".** A text scan for *open half / still open / remains open* inside `[x]` bodies returns 17 tickets, but it is **the same mentions-versus-effect error this project keeps making** (third instance in one session, after `T4-DD` clause 4 and the `T4-DK` coverage reading). Sampling shows at least two kinds mixed together: `T2-EX` (*"Still open, and no steering change may precede it"*) is a genuine own-residual, while `T1-FS` (*"`T4-CL` … is still open"*) refers to a DIFFERENT ticket's openness and is not a defect at all. **The audit must read each body, not grep it.**
  - **Why this matters more than any one ticket:** the checker is the mechanism that decides what gets worked next, so a blind spot in it silently removes work from the queue. It is the [[feedback-delete-debt-pattern]] failure with a tooling cause rather than a human one — scaffolding accrues because nothing surfaces it.
  - **DoD:** `find_warnings` distinguishes the two reverse cases and warns only on the second; the candidate set is audited body-by-body with each ticket either re-opened or its prose corrected; the count that ends up quoted is the audited one, never the grep; and `T4-DK`'s appended finding is reachable from the live queue.
  - **Tier note:** `M-` — this is the development process (how YOU+ME pick work), not a product capability. Sits beside [[M-C]], which built the selection mechanism this extends.
  - **CLOSED 2026-09-08 (Phase 2 plan 02-02) — all four DoD clauses met on the `.planning/` surface; see `.planning/phases/01-queue-integrity/`.** (1) `find_warnings()` gained the reverse `closed-with-residual` direction, gated by `claims_own_residual(line)` AND `not declines_build(body)` — distinguishing a body that asserts its own remaining work from one that declines the build (Phase 1 plan 01-01). (2) The candidate set was audited body-by-body in `.planning/phases/01-queue-integrity/01-AUDIT.md`: population read 17 (all five closed kinds) — 2 REOPEN (`T4-CK`, `T5-BD`), 7 CORRECT, 8 NO EDIT, plus `T2-FH` ACKNOWLEDGE outside this candidate set (Phase 1 plan 01-02). (3) The audited disposition counts are what gets quoted from here on, never the text-scan number — the scan itself returns 17 over all five closed kinds, or 16 over the `[x]`-only population (one fewer, since `T4-DK` had already left that population the same day it was filed); `01-AUDIT.md`'s own scan-header records both readings and why they differ. (4) `T4-DK`'s appended finding is reachable from the live queue, demonstrated in `.planning/phases/01-queue-integrity/01-03-SUMMARY.md`'s QUEUE-03 check. **Repeating the warning this ticket itself carries: the population figure is a candidate set, not a count — quote the audited dispositions above, never a bare scan total.**

## M-E — The port review stage: loop-built ticket code reaches `src/` only on the operator's approval

**Filed 2026-09-27 on the user's decisions** ("the review stage should be done by me ... which then needs
approval, followed by the decision if the porting goes through or not"), and **moved here from T4-CF the same
day** on the user's word: "the meta tasks are directly implemented as code ... it's mixed together". The review
stage is how the operator and the session work, not what Autobuilder does, so its record lives on this axis and
its tools in `process/`, apart from the product's `scripts/` and `src/`.

- **Flow:** the run's artifact is archived durably (a product concern: `preflight.sh`'s run archive) -> a blind
  reference (`process/blind_reference_workspace.sh`) -> a measured packet (`process/port_review.py` ->
  `process/ports/<target>/REVIEW.md`) -> an independent assessment (a fresh agent) -> a page with a Decision list ->
  the operator decides -> only then a commit to `src/`.
- **State per slice:** the `LOOP-BUILT: ... -- PORT: <state>` tag on the product ticket the code belongs to
  (the port changes that ticket's code); `scripts/next_work.py` lists the open ones.
- **Decided so far:** receiver-questions -- the proposed port approved and ported (`e4c92306`). criterion-status and
  stuck-questions -- retro packets published; superseded by M-F (treated as ordinary code).
- **2026-09-27, OFF THE CRITICAL PATH (M-F):** loop solutions are no longer ported by default.
- **Known limit, raised by the user 2026-09-27:** the "blind" reference is a subagent of the session; it very likely
  receives the project's CLAUDE.md and memory index as context, so it is instructed-blind, not information-equal.
  The user's reading of the gap (option b): the fault is on the loop's side -- the LOOP is information-starved
  compared with any developer; that is a product change, filed on T4-CF, not here.

## M-F — Roadmap tickets are developed directly; the loop's own capability is tested separately, on versioned toy cases

**Decided by the user 2026-09-27:** "I don't think we should mix our roadmap work of Autobuilder with the work
Autobuilder is capable of doing itself on its own further for now. We want to test that capability as toy cases
while we continue development of the tickets separately. Otherwise we would bottleneck the capabilities of the
overall development too much regarding the capabilities of the local model."

- **Tickets:** the session implements roadmap tickets directly (suite, field checks as before). No loop run is on a
  ticket's critical path.
- **Toy cases:** a ticket may still become a toy case -- as a CAPABILITY TEST of the loop. Its output is measured, not
  ported. The port review stage (M-E) leaves the critical path; it applies only when a loop solution is clearly
  better than ours and someone proposes porting it.
- **Reference:** a toy case frozen at the commit before a ticket was implemented can be compared with OUR
  implementation of that ticket -- a reference written with full context (answers the "instructed-blind" limit of M-E).
- **Versioned toy cases (the user, same day):** "That's why we version the toy cases. If there is more up-to-date code,
  we could update the toy cases and version themselves. So we also have a history what changed regarding the toy cases
  over time." Each toy case carries a version and a changelog; every run records the version it faced.
- **The two open retro reviews** (criterion-status, stuck-questions): treated as ordinary code in `src/`; the
  stuck-questions assessor's fixes are ordinary ticket work (T4-DY), criterion-status closes as reviewed.

## M-G -- Archive old resume-state blocks: orientation reads the newest few, the file holds them all

- [ ] **M-G: `project_resume_state.md` keeps every session's block since early July -- 2,438 lines, 0.94 MB,
  179 `##` blocks on 2026-10-20 -- while a session reads only the newest one or two.**
  - **Filed 2026-10-20** from the progress review of that day (recommendation "archive the resume state"),
    placed on this axis rather than the roadmap because it is about how a session orients, not about the product
    ([[feedback-process-apart-from-product]]).
  - **The move:** keep the newest three dated blocks live; move the rest, in order and unedited, to a dated
    archive file that nothing loads by default; the live file says where the archive is. The trailing undated
    sections (`Next lever`, `Tools added this session`, `CRITICAL infra gotcha`) are read once and either kept
    or folded into the newest block.
  - **Check first:** `scripts/orientation_check.py` and its tests name `project_resume_state.md`; run them after
    the move. Memory files and the roadmap link into the resume state by date; an archived block keeps its
    heading text so those references still resolve by search.
  - **DoD:** the live file holds three dated blocks; the archive holds the rest byte-for-byte;
    `orientation_check.py` is quiet; the MEMORY.md index line names the archive.
  - **Related, same day:** `scripts/next_work.py` lost the prose fallback M-D's residue note describes ("keeps a
    prose fallback for any ticket not yet carrying a kind") from its dead-dependency check -- by 2026-10-20 it
    misread 11 built tickets, and every warning it alone raised was false. Its other use (skipping the
    residual warning on a body that declines its build) stays.
