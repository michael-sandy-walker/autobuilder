# Autobuilder -- technical documentation

How Autobuilder is built: the cycle that drives it, the agents and the contracts they answer in, every check a
change passes on its way to being merged, how a run decides it is finished or stuck, what it asks then, how it
talks to the model, what it keeps, and how it is measured. Each part names the class or configuration key it
describes, so it can be checked against the code (`scripts/doc_check.py`) and read next to it.

For what Autobuilder does and how to use it on a project of your own, see [functional.md](functional.md). The
generated configuration reference is [configuration.md](configuration.md).

Contents: 1 the shape of the system -- 2 the cycle -- 3 the agents -- 4 what the evolution agent is shown --
5 from a proposed change to a merged one -- 6 deterministic repairs -- 7 the completion edge -- 8 goals and the
specification -- 9 brakes, halts and rewind -- 10 starting and resuming a run -- 11 the stuck layer -- 12 the
model client -- 13 the graph, the trace and what a run keeps -- 14 the REST surface and the browser pages --
15 profiles and configuration -- 16 tests and the measurement apparatus -- 17 design rules the code follows.

---

## 1. The shape of the system

Autobuilder is a Spring Boot application (Java 21) that changes a *target project* toward a stated goal, one
cycle at a time, using a language model for the parts that need judgement and deterministic code for
everything that can be decided without one. It runs against a local model behind an OpenAI-compatible endpoint
(`llm.api.url`, `llm.api.model`); Neo4j keeps the history of every run.

A target lives in its own directory, `file.base-path` (default `data/firestarter`). Inside it:

| Path | What it holds |
|---|---|
| `bootstrap/seed.txt` | the specification -- the goal, in prose |
| `bootstrap/seed.obligations.txt` | the specification split into checkable obligations (section 8) |
| `bootstrap/protected-paths.txt` | paths the loop may not write, and declarations it may not change |
| `bootstrap/yardstick`, `VERSION`, `CHANGELOG.md` | for a toy case: its scoring script and its version history (section 16) |
| `output/` | the project being built or changed -- the only place the loop writes code |
| `meta/` | the loop's own state: `GoalStack.json`, `run-identity.json`, `stuck-ledger.json`, the adoption manifest, the preserved artifact, the run end report |
| `mutations/`, `snapshots/`, `checkpoints/`, `checkpoint-archive/` | candidate changes and tree copies for rollback, rewind and branching |
| `logs/` | `autobuilder.log` and `behaviour.ndjson`, the structured event trace |

Packages, by responsibility:

| Package | Responsibility |
|---|---|
| `core.service`, `core.lifecycle`, `core.engine`, `core.deliberation` | the cycle: scheduling, the context it carries, which agent runs, convergence |
| `agent.*` | the agents (planner, analysis, evolution, evaluation, merge, interpretation) and their model adapters |
| `protocol.*` | what each agent is asked (prompt contracts and prompt blocks) and how its answer is read (parsers) |
| `goal` | the goal tree, the specification's obligations, coverage, closure |
| `core.build` | everything that builds, runs or reads the target's code: probes, gates, guards, structural questions |
| `core.build.inject` | deterministic compile fixes (section 6) |
| `core.mutation` | rejected-proposal memory and the deferred-mutation ledger |
| `core.stuck` | the stuck layer: one home for "the loop is not getting anywhere" |
| `core.checkpoint`, `core.merge`, `core.persistence`, `core.retention` | snapshots, rollback, rewind, branches, lineage merges, end-of-run state, pruning |
| `core.init`, `core.coldread` | start modes, run identity, resume, the target catalogue, reading an unknown project |
| `analysis.*` | heuristics, diff, structure queries, scope, coverage and problem analysis |
| `client` | the model client: budgets, output bounds, the circuit breaker, record/replay |
| `graph.*` | Neo4j: every agent's actions and results, run and lineage identity |
| `observability`, `controller` | the behaviour trace, readouts, the REST surface, the browser pages, narration |
| `tools.*` | source summarising, structural diffs, hash tracking, diagrams |

---

## 2. The cycle

`AutobuilderService` is the entry point. With `autobuilder.scheduler.enabled` it runs a cycle, waits
`autobuilder.scheduler.inter-cycle-delay-ms`, and runs the next; without it, cycles run on request
(`POST /autobuilder/cycle`, `POST /autobuilder/evolve`). Before the first cycle, `StartModeService` decides
whether the run may begin at all (section 10).

`CycleExecutor` prepares a cycle: it builds the `CycleContextData` (the immutable record every agent reads and
returns a changed copy of -- the current goal and subgoal, the live tree, the last build and probe outcomes, the
open requests, the participation windows, the last rejection reasons), and runs the post-cycle steps: scope
guard, merge bookkeeping, criteria banking (`flushPendingCriteriaIfNotRolledBack`), subgoal completion
(`applyCompletion`, through `SubGoalFulfillmentHeuristic` and `GoalCompletionDecider`), checkpointing and the
brakes of section 9. `ContextLineageTracker` decides which context fields survive into the next cycle and for
how long.

