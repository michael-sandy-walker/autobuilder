---
name: project-completion-binding-constraint
description: "2026-06-22 investigation: the binding constraint on GOAL_FULFILLED is keeping a large interdependent (main+test) build compiling, NOT naming or vague 'model capability'. build red 60-83% of cycles, dominated by cross-file structural errors. REFINED same day by the test-compile-coupling A/B: the '0/7 never fulfills' claim was superseded — a run fulfilled by writing ZERO tests (test gate passes vacuously). The test requirement is neither reliably achievable nor enforced. See update block at end."
metadata:
  node_type: memory
  type: project
  originSessionId: 609aab02-3d49-46ec-874f-e42f8a4aa75f
---

**Question:** what actually prevents runs from reaching GOAL_FULFILLED? Investigated 2026-06-22 over the 6 backed-up T4-BQ A/B runs (`/tmp/t4bq-naming-block-ab/pair{1,2,3}/{block-enabled,baseline}/`) + the 4356-file log corpus. Belief→verification, not assertion.

**Headline finding (evidence-backed):** the binding constraint is **keeping a large, tightly-coupled (main + test) build compiling long enough to reach the completion edge.** It is NOT naming/synonyms, and "local-model last-mile capability" is too vague — the specific failure is **global cross-file structural coherence across a big working set**.

**The discriminator — the test-authoring requirement:**
- Corpus cross-tab: **0 of 7** runs whose seed required working tests ("provide JUnit 5 tests for every endpoint / MockMvc tests for each controller") ever fulfilled. Fulfillment is common WITHOUT that requirement (40+ distinct non-test-seed runs fulfilled).
- The recent `2026-06-17_19-03` run FULFILLED on the SAME framework with a non-test seed (and zero tests → test gate abstained). So the framework still completes; adding the test requirement is what stops it.

**The mechanism, quantified (aggregate over the 6 test-seed runs):**
- Build is COMPILE_ERROR most of the time — OK-ratio 0.17–0.37 per run.
- Dominant errors: `cannot find symbol` 2253, `package com.example.* does not exist` 443 (referencing project packages not yet created / wrong place), `constructor/method cannot be applied` 343 (wrong arity/signature). Smaller but test-specific: missing framework/test deps `package javax.validation|org.mockito|org.hamcrest|org.springframework.test ... does not exist` 140 (pom lacks test deps; javax-vs-jakarta).
- Error locus: **src/main 3732 vs src/test 721** (~5:1) — main code breaks more than the tests do; requiring tests roughly doubles the interdependent working set and knocks main around too.
- Downstream: planner backlog saturates at its 18-active cap (`PLANNING_THROTTLED` / "backlog cap reached" 364 events) because while COMPILE_ERROR the planner may only append build-fix subgoals — it fills with unresolved build-fixes and throttles.
- **Completion edge never reached: 0/6 rootCOMPLETED; package/test gates effectively never ran.** Runs halt (T4-I convergence brake / GIVEN_UP) far short of the edge.

**Interpretation:** this is "divide-and-conquer WITHOUT a linkage layer" — each cycle is a local edit that is locally plausible but doesn't resolve against the rest of the tree (wrong package, missing member, wrong constructor arity, missing dep). With test-compile coupling the tree is ~2× larger and more cross-referential, so coherence collapses faster than the loop repairs it. It IS a model limitation, but a *specific, addressable* one (global symbol map + build-config), not "the model isn't smart enough." Matches [[project-introspection-as-north-star]] (information axis: route the global symbol/dependency map to each local step).

