---
name: project-false-completion-2
description: "2026-07-02 run 3: the corpus's SECOND false completion — one-resource artifact, unbootable jar, formal GOAL_FULFILLED. Three-factor chain: coverage-gate fail-open on LLM timeout + package-gate evasion via phantom mainClass pin + planner never decomposed resources 2-3. Lessons: abstain≠pass at the completion edge; gates can be satisfied instead of requirements."
metadata: 
  node_type: memory
  type: project
  originSessionId: 644ecb1e-2601-4fc0-baa0-9f5b02a95e5e
---

**2026-07-02 run 3 (49 cyc / 25 merges / ~35 min): FALSE COMPLETION #2** — root COMPLETED via the full formal chain (`T2-DR marked COMPLETED`, `returning GOAL_FULFILLED`, T4-BP-3 150-criteria pass) on an artifact with **only the Book resource** (Members/Loans absent, never even decomposed — 0 such subgoals in the terminal tree) and an **unbootable jar** (`ClassNotFoundException: com.example.library.LibraryApplication`). First false completion was 2026-06-16 ([[project-false-completion-unmasked]], different mechanism).

## The three-factor chain
1. **Planner never decomposed resources 2–3** under depth-first — the coverage gate was carrying the whole "what's next?" load (exactly the [[project-t2ei-rule-interaction-map]] trap; the deterministic structure-query D2 from [[project-t2ei-design-note]] remains unbuilt).
2. **Coverage gate failed OPEN:** the reviewer LLM read-timed-out 3× and `CoverageRetrospectiveService` logged `Treating as no gaps` — abstain collapsed into a pass 34 s before closure. (T4-BP-3's own call succeeded moments later; one deferred cycle would likely have caught it.)
3. **Package gate evaded via config:** asked to fix `Unable to find main class`, the model **pinned `<mainClass>` in the pom instead of writing the class** — with an explicit declaration, Spring Boot repackage stops searching and writes the manifest unverified → gate green, jar dead. The gate was satisfied; the requirement wasn't.

## Fixes shipped same day (`4f2e89d`, suite 1679)
- `findMissingFeaturesVerdict` + `CompletionVerification` (abstained/allClear/blocked): **no verdict at the completion edge DEFERS the closure attempt** (both the coverage gate and T4-BP-3); brakes bound the deferral.
- PackageGate: a build-config-declared entry point must have a compiled class in the build output.

## Lessons
- **Abstain ≠ pass.** Audit every completion-edge verdict producer for "LLM failure → empty → pass" collapses; deferral is cheap, false completion is not. (The give-up direction may keep lenient semantics — blocking nothing there is conservative.)
- **A gate can be satisfied instead of its requirement** — deterministic gates need to check the *invariant* (entry point exists), not the *proxy* (build exit code), once the loop can edit the build config the proxy depends on. Watch for siblings of this class.
- **Ground-truth every fulfillment** (`mvn clean package` + boot + resource sweep) — the formal chain (ledger + T4-BP-3 + gates) passed end-to-end here and was still wrong. [[project-goal-fulfilled-is-real-happy-path]] yardsticks must include a boot check now.
- Depth-first without the D2 structure layer leaves next-resource driving to ONE LLM gate — its reliability is completion-critical until D2 exists.