`AdaptiveExecutionEngine` runs the cycle itself, as an explicit selection pass:

1. **Halt** -- a pause or a terminal fact ends the pass before it starts.
2. **Candidates** -- `DeliberationService` decides which roles may run in this cycle (rule-based, no model call;
   `RuleBasedDeliberationProposer` proposes, `DefaultDeliberationValidator` validates, `DeliberationExecutionGate`
   applies the result).
3. **Ordering** -- the candidates are ordered by explicit constraint, the urgency of open requests addressed to the
   role, the role's participation window, and base priority; ties go by pipeline order
   (planner, analysis, evolution, evaluation, merge).
4. **Execution** -- eligible agents run in that order until the cycle merges or none is left. An agent that a
   previous agent made eligible gets a second pass in the same cycle. `ExecutionEligibilityExplainer` logs why
   each role ran or was skipped.

### What replaced "signals"

Earlier versions steered the cycle with stored signals that decayed over time. They were replaced by three
mechanisms that cannot go stale, each in `core.lifecycle`:

- **Derived facts** (`DerivedFacts`) -- predicates computed from the context where they are read, never stored.
- **Requests** (`ChannelRequest` on the `RequestChannel`) -- a message from one mechanism to another, with the
  urgency its issuer stated, consumed on read or expiring visibly after a stated number of cycles.
- **Participation windows** (`ParticipationWindows`) -- one open window per role, with a boost and a horizon,
  aged once per cycle.