**Candidate levers — HYPOTHESES to test, NOT asserted fixes** ([[feedback-no-defensive-recast]]: don't name "the lever" off one investigation):
1. Full T4-BQ symbol-model closure (usage-derived member synthesis + dependency-ordered creation + missing-type closure) directly targets the dominant error class (`cannot find symbol` / ctor-mismatch / project-package-missing). Slice-1 member synthesis alone was inert; the closure/ordering may be the part that matters. Untested at scale.
2. Build-config management (ensure test deps in the pom; resolve javax-vs-jakarta) — addresses the 140 dep-missing class, test-specific. **User constraint (2026-06-22): do NOT add fixed javax→jakarta mappings; let the system discover the right coordinate dynamically (project pom → classpath → Maven Central), with a fixed mapping only as a last-resort fallback** ([[feedback-no-hardcoded-ecosystem-data]]). Existing debt to fix in that direction: `WellKnownPackageRegistry` (lines ~94-95) already hardcodes `javax.validation→jakarta.validation` + `javax.persistence→jakarta.persistence` (semi-dynamic on the SB version, but the pairs are frozen) — a frozen-worldview table, the exact pattern the rule warns about. Untested whether dynamic discovery is even the bottleneck vs the structural class (1) which is far larger (583 dep/pkg-missing combined vs 2253 cannot-find-symbol + 343 ctor).
3. Process question, not a build: did `mvn test-compile` probe (T4-BN-2) + the test-run gate (T4-AN-2) raise the bar past current capability for a SINGLE autonomous loop? Maybe test authoring should be a scoped phase after main reaches COMPILE_OK, not coupled every cycle. Untested.

**What is NOT established:** which (if any) lever moves the outcome; whether main-code incoherence would persist even without the test requirement at higher complexity. Next verification if pursued: an A/B on the test-compile coupling (probe = `mvn compile` vs `mvn test-compile`) on the test seed, measuring whether main reaches a stable COMPILE_OK and the edge is reached.

**Corrects:** the roadmap's frequent "remaining variance is local-model last-mile capability" shorthand — true but unspecific; the measurable constraint is build-greenness of a large interdependent tree, gated hardest by the test-authoring workload.

---

## UPDATE 2026-06-22 — test-compile-coupling A/B: inconclusive on its question, but corrects the headline

Ran the proposed A/B (probe `mvn test-compile` [coupled] vs `mvn compile` [decoupled], test-authoring seed held constant, 2 counterbalanced pairs, single jar, reset-on-start verified firing — `SystemResetConfig` purges goal graph + wipes output/mutations/snapshots/meta each run, so all 4 were clean starts). Backups `/tmp/test-compile-coupling-ab/`.

| run | config | terminal | cyc | OK-ratio | test files | note |
|---|---|---|---|---|---|---|
| pairA | coupled | paused | 36 | 0.48 | wrote tests | thrashed |
| pairA | decoupled | paused | 20 | 0.86 | few (8 files, barely restructured) | high ratio partly = built little |
| pairB | decoupled | GIVEN_UP | 4 | 0.00 | test file in src/main | collapsed fast |
| pairB | coupled | **GOAL_FULFILLED** | 4 | 0.83 | **0** | real main code, no tests |

**Inconclusive on the coupling question.** The dominant variance is **whether the model engages with tests at all** (stochastic per run), which swamps the probe-coupling variable: same config + same seed produced both a 36-cycle thrash (pairA/coupled, wrote tests) and a 4-cycle fulfillment (pairB/coupled, skipped tests). Run-to-run variance 4–36 cycles. More runs of this design would measure the confound, not the question.

**The real finding (well-evidenced) — the test requirement is neither achievable nor enforced.** The system faces a fork: (a) attempt tests → cross-file coherence collapses, build stays red, never reaches the edge; or (b) skip tests → `mvn test` gate returns COMPILE_OK *vacuously* (no tests to run), criteria verify against real main code, root COMPLETED. pairB/coupled took (b): 0 test files, genuine main artifact (ISBN `\d{13}` `@Pattern`, max-3-loans `>=3` both present), fulfilled in 4 cycles. **The seed's explicit "provide JUnit/MockMvc tests" requirement is silently unenforced when the model writes none.** Not a hollow false-completion (main is real) — an *unenforced-requirement* gap.

**Corrects the earlier claim** "0/7 → test authoring is the wall": completion CAN occur on the test seed, by skipping tests. So the constraint isn't "can't ever finish with tests"; it's that working tests are a fork the system neither reliably clears nor is forced to clear.

**Candidate next steps — HYPOTHESES, not asserted ([[feedback-no-defensive-recast]]):** (1) the test gate should not pass vacuously when the seed requires tests and none exist (enforce test *presence*, not just `mvn test` exit code) — addresses fork (b); but enforcing it would just push runs onto fork (a) where they currently collapse, so (1) alone likely converts false-passes into honest non-completions, not into fulfillments. (2) A clean coupling A/B would need to control test-engagement (e.g., a seed phase that mandates tests only after main COMPILE_OK), which is itself the "scope test-authoring as a later phase" idea. (3) Whether decoupling helps is still genuinely unknown. Not building anything without a steer.

**SHIPPED 2026-06-22 — step (1), the unenforced-test-requirement guard (T4-AN-2b, suite 1639/1639).** `TestRunGate.verifyTestsPass(cycleId, testsRequired)` overload: when `testsRequired` and no test sources exist under `<base>/output/**/src/test/**/*.java`, it returns a completion blocker instead of the vacuous `mvn test` pass — decided on disk, before the suite even runs. Requirement read from the goal spec via static `specRequiresTests(spec)` = generic `\btests?\b` (NO framework names — [[feedback-no-ecosystem-jargon-in-framework-code]]); wired at the `GoalPlannerService` closure edge from `root.getDescription()`. Fail-safe: scan error → treat as present (never false-block a clean completion). 4 gate tests + 1 closure-stub fix. Validated against this session's artifacts: pairB/coupled (0 test files) now BLOCKS; pairA/coupled (6 test files) proceeds to the suite. **As predicted, this is a correctness/honesty fix, not a capability gain** — it converts the false "fulfilled with 0 tests" into an honest non-completion that demands the tests; it does NOT make test-required runs more likely to finish (that is still the fork-(a) coherence wall). Steps (2)/(3) remain open and unbuilt.

---

## UPDATE 2026-06-22 (later) — prompt-salience lever found + shipped (n=1, A/B pending)

User hypothesis: the diagnostic info isn't absent, it's DROWNED — too much prompt, so the dedicated signal loses weight. Measured on a real evolution prompt (naming-experiment backup): total ~4,770 tok / 510 lines, and **one block — the verbatim echo of the previous cycle's raw response — was ~3,056 tok / 64%**, vs the naming diagnostic at ~123 tok / 2.6% (drowned 25:1). The echo is largely redundant (current state is already in Structure Summary + Diff; repetition is caught by dedicated signals). Notable supporting differential: feeding structural facts to the local model HELPED in the focused completion-verifier prompt (T4-BS, recall 0.25→0.50) but was IGNORED in the bloated evolution prompt (naming block) — same intervention, opposite outcome, consistent with salience/dilution (confound: judge-vs-generate task difference).

SHIPPED (`97fe5cc`): cap the previous-raw-response echo to the leading 40 lines + elision note; importance-ranked (only that block capped, all diagnostics/structure stay full); flag `autobuilder.evolution.prompt-compaction.enabled` (code false / yml true). 3 tests, suite 1642.

**n=1 field run (75-min deadline-cut, test seed, NOT fulfilled):** compaction fired 41×; merge rate 0.36 + OK-ratio 0.39 sit at the TOP of the prior no-compaction range (0.13–0.27 / 0.17–0.37) but WITHIN its spread; 0 invented exceptions; still merging at deadline. **Encouraging, NOT proof — classic n=1 trap ([[feedback-verify-population-before-claim]]).** Validation = controlled on/off A/B, same seed, counterbalanced ≥2 pairs (the rigor the naming A/B established; pair-1-alone overclaimed). Until then: shipped behind a flag, benefit unclaimed. This is the most promising lever found because, unlike per-signal fixes, it would lift EVERY diagnostic block's weight at once.

---

## UPDATE 2026-06-22 (eval) — prompt-compaction lifts diagnostic adherence 0.08→0.50 (proximal, controlled)

Replaced the slow/noisy 75-min loop A/B with a direct single-call adherence eval (the drastic reframe: measure the mechanism, not the emergent run). New `PromptCompactionAdherenceEvalTest` (opt-in `PROMPT_COMPACTION_EVAL=1`, mirrors the T5-AF layer-2 pattern) + `PromptSalienceFixtures`: each case = a type that exists on disk under a CANONICAL name, the naming diagnostic listing it, and a bloated previous-response echo that refers to the same concept by a WRONG synonym. Render the real evolution prompt twice differing ONLY in `compactPreviousResponse`, one model call each, score whether the response reuses the canonical name. 4 cases × 3 trials × 2 conditions = 24 calls, ~4 min, paired.

**Result (qwen3-coder:30b, same model the loop uses):** echo-full adherence **1/12 = 0.08**; echo-capped **6/12 = 0.50**; **delta +0.42**. Same model/prompts/diagnostic — only the echo cap differs. This is the proximal proof the loop couldn't give: the model was ignoring the diagnostic because the 64% echo drowned it, NOT because it can't follow it. Confirms the user's dilution hypothesis.

**Not uniform — the honest detail:** already-returned 0/3→3/3 and loan-limit 1/3→3/3 (compaction flips them); but book-not-found 0/3→0/3 and member-store 0/3→0/3 were UNMOVED — the model minted the synonym (`NoSuchBookException`, `MemberRepository`) both ways. Those are strong training-prior names; the base model's naming prior overrides in-context facts, a different failure than dilution that compaction cannot fix (→ outbound deterministic rename, T4-AW direction).

**Scope of the claim (do NOT overclaim — [[feedback-no-defensive-recast]], [[feedback-activity-not-progress]]):** PROXIMAL only — compaction makes the model attend to a diagnostic in a single call (~half the synonym-minting removed). It does NOT prove a full run fulfills more often (distal); and it does not fix prior-driven naming. It earns compaction staying on (flag true) and predicts diagnostics land more often. Method win: this eval is re-runnable in minutes as model/prompt change, vs a 5-hour loop batch — the right level to test per-prompt mechanisms.

---

## UPDATE 2026-06-23 — compaction DISTAL A/B: no measurable run-outcome benefit (proximal-but-inert)

Controlled compaction ON vs OFF, test seed, 2 counterbalanced pairs, cycle-cap 40 + 1s delay (cheaper batch). Backups `/tmp/compaction-distal-ab/`.

| pair | cond | OK-ratio | merges | files | tests | invented | edge | fulfilled |
|---|---|---|---|---|---|---|---|---|
| 1 | off | 0.47 | 11 | 10 | 0 | 0 | 0 | no |
| 1 | on  | 0.57 | 7  | 26 | 6 | 0 | 0 | no |
| 2 | on  | 0.35 | 13 | 10 | 2 | 0 | 0 | no |
| 2 | off | 0.39 | 12 | 19 | 6 | 0 | 0 | no |

Averages: OK-ratio on 0.46 / off 0.43 (+0.03, inside the on-condition's own 0.35–0.57 spread); merges on 10 / off 11.5; files on 18 / off 14.5; tests on 4 / off 3. **0/4 reached the completion edge, 0/4 fulfilled, invented=0 in ALL (not discriminating at capped scale).**

**Verdict: compaction is proximally real but DISTALLY INERT.** The single-call eval proved it lifts diagnostic adherence 0.08→0.50; this full-run comparison shows that does not move the run outcome — every metric is inside the noise. By [[feedback-activity-not-progress]] (validate the outcome moved, not activation) compaction is NOT a completion lever. It is a cheap, proximally-validated, distally-neutral prompt cleanup — keep-on is defensible as cost reduction, but claim only the proximal win.

**The real redirect:** 4 more capped runs, 0 reached the edge — runs stall PARTIAL (10–26 files, planner not driving full spec coverage; same T1-EI/coverage stall as the 2026-06-22 user run). The binding constraint on completion is UPSTREAM of prompt content — drive-to-completion / coverage, not salience. That is the next thing to investigate (NOT yet asserted as THE lever — corpus shows the coverage/backlog-churn family is common but hard amputation is only ~3%; needs its own grounded diagnosis before any build).

---

## UPDATE 2026-06-23 (later) — T4-BN-2 gate-vs-steer A/B CONTRADICTS the freeze diagnosis

Built the steer-not-gate fix (78155d9) on the diagnosis "the per-cycle test-compile GATE freezes main evolution → caused the 06-17→06-23 cliff." A counterbalanced gate-vs-steer A/B (2 pairs, cycle-cap 40, test seed; backups /tmp/t4bn2-validation/) does NOT support it:

| pair | cond | terminal | cyc | merges | files | tests | edge | probeERR | testPathErrs |
|---|---|---|---|---|---|---|---|---|---|
| 1 | legacy-gate | GIVEN_UP@8 | 8 | 2 | 3 | 0 | 0 | 1 | **0** |
| 1 | fix-steer | cap40 | 40 | 8 | 22 | 6 | 2 | 17 | 108 |
| 2 | fix-steer | paused@24 | 24 | 7 | 25 | 6 | 0 | 16 | 271 |
| 2 | legacy-gate | cap40 | 40 | 4 | 23 | 6 | 0 | 24 | **337** |

- **The dramatic pair1 contrast was an artifact:** pair1/legacy-gate collapsed at 8 cyc with 0 test-path errors — gave up early for reasons unrelated to test-compile; NOT a "freeze signature" (I wrongly called it one).
- **Gate-mode does NOT freeze:** pair2/legacy-gate hit 337 test-path errors (the exact freeze condition) yet ran to cap and built 23 files + 6 tests. Gate-mode keeps merging main-only mutations while test-bearing ones are rejected.
- **No outcome separation:** both conditions ~22-25 files + 6 tests; neither reached the edge reliably; neither fulfilled. The fix fires (steered 3-12×) but shows NO demonstrated run-outcome benefit.

**Correction:** the freeze mechanism is real IN CODE (a test-only break does reject the mutation) but is NOT load-bearing on the outcome, and removing it does not move fulfillment. The 06-17 cliff is most parsimoniously "test-requiring goals are simply harder" (must also author working tests + deps), NOT the test-compile gate. This is the SAME failure mode as the naming/compaction work: a mechanistically-real change that is distally inert ([[feedback-activity-not-progress]], [[feedback-no-defensive-recast]], [[feedback-verify-diff-is-substantive]]). The pair-1 excitement was caught and retracted before it was reported as a win.

**Disposition:** keep the steer fix as a principled correctness change (a main-green mutation shouldn't be discarded over a test that may be pre-existing or ahead-of-main; flag-gated, harmless) BUT drop the claim it fixes the cliff. T4-BN-3 (focused test-repair) inherits a weakened rationale — its one independently-justified piece is the deterministic "ensure test deps in pom" step, which targets the dominant REAL error (missing Mock/Test/ExtendWith deps). Get a steer from the user before building it.

---

## UPDATE 2026-06-23 (drive-to-fulfillment loop) — the PLAIN happy path has regressed; not my flags

Drove toward GOAL_FULFILLED, 3 iterations, all stalled on the SAME main cross-file coherence wall:
- iter1 (test seed, all fixes): GIVEN_UP @33cyc, 101 cannot-find-symbol, 0 controllers, never reached the test layer. (Caught my own GAV "fix" hypothesis being wrong — the pom self-corrected to spring-boot-starter-validation; validation was transient churn.)
- iter2 (PLAIN seed, all fixes): convergence stall @23cyc, 368 cannot-find-symbol, 0 controllers, 9 OK/55 ERR.
- iter3 (PLAIN seed, compaction OFF + ensure-deps OFF = ~06-17 baseline): convergence stall @24cyc, 250 cannot-find-symbol, 0 controllers, 13 OK/38 ERR.

**Key finding: the plain (no-test) seed — which fulfilled 3/3 on 06-17 and 40+ times historically — now stalls on main-coherence, AND it is NOT caused by this session's flags (compaction OFF did not restore it).** Errors are genuine model incoherence (referencing real project types across files without resolving: `class MemberService` ×48, `class BookService` ×34, `class Book`, `method getMemberById`/`getBookById`). Model is qwen3-coder:30b (configured + present on endpoint).

**So the regression is below my flags** — candidates: (a) local model/environment drift since 06-17 (local-first: can't swap), (b) a NON-flag code change in the repo since 06-17 (bisectable/fixable), (c) high variance (less likely — 3 consecutive 0-controller stalls). **Decisive next test (not yet run): build the jar at the last 06-17 commit (`12b4626`) and run the plain seed.** old-jar fulfills + new-jar stalls ⇒ in-repo code regression (bisect it); both stall ⇒ environment/model drift (same code fulfilled 06-17, so the model/env changed). **Stopped the auto-loop here rather than grind into a wall my changes don't touch ([[feedback-activity-not-progress]] at the loop scale).**

---

## RESOLVED 2026-06-24 (git-bisect) — the regression was T4-BN-2, now reverted; constraint has MOVED

The "likely env/model OR code regression" question above is SETTLED: it was **code — T4-BN-2's per-cycle build-command flip `mvn compile` → `mvn test-compile`** (commit 8cd7ad1, 2026-06-17 19:02), and it regressed even the PLAIN happy path.

**How found:** user pushed `main` (= 1108cb5, T4-BQ slice-1, the last-good base) and had me branch `feature/completion-correctness` off it and cherry-pick selectively. git-bisect on plain-seed health (good=main, bad=curated tip) → the two commits between last-healthy and first-sick are test-file/README only, so T4-BN-2 is the sole runtime change. **Single-variable proof** (curated branch, plain seed, only the command differs): `mvn compile` → cannot-find-symbol **61**, OK-ratio **0.60**; `mvn test-compile` → **1534**, **0.26**.

**Mechanism:** the local model writes stray test sources even on a no-test goal; `mvn test-compile` then fails the WHOLE probe (tests reference not-yet-built main types / missing test deps) → the main-green mutation is rejected/rolled back → main evolution freezes (the 1500-cfs cascade). `mvn compile` ignored test sources, so main always progressed. (Also why my steer fix didn't save it: a stray test referencing main types errors in BOTH trees, so `isTestOnly` is false → still gated.)

**Fix (committed `0c98742` on feature/completion-correctness):** per-cycle probe reverts to `mvn compile` + `include-test-compile=false`. Test correctness stays at the completion edge (package-gate + test-gate + T4-AN-2b), where it cannot freeze main.

**Result — regression genuinely fixed:** curated+revert produced the **healthiest + most complete curated run** — all 3 controllers, 9 endpoints, ISBN `\d{13}`, OK-ratio 0.66, cfs 80, 28 merges in ~41 min (on the new `fast` profile). (Note: an earlier "0 controllers" report was a grep artifact — the model used a flat `com/example/demo/books/` package, not a `controller/` subpackage. Verify output STRUCTURE before claiming absence.)

**The constraint has MOVED — next lever:** the near-complete run did NOT formally fulfill; it **paused via T1-EI (planner produced empty output) + T4-I (convergence brake)** before the root was marked COMPLETED. So the binding constraint shifted from "can't keep the build coherent" (T4-BN-2, fixed) to **planner-coverage / drive-to-formal-completion** (a near-complete artifact pauses instead of fulfilling — same T1-EI/T4-I terminal as the 2026-06-22 user run).

**Residual / honest caveat (two-factor — [[feedback-calibrate-not-flip]]):** even **bare main (1108cb5)**, the state that fulfilled 3/3 on 06-17, did NOT fulfill in a 90-min plain run (healthy: 0.74/3-controllers, but ran out the clock). So beyond T4-BN-2 there is a residual — env/model slowness, run-variance, or the convergence brake firing on healthy-but-slow runs. NOT resolved. So: T4-BN-2 explains the build-health collapse (0.26↔0.74); a separate factor explains why even healthy runs no longer fulfill quickly.

**Meta:** I had diagnosed the T4-BN-2 freeze earlier this session, then RETRACTED it on one noisy gate-vs-steer A/B, then re-derived it via bisect — see [[feedback-calibrate-not-flip]].

---

## UPDATE 2026-06-24 — T2-EH (verify-before-pause) SHIPPED; the "drive-to-formal-completion" gap actuated

Shipped the fix for the moved constraint (`6166075`, suite 1649 + 7 tests). **Mechanism settled deterministically from the code** (not a full run): a subgoal is promoted to COMPLETED only when the evaluator has BANKED every criterion (`allCriteriaDefinedAndBanked`); merges land but the evaluator marks an internal-structure criterion ✗ (or never re-grades the final code), so the leaf stays ACTIVE → `autoCompleteRootIfSatisfied` early-returns (it bails while ANY subgoal is ACTIVE) → the T4-I brake was the only thing left and it just halted. That is why "merges land yet subgoals stop being promoted."

**Fix:** `GoalPlannerService.verifyAndPromoteBeforePause(context)` — before a T4-I or T1-EI halt, re-verify every ACTIVE **leaf** subgoal against the CURRENT output (`CriteriaVerificationService.verifyAndRecord`, now with an explicit-criteria overload so any subgoal can be graded, not just `currentSubGoalId` — the T4-BP promoting direction), promote those that pass (incl. already-banked-but-never-promoted), then `autoCompleteRootIfSatisfied`. The brake **verifies-then-maybe-halts**; resets the stall counter on any promotion or completion (T4-I tuning — the 20-cycle limit becomes a verify checkpoint, no constant inflation). Conservative: all-criteria-pass to promote; abstains on empty/failed verification.

**Field validation status — ACTIVATION CONFIRMED (run 2).** Two monitored plain-seed runs on the `fast` profile.
- **Run 1 (88 cyc, 55-min deadline-cut):** HEALTHY — okRatio 0.73, 3 controllers, **completed-with-merge climbed to 18** (subgoals kept promoting), brake counter rose to 10–11 twice and RESET both times, never reached 20. So verify-before-pause never fired (its precondition, a 20-cycle stall, was not reached) and the run was cut still progressing. This run **proves no regression** (brake resets normally, no spurious verify firing, no crash). Run-to-run variance.
- **Run 2 (130 cyc, 95-min deadline-cut): FIRST FIELD FIRING of verify-before-pause, exactly as designed.** The brake counter climbed 17→18→19 and at the limit (20) verify-before-pause ran instead of halting: `T2-EH: verify-before-pause promoted 1 of 2 active leaf subgoal(s) against current code` → `broke the convergence stall (rootCompleted=false, promoted=1, completed-with-merge 34 -> 35) — not halting.` It verified the 2 ACTIVE leaves against the current code, **promoted the 1 whose criteria passed** (correctly left the other — conservative; `rootCompleted=false` ⇒ NO false completion), bumped completed-with-merge 34→35, **reset the brake and continued.** The actual `root convergence stall` halt fired **0×** — pre-T2-EH this would have been a hard SYSTEM_PAUSED abandoning a near-complete artifact (3 real controllers Book/Member/Loan in a flat `com/example/demo/` package, 8 endpoints). Deadline-cut still progressing.

**Takeaway (calibrated — [[feedback-activity-not-progress]]):** verify-before-pause is now **unit-test-correct, regression-free, AND field-activated** — it fires at the brake edge, verifies active leaves against current code, promotes only what passes, and converts a hard halt into continued progress. What is NOT shown is a full GOAL_FULFILLED via this path (neither run reached the all-terminal edge within the window). The residual two-factor (healthy runs are SLOW to formally fulfill) is UNCHANGED by T2-EH — T2-EH rescues a STALLED run, not a merely-slow one. **T2-CR stood down same day** (0/3788 `evaluator_repeat_cap_fired`).

---

## UPDATE 2026-06-24 (late) — run-2 churn diagnosis: NOT slow, CHURNING; the "get a fulfillment again" lever, and why NOT more T4-BQ

Drilled into run-2's log (`autobuilder-2026-06-24_03-40-22.log`, 130 cyc) to settle "is a healthy run slow or stuck?". **It was CHURNING, not slow** — "run it longer" would not have fulfilled it:
- **505 subgoal appends vs 145 completions** — the tree grew ~3.5× faster than it closed; never trended toward all-terminal. completed-with-merge plateaued at 34.
- **647 `cannot find symbol`** (+ 111 package-missing, 82 ctor-mismatch) — the dominant error class; **build red 54%** (227 OK / 272 ERR).
- **1422 BUILD_FIX** refs + **527 PLANNING_THROTTLED/backlog-cap** events — planner pinned in build-fix mode, backlog saturated at the 18-active cap. 126 consolidation folds couldn't keep up.
- `autoCompleteRootIfSatisfied` WAS reached repeatedly but kept getting **deferred — replan in flight**.

**So the binding constraint on `GOAL_FULFILLED` is exactly this file's original headline:** keeping a LARGE interdependent working set coherent (cannot-find-symbol dominant) long enough to converge. The T4-BN-2 revert fixed the *worst* collapse, but in a full healthy run the cfs class still dominates → build red half the time → planner stuck appending build-fixes → backlog saturates → tree never converges.

**Decision (user-aligned 2026-06-24): do NOT double down on T4-BQ.** The user pointed at the `checkpoint/2026-06-16-completion-correctness` branch — both built T4-BQ slices are inert: slice-1 `UsageDerivedMemberSynthesizer` emits `Object` for non-JDK params/returns → second-order Object-typing cascade ("fires but doesn't help", held off); slice-2 naming block ignored by the model (stood down 0/6). **Deeper structural objection:** T4-BQ closes the build by synthesizing STUBS → best case it trades `cannot find symbol` for HOLLOW, which the completion gate (T4-BP-3) is designed to reject → still no honest fulfillment. Wrong *shape* of fix for a REAL fulfillment. Two responses to cfs-churn have now failed: steer inbound (ignored) and synthesize the missing pieces (hollow + Object-cascade).

**The candidate lever instead — shrink the working set (depth-first per resource), HYPOTHESIS not asserted ([[feedback-no-defensive-recast]]):** build one resource fully (controller+service+repo+model+exception, green and REAL) before decomposing the next, so the model only keeps one resource's files mutually coherent at a time — not nine. Concrete culprit it would reverse: the planner's **`FULL_COVERAGE_REQUIRED`** rule mandates breadth-first ("your FIRST planning response MUST produce one subgoal per feature"), instantiating the maximal working set up front. The useful kernel of T4-BQ (dependency-ordered creation — `Loan` before `LoanController`) survives *inside* depth-first; the stub-synthesis does not. **User flag (hold for later):** several design choices likely bite each other (depth-first directly contradicts `FULL_COVERAGE_REQUIRED`; other rules are tuned around breadth-first) — **map the rule-interactions BEFORE changing anything**, don't bolt on a rule that fights existing ones. Caveat: the local-model capability ceiling could still bite at any granularity (a single resource may not finish cleanly either) — depth-first is testable cheaply (harness / one run), not a new system. Filed as **T2-EI**.