The names survive as vocabulary: `SignalType` and `BasicSignals` name the kinds of requests, windows and
switches, and `SignalReadout` renders all three in one shape for prompts and snapshots (`Signal` is only that
readout's carrier). `PUT /autobuilder/signals/{name}` is the operator's poke: a request kind is queued with the
given urgency, a role name opens or revokes that role's window, a switch such as `SYSTEM_PAUSED` is set or
cleared.

---

## 3. The agents

Each agent lives in `agent.<role>`, has a prompt contract in `protocol.<role>` and an answer parser beside it, and
calls the model through `LLMAgentProtocolInvoker` (which re-asks once with the reason when an answer does not have
the required shape).

| Role | Class | What it decides |
|---|---|---|
| planner | `PlannerAgent` | splits the goal into subgoals, each with acceptance criteria; replans on request |
| analysis | `AnalysisAgent` | classifies the current subgoal (executable, structural, hybrid), reads problems, may propose subgoals within its injection budget |
| evolution | `EvolutionAgent` (`EvolutionLLMAdapter`) | proposes the change for the current subgoal |
| evaluation | `EvaluationAgent` | judges the proposed change against the subgoal's criteria, with the heuristics' vote |
| merge | `MergeAgent` | applies an accepted change and records it |
| interpretation | `DefaultInterpretationAgent` (`InterpretationService`) | reads each cycle's outcome back into the context for the next cycle's prompts |

Smaller model roles, each a single question with a structured answer:

- **scope review** (`SubgoalScopeReviewer`, `DefaultSubgoalScopeGuard`) -- whether a produced diff belongs to the
  current subgoal; a confident "out of scope" blocks the progress from being applied (the planner, not the guard,
  reconciles);
- **criteria grounding** (`CriteriaGroundingService`) -- whether each acceptance criterion is one the specification
  states (`SPEC`) or one the loop added (`AUTHORED`), stamped per criterion (`CriteriaBinding`); authored criteria
  are advisory and cannot block closure;
- **goal kind** (`GoalKindClassifier`) -- what kind of work a goal is;
- **goal equivalence** (`GoalAdoptionService`) -- whether two goal descriptions name the same work, the one notion
  of "duplicate" every consumer uses;
- **build-error classification** (`BuildErrorClassifierAgent`) -- a build failure's category and the missing
  artifact or symbol, with the pattern matcher `BuildErrorClassifier` as its fallback;
- **the terminal agent** (`TerminalAgent`) -- read-only commands in a copy of the tree (section 11).

---

## 4. What the evolution agent is shown

`EvolutionProtocolContract` builds the prompt; blocks are added in `EvolutionLLMAdapter`, each within the prompt
budget `PromptTokenBudget` computes (`llm.api.context-window-tokens` minus `llm.api.response-reserve-tokens`). A
block that does not fit is left out whole and counted, never cut.

**Source and structure**

- **The source of the existing files the work names** (`SubgoalSourcePromptBlock`): files the subgoal, the goal or
  the last rejection mentions, ordered so the class to change comes first (a file the last answer could not
  edit; then the subgoal's own files; then files the work may write; then read-only ones). A file too large to
  show whole is shown as **parts**: the members the work names, each exactly as the file has it (methods, fields
  and nested types), and -- for a file the work may change -- the **end of its type**, the last member and the
  closing brace, where a member the work adds goes. A file is never cut mid-way.
- **The paths and declarations it may not change** (`ProtectedPaths`), with every write refused since the last
  prompt.
- **The API of the dependencies the work uses** (`DependencyApi`): the classes of every dependency package the tree
  or the last failed attempt imports (an invented class is shown absent), the public members of every imported
  class, and the packages of a library the goal names -- read from the project's own jars.
- **Where routes are actually served** (`ServedPrefixPromptBlock`), when the configuration adds a prefix the code
  never mentions; the parts of a target's briefing the subgoal is about (`BriefingSource`).

**What happened last time**

- **Why the previous attempt was rejected** (`RejectionFeedbackPromptBlock`): the reasons the evaluator and the
  heuristics recorded, relayed verbatim.
- **The previous compile errors** (`CompileDiagnosticPromptBlock`, from `CompileAuthorErrors`), and targeted
  versions of them: a member missing on an existing type (`MissingMemberPromptBlock`, reversed for a protected
  type -- use what it declares), a type to create (`MissingTypePromptBlock`), a call on a wrapped value
  (`UnwrappedContainerPromptBlock`), a type declared in a second package (`PackageFragmentationPromptBlock`), a
  change that was rolled back (`RolledBackPromptBlock`), the last build result (`BuildResultPromptBlock`).
- **The project's own test run** of the tree as it is (`TestRunPromptBlock`): what fails, with each failure's
  message and, for a parameterized case, the row it ran with (`ParameterizedCaseInput`); tests that do not compile
  are reported with the compiler's errors.
- **Values the code was observed to return** (`ObservedBehaviour`, `ValueObservationProbe`), kept for the run.
- **The stuck layer's answers** to failures that kept repeating (section 11); a standing answer about the tree
  stays in the prompt.

**Context**

- the analysis observations (`AnalysisObservationsPromptBlock`), the open problems (`ProblemContextPromptBlocks`,
  from `DefaultProblemDetector`), what the specification asks that the plan does not yet say
  (`SeedCoverageAnalyzer`), and earlier abandoned work on the same topic (`AbandonedGoalQueryService`).

### How an answer becomes a change

`EvolutionResponseParser` reads fenced blocks headed with a language tag and a path. A block ends at three
backticks **on a line of their own** (`FencedBlocks`) -- never at three backticks inside a line, which a source
file may legitimately contain. An existing file is changed by an **edit block** (`EditHunks`):

````
```edit [src/main/java/org/sample/Quill.java]
<<<<<<< SEARCH
the lines as they are now
=======
the lines that replace them
>>>>>>> REPLACE
```
````

Each SEARCH part must occur exactly once in the file as it is; zero or several matches refuse that file's edit
with the reason, which the model is shown on its retry. A block is an edit by what it says: one whose body opens
with the SEARCH marker is an edit under any tag. New files are written whole.

---

## 5. From a proposed change to a merged one

A change passes these checks, cheapest first; each can stop it.

1. **A repeat of a standing rejection** (`RejectedProposalLedger`). A proposal identical to one already rejected
   against the same tree is refused without being evaluated again.
2. **Protected paths and declarations** (`ProtectedPaths`, `PreservedMembers`). `bootstrap/protected-paths.txt`
   lists globs the loop may not write, and `members: <glob> [except <names>]` lines: a file whose existing
   declarations are kept as they were adopted, whatever a writer puts there -- new declarations are kept beside
   them, and the names after `except` may be changed.
3. **Package fragmentation** (`PackageFragmentationCheck`). No two files may declare the same top-level type in
   different packages; such a write is refused before it lands.
4. **The compile probe** (`CompileProbeService`). The change is written to `output/` and the target's build command
   runs (`autobuilder.build.*`; `JavacDirectProbe` compiles in-process instead when configured). A change that
   does not compile is rolled back and rejected before any model judges it -- after the deterministic repairs of
   section 6 have had their one attempt. A rejection fully explained by members a later change could supply is
   kept in the deferred-mutation ledger (section 6) instead of being lost.
5. **Evaluation** (`EvaluationAgent`). The model scores the change against the subgoal's criteria, shown the
   project's own test run of exactly these files when the operator declared the suite an authority
   (`EvaluationTestEvidence`, `DeclaredTestSuite`).
6. **The heuristics' vote** (`HeuristicNegotiator`). Deterministic heuristics (`analysis.heuristic`) each give an
   opinion with a confidence; a weighted majority (confidence and margin thresholds) can overrule the model, and a
   *defect finding* keeps its rejection whatever else says. There is no single score threshold: acceptance is
   the vote. The score adjustment is pluggable (`autobuilder.heuristic.score-strategy`).
7. **Decisive acceptance by measurement** -- two cases in which a deterministic check outranks the model's
   rejection (neither overrides a defect finding):
   - **the gate that raised the work item passes** on the candidate (T4-CC, `applyOriginatingGateCheck`): for a
     subgoal raised by the package, boot or test gate, that gate is re-run on the staged change; if it passes,
     the change is accepted **and the work item is fulfilled**, its criteria recorded as met on the gate's word
     (`criteriaMetByGate`); if it still fails, the gate's own output replaces the model's reason;
   - **the operator's suite is better with the change** (`applyTestProgressCheck`, `TestRunGate.compareWithTree`):
     the tree's tests did not compile and the candidate's run; or fewer tests fail and none that passed breaks;
     or -- when the tests still do not compile on either side -- fewer test **files** fail to compile and none
     newly does, with the candidate's main code compiling (`compareUncompiledWithTree`). This is what lets a
     change that must touch several classes before anything compiles be kept one class at a time. Every
     comparison is recorded as a `test_delta` event (`measure`: tests or compilation), accepted or not.
8. **Merge** (`MergeAgent`, `MergeProcessor`). The accepted change stays; it is recorded in the graph. A merge that
   breaks the build afterwards is rolled back (`MergeRollbackService`), and the next prompt says so. After a merge,
   `StructuralTreeRepair` applies the repairs the loop owns for properties of the tree.

### The heuristics

| Heuristic | Opinion |
|---|---|
| `TestRegressionHeuristic` | **defect**: the change makes more of the operator's tests fail than the tree, or its suite does not finish |
| `StagedTestFailureHeuristic` | **defect**: the change's own test classes fail, on a target whose suite is an authority |
| `MergeRegressionHeuristic` | **defect**: the change takes an annotation off a type that had it and puts nothing in its place |
| `ManufacturedDependencyHeuristic` | **defect**: a container-managed component is created with `new` inside another instead of being injected |
| `StoreCoherenceHeuristic` | the same entity stored in two different classes (writes to one are invisible to the other) |
| `CrossFileConsistencyHeuristic` | calls to methods no known project type declares |
| `ScoreRegressionHeuristic` | the evaluator's score dropped sharply against the subgoal's previous one |
| `ProblemReductionHeuristic` | an acceptance with no score gain, no goal progress and no fewer open problems |
| `NoMeaningfulChangeHeuristic` | a high score for no change |
| `StructurallyInsignificantChangeHeuristic` | a high score for structurally insignificant changes |
| `RedundantOutputHeuristic` | output files repeated from the last cycle |
| `SemanticClarityHeuristic` | a modest reward when deletions outweigh additions |
| `PlannerRepetitionHeuristic` | the planner's output repeating within a scope |
| `DeterministicScoreHeuristic` | the deterministic project score (`ScoringService`) when its confidence is high |
| `SubGoalFulfillmentHeuristic` | not a voter: decides after the merge whether the subgoal is fulfilled |

---

## 6. Deterministic repairs

Where a failure has a known shape, the loop repairs it itself instead of spending a model call on it.

**Compile-fix injection** (`core.build.inject`). After a failed compile probe, `CompileFixInjector` classifies the
error and walks the `CompileFixResolver` chain in order; the first `FixProposal` is applied once and re-probed. A
proposal that already failed is not repeated until a file it touches changes (`FixAttemptLedger`). Every injected
change carries its provenance (`InjectorFixRecord`) into the evaluator's and planner's prompts.

| Resolver | Repairs |
|---|---|
| `SymbolImportResolver` | a missing import (and the dependency that provides it) |
| `MisplacedMemberResolver` | a call that named the right member on the wrong type (`ClasspathMemberDeclarationService` finds where it is declared) |
| `DeclaredAccessorResolver` | `getX()` called on a project record that declares `x()` |
| `UnresolvableDependencyResolver` | a declared dependency version that will not resolve |
| `TestDependencyEnsurer` | test sources failing on missing test-framework symbols |
| `BootstrapPomResolver` | sources without a build descriptor: a minimal one derived from the sources |
| `IdiomSkeletonResolver` | a missing class whose name matches a configured idiom (`IdiomSkeletonConfigProperties`) |

Where a fix needs coordinates or versions, **discovery comes before tables**: `PackageDiscoveryService` asks the
project's own observations first (its imports, its resolved classpath through `ProjectClasspathService`, the
dependency-management chain through `BomManagedVersionsService`), then the local artifact repository
(`LocalArtifactRepository`), then a coordinate search index (`CoordinateSearchClient`); `VersionSuitability` picks
which published version to write; `WellKnownPackageRegistry` and the model lookup
(`LlmSymbolImportLookupService`, memoised in `SymbolImportCache`) answer only when discovery abstains.
`BuildToolProbe` runs the build tool's own diagnostics instead of letting the model guess versions.

**The deferred-mutation ledger** (`core.mutation`). A candidate the compile probe rejected only because members or
types it uses do not exist yet is not thrown away: `BlockerExtractor` classifies each diagnostic
(`DiagnosticClass`: deferrable, or an author error), the candidate is stored in the graph with `BLOCKED_BY`
edges to what it needs (`DeferredMutation`, `MutationBlocker`), and after every merge
`BlockerClearanceDetector` checks whether the merged code now declares those. A candidate whose blockers have all
cleared is re-staged at a later cycle start (`DeferredRestageScheduler`, `DeferredMutationRestager`): re-probed,
and dropped if the files it touches drifted in the meantime.

**Tree repairs** (`StructuralTreeRepair`), applied at any moment the tree can be wrong, not only after a merge: a
type supplied to a constructor but registered nowhere gets the registration its peers carry
(`InjectedTypeRegistrationRepair`, from `WiringFactsAnalyzer`'s facts); self-authored test sources that cannot be
made to compile, on a goal that never asked for tests, may be removed (`BrokenTestQuarantine`). A missing type that
recurs across subgoals drives a targeted "create it" redirection (`RecurringDefectTracker`).

---

## 7. The completion edge

When every subgoal is accounted for, the goal may close -- only if these pass, each run once per completion
claim, not per cycle:

- **the package gate** (`PackageGate`) -- the project assembles into a startable artifact;
  `PreHaltPackagingCheck` gives a stalling run one chance to become packageable before it halts;
- **the test gate** (`TestRunGate`) -- the project's own test suite passes. A run cut at the timeout is a failure
  that names the test classes that never finished, never a partial pass; tests that do not compile are reported
  with the compiler's errors;
- **the boot gate** (`BootGate`) -- the packaged artifact is started and probed the way its specification says:
  endpoints answered (requests synthesized per criterion by `CriterionProbeSynthesizer`, bodies from the
  artifact's own model and handler declarations by `RequestBodySynthesizer` and `RequestBodyContractQuery`),
  commands run (`CommandExerciseSynthesizer`); dependency-injection wiring is proven resolvable without running
  (`InjectionWiringQuery`);
- **criteria verification** (`CriteriaVerificationService`, `ClosureClaimVerifier`) -- every acceptance criterion
  is confirmed against the current code. **A record answers before a reading, either way:** that the tests pass
  (`TestRunClaims`, from the test run of this exact tree), that nothing outside the intended change was changed
  (`UnchangedClaims`, from the `AdoptionManifest` of an adopted project), and that a named file was changed
  (`ChangeClaims`, which can only contradict). A tree too large for one verification prompt is verified in
  pieces: each criterion with the files it is about shown whole, the rest referenced by outline, and the model
  may ask for a file it needs. A refutation names which criteria failed (`ClaimRefutation`). A work item raised by
  the criteria verifier is discharged only by that verifier.

Gates that fail raise **work items**: subgoals stamped with the gate that raised them. Such an item is closed by
measurement -- by its gate passing (section 5) -- never by a model's score.

`BehaviouralSelfCheck` runs the boot and criterion probes *during* a run, so a run that never reaches the
completion edge is still observed; its findings become work items and stuck observations.

---

## 8. Goals and the specification

`GoalPlannerService` owns the goal tree: one root (`GOAL`) and its `SUBGOAL`s, each `ACTIVE`, `COMPLETED`,
`GIVEN_UP` or `SUBSUMED`, each subgoal with acceptance criteria. The tree is stored in the graph (`GoalGraphService`)
and mirrored to `meta/GoalStack.json`; every status change is an appended transition, so the history is a fold.

The specification's obligations live in `bootstrap/seed.obligations.txt` (`SeedObligationsFile`): one obligation
per line, generated by the model from the seed when absent (`SeedObligationsGenerator`, enabled by
`autobuilder.obligations.generate`) and used as an input afterwards. Its header carries the seed's hash and the
body's hash, so an operator's hand edit is kept and reported as theirs. Line markers:

- `rule:` -- how the work is done; guides the work, never blocks completion;
- `gate: tests` -- answered by the project's own test run, not by a model reading the source; the line also
  declares the suite an authority for the whole run (`DeclaredTestSuite`).

`ObjectiveCoverage` checks that the obligations still carry the seed's `OBJECTIVE:` sentence.

**Keeping the plan honest:**

- **Required or extra.** `SpecTraceability` classifies each subgoal by whether its criteria trace to the
  specification; extra work cannot become a fatal blocker of the root.
- **Coverage.** `SeedCoverageAnalyzer` reports what the specification asks that the plan does not say (shown to the
  agents); `CoverageRetrospectiveService` re-injects requirements no subgoal ever targeted before the goal may be
  declared exhausted (`POST /autobuilder/coverage-review` runs it on request). `EndpointSurfaceQuery`,
  `CommandSurfaceQuery` and `SpecEndpointBehaviourQuery` answer "what does the specification declare that the
  code does not have yet" as queries over structure, not as model calls.
- **Duplicates.** `GoalConsolidationService` revisits the tree for near-duplicate active goals when the loop
  stagnates or the active-leaf count grows, using the one equivalence reviewer (`GoalAdoptionService`).
- **Earlier work.** `AbandonedGoalQueryService` surfaces given-up clusters and orphaned merged files related to a
  proposed subgoal, so the planner and analysis see prior work before proposing new.

Closure asks whether every obligation the seed states is met (`SeedRequirements`); an obligation no criterion
covers is still asked at the completion edge.

---

## 9. Brakes, halts and rewind

A run ends with `GOAL_FULFILLED`, or the loop stops itself -- an **honest halt** with a named reason, recorded as
the run's `pausedReason` and in the run end report. The brakes:

| Reason | Fires when |
|---|---|
| `root-convergence-stall` | `RootConvergenceTracker`: a stretch of cycles without a subgoal completed with a merge, while `ConvergenceTrend` shows the run has also stopped landing work |
| `exhaustion-runaway`, `root-exhaustion`, `stagnation-runaway` | subgoals keep being given up without a merge, or progress has stagnated past recovery |
| `empty-backlog-injection-limit` | the planner keeps finding nothing left to inject |
| `build-blocked-deadlock` | the build is blocked and no work item can unblock it |
| `planner-output-truncated` | the planner's answer was cut and could not be recovered |
| `null-subgoal-halt` | no current subgoal can be established |
| `llm-circuit-breaker` | the model endpoint kept failing (section 12) |
| `user-pause`, `user-edit` | the operator paused, or changed state by hand |

**Rewind instead of dying** (`RewindPolicy`). At the halt edge, before stopping, a run that is no longer landing
work may return to an earlier checkpoint and take a different path. `CheckpointService` writes the points a run can
return to (references to the per-cycle snapshot and serialised context, plus the in-memory decision state and the
cut point in the goal history); `CheckpointArchive` keeps a source-only copy of each tree so a rewind can reach
past the run that took it; `RewindService` restores, and `StateFingerprintService` recomputes the state's
fingerprint after restoring and compares it with the checkpoint's, so uncaptured state shows up as a mismatch.
`BranchExplorer` and `BranchLedger` explore more than one path from the same fork, one after another, and adopt
the branch that achieved most. `POST /autobuilder/rewind` does the same on request.

**What is kept at a halt.** `PreservedArtifactService` copies the newest snapshot that still passes the run's own
verification bar to `meta/preserved-artifact/`, so a run's terminal state includes the best state it had, not the
last one touched (`EntryPointScan` records per snapshot whether an entry point exists). `RunEndReportService`
writes a machine-readable self-description once per pause; a resumed run reads its parent's diagnosis in its first
prompts.

---

## 10. Starting and resuming a run

`StartMode` names the four ways a run can begin; `autobuilder.start.mode` chooses one and `StartModeService`
checks it against what is actually in `output/` before anything is touched:

| Mode | What it does | Refused when |
|---|---|---|
| `NEW_PROJECT` | clears `output/` and seeds the goal from `bootstrap/seed.txt` | there is no seed; or `output/` holds code the loop did not write (unless `autobuilder.start.confirm-discard-foreign-tree`) |
| `CONTINUE_LINEAGE` | continues a lineage this loop built, restoring its artifact and goal state (`RunResumeService`) | `output/` is empty |
| `ADOPT_PROJECT` | works on a codebase as it is: nothing cleared, nothing seeded; the loop waits for a goal (`POST /autobuilder/goal`) | `output/` is empty |
| `ASK` | touches nothing and waits | always -- it is a resting state |

`TargetCatalog` lists the targets under the targets directory and which modes each supports (`GET
/autobuilder/targets`). An adopted project gets an `AdoptionManifest` (the digest of every file as adopted), which
is what lets "nothing else was changed" be proven rather than read. `GET /autobuilder/cold-read` (`ColdReadService`,
`ColdRead`) gives a factual account of an adopted tree from its own evidence before a goal is given.

Each boot mints a run id and inherits or mints a lineage id (`RunIdentityService`, `meta/run-identity.json`);
every graph write carries both.

**Merging lineages** (`core.merge`). Two lineages that grew one adopted project in different directions can be
combined into a child: `LineageTestMerge` merges test layers over identical library code, member by member;
`LineageCodeMerge` merges differing library code three-way against the tree both started from, with the
declaration as the unit (`scripts/merge_lineages.sh`).

---

## 11. The stuck layer

`core.stuck` is the one home for "the loop is not getting anywhere". Detectors only report what they see as a
`StuckObservation` (`source`, `signature`, the subgoal, the cycle). The `StuckLedger` remembers, per signature, how
often it occurred, which question is next, every question asked with the authority that answered, and whether
the signature came back after an answer. `StuckLayer` asks the due questions while the next evolution prompt is
built, so the answer is in the move it is about.

| Source | Reported by | Becomes a question |
|---|---|---|
| `BUILD` | `GrindDetector` -- the same build failure | at its third cycle |
| `PROTECTED_WRITE` | a refused write to a protected path | at its third cycle |
| `CLOSURE_REFUSAL` | the closure gate refusing on the same grounds | at its third cycle |
| `TEST_FAILURES` | `TestRunGate` -- the same failing tests | once persistent by `Recurrence` (ratios over the run) |
| `BOOT` | `BehaviouralSelfCheck` -- the same boot defect | once persistent by `Recurrence` |
| `FLAT_ROOT`, `EXHAUSTION` | the root not moving; subgoals given up without a merge | at once (no question; the operator's) |

Build, write and closure failures belong to one attempt and recur only when a new attempt makes them, so they are
counted in cycles; test and boot failures persist by themselves, so they are judged by `Recurrence` (a share of
observations since the first sighting, spanning a share of the run).

The questions, each answered by an authority, structure first:

- **BUILD**: what the code declares for the name the failure is about (`StuckQuestions`: members, constructors,
  overloads, where a symbol is declared -- in the sources, the JDK and the project's dependency jars through
  `ProjectTypeAuthority`; a class looked for in a package that does not declare it is answered with where it is,
  or the package's classes that look like it); what the failing line constructs; the declaring file whole; and
  last, an **observation** -- `TerminalAgent`, which runs read-only commands in a copy of the tree (`ReadOnlyCopy`:
  an allow-list, no shell, compile but never execute; exchanges recorded by `TerminalAgentRecorder`).
- **TEST_FAILURES**: **what each failing test's call executed** (`ExecutionTrace`): every failing test the
  observation names is run again in its own copy of the tree under the JDK debugger (JDI), and every call into the
  project's own `src/main/java` types is reported with its arguments, its result, the line it returned at and the
  values in scope there. The traces run **side by side**: `TraceCapacity` allows as many at once as half the memory
  available at that moment holds, by what one trace was measured to take (the first trace of a run goes alone and
  is measured). Each trace is bounded by a multiple of the project's own last suite run
  (`TestRunGate.lastSuiteMs`), and by `autobuilder.stuck.execution-trace.budget-ms` until the suite has run. Maven
  projects only; switch `autobuilder.stuck.execution-trace.enabled`.
- **PROTECTED_WRITE**, **CLOSURE_REFUSAL**: what the protected file says; which requirement keeps closure refused.

A signature that comes back after its answer moves to the next question. When no question is left, the layer
asks the operator for a **diagnosis** (a `diagnosis_request` event and log line, with every question asked and its
answer). The loop itself never acts on a model's reading of a stuck case: every move it takes comes from a fact.

**Across runs** (`StuckPriors`, `Neo4jStuckPriors`): how each signature ended is kept in the graph under the task
(the SHA-256 of the seed); a signature that earlier runs of the same task noticed is asked about at its first
sighting here, starting with the question that ended it there.

---

## 12. The model client

All model calls go through `LLMClient` (`client` package), one chokepoint:

- **Budgets.** `PromptTokenBudget` -- the deployed context window minus the response reserve -- bounds every prompt
  block; a block that does not fit is left out whole and counted, never cut.
- **Output bounds** (`OutputBounds`). Every call carries `max_tokens`, learned per role: it starts at the response
  reserve, grows by half a reserve when a clean answer was cut by it, and halves after a *runaway* -- an answer
  cut by the cap whose last 60 lines are at least 80% lines it already wrote -- but never below the reserve nor
  below the role's longest answer that ended by itself, and never above the room the prompt leaves in the
  context. An answer that filled the context is asked again for less. A runaway is never retried identically (at
  temperature 0 it would repeat): it is retried with a frequency penalty, then at temperature 0.4, keeping the cap
  it ran into. The read timeout follows the cap at the run's observed pace. Switch:
  `llm.api.output-bounds.enabled`.
- **The circuit breaker** (`LlmCircuitBreaker`). Repeated failures open it for an escalating cool-down; while open,
  calls fail fast instead of piling onto a saturated endpoint.
- **Record and replay** (`LlmExchangeRecorder`). A run's answers can be recorded and replayed by prompt digest,
  so a recorded state can be re-run without the model.
- `LlmCallContext` carries the calling role and cycle into the trace; every call is an `llm_call` event with its
  prompt and completion tokens and its duration.

---

## 13. The graph, the trace and what a run keeps

Neo4j (`spring.data.neo4j.*`) holds the orchestration history: each agent's actions and their artifacts, goals,
evolution attempts, evaluation results with every heuristic's opinion and every criterion's result, merges,
deferred mutations and their blockers, checkpoints, stuck outcomes, terminal-agent exchanges. Every node carries
`runId` and `lineageId`. Reads that span runs are declared with `@HistoryRead` (how far back they read, and whether
the answer may decide anything alone); a contract test fails the build for an undeclared read.
`OrchestrationSnapshotService` renders a structured snapshot of recent graph state; `DiagnosticScanService` scans
the graph for known failure patterns; `GraphQueryService` answers the explorer's queries.

`BehaviourLogger` writes every behaviour event (`BehaviourEvent`, one record type per event) to
`logs/behaviour.ndjson` and streams it on `GET /events/behaviour`. Readouts summarise a run's shape as it goes:
`CriteriaExecutabilityReadout` (how much of the run is verifiable at all), `SignalLifetimeTracker`.
`scripts/run_profile.py` compares a finished run's shape with healthy runs from a curated baseline.

`RetentionPolicy` prunes logs, snapshots, mutations and responses under the target by age and size, always keeping
the most recent entries, and never while a cycle runs (`RetentionConfigProperties`).

---

## 14. The REST surface and the browser pages

`AutobuilderController` (`/autobuilder`): `status`, `start-state`, `targets`, `cold-read`, `usage`, `checkpoints`
(GET); `start`, `goal`, `cycle`, `evolve`, `pause`, `resume`, `rewind`, `reset`, `bootstrap`, `coverage-review`
(POST); `PUT /autobuilder/signals/{name}` and `DELETE /autobuilder/signals/{name}` (the operator's poke, section
2). `GoalExplorerController` (`/autobuilder/goals/...`): the goal tree and its live stream, a goal's criteria,
cycles and synergies; editing criteria (add, change, delete, mark met), pinning, injecting a subgoal, forcing a
goal given up, reactivating it. `GraphExplorerController` (`/autobuilder/graph/...`): overview, nodes, neighbours,
a goal's activity. `BehaviourEventController` and `EventController` (`/events/...`): live and recent events.
`FileController` (`/files/...`): reading, listing, writing and uploading files of the target.
`TtsController` (`/tts/synthesize`, through `TtsService`): narration, spoken in-process.

Pages under `src/main/resources/static`: `start.html` (choosing a start mode and a target), `goals.html` (the goal
tree, live, narrated), `graph.html` and `graph-explorer.html` (the graph). Swagger UI: `/swagger-ui/index.html`.

---

## 15. Profiles and configuration

| Profile | Bootstrap | Scheduler | Notes |
|---|---|---|---|
| (default) | off | off | |
| `test` | off | off | for the test suite |
| `dev-local` | on | off | cycles on request; obligations generated |
| `dev-auto` | on | on | the autonomous loop |
| `fast` | -- | -- | combine with another profile; only trims waiting (`inter-cycle-delay-ms: 1`) |

Every configuration key the code reads, with its default and the class reading it, is listed in
[configuration.md](configuration.md), generated from the code by `scripts/doc_config_reference.py`.

---

## 16. Tests and the measurement apparatus

- **Unit tests** use JUnit 5, Mockito and AssertJ without a Spring context; tests that need Neo4j use
  Testcontainers. `mvn test` runs the suite; `-Dtest=Class#method` one test. Tests that start real processes (a
  debugger trace, for example) run only with their switch (`-Dexecution.trace.live=true`).
- **Toy cases** are versioned targets under `data/<name>/bootstrap/` (`scripts/toycase_version.py`), each with a
  yardstick script that scores a run's artifact against its specification without trusting the loop's own
  verdict (the last line `SMOKE: <passed>/<total>`). A score is a chain of checks, not a set of independent
  measurements: compare the failing check sets, never only the totals.
- **The ticket ladder** (T6-T): toy cases cut from Autobuilder's own history. `scripts/ladder_harvest.py` lists past
  commits by how many classes they changed (the bands); `scripts/ladder_case.py` turns one into a case -- the
  changed classes and everything they reach, as they were before the commit, the test helpers their tests reach,
  the commit's own tests as the specification, and the commit message as the ticket. `scripts/prepare_module_change.sh`
  prepares its pristine tree and `scripts/smoke_ladder_case.sh` scores it.
- **Runs** are driven by `scripts/preflight.sh` (build, launch, archive, score) and cut early by
  `scripts/early_stop.sh` when the score stops moving; every run is archived with its logs, goal state, artifact and
  summary. `scripts/post_adopted_goal.sh` posts an adopted target's goal.
- **Replay** instead of new runs where possible: a recorded run's model answers can be replayed
  (`LlmExchangeRecorder`), a run's tree frozen and a cycle's input replayed (`scripts/freeze_scenario.sh`), and the
  archive re-read for one decision (for example `scripts/test_delta_replay.py`, `scripts/closure_replay_prep.py`).
- `scripts/doc_check.py` checks that every class, configuration key, script and endpoint these documents name
  still exists.

---

## 17. Design rules the code follows

These are why the mechanisms above look the way they do.

- **A fact before a reading.** Where a record, a structure query or a measurement can answer a question, it answers
  it, and its answer stands over a model's reading either way (`TestRunClaims`, `UnchangedClaims`, decisive gate
  acceptance, the stuck layer's authorities).
- **Derive, do not store, what can be derived.** Verdicts that can go stale are computed where they are read
  (`DerivedFacts`); what is stored is history -- what was tried, what happened, how it ended.
- **Relative thresholds.** Limits come from the run's own environment -- shares of the run (`Recurrence`), the
  run's own rate (`ConvergenceTrend`), the suite's own duration, the memory available now -- not from constants.
- **One invariant, one home.** "Stuck", "duplicate goal", "protected", "what a type declares" each have one class
  every consumer asks.
- **Discovery before tables.** Ecosystem knowledge comes from the project's own build and classpath first; tables
  are a fallback, and framework names stay out of framework code.
- **Local model.** Everything is built for a local model: timeouts, output bounds and prompt budgets adapt to the
  endpoint that is actually deployed.
