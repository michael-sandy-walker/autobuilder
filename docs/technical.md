# Autobuilder -- technical documentation

How Autobuilder is built: the cycle that drives it, the agents and the contracts they answer in, every check a
change passes on its way to being merged, how the loop notices it is stuck and what it asks then, how it talks
to the model, and what it keeps. Each part names the class or configuration key it describes, so it can be
checked against the code (`scripts/doc_check.py`) and read next to it.

For what Autobuilder does and how to use it on a project of your own, see [functional.md](functional.md). The
generated configuration reference is [configuration.md](configuration.md).

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
| `bootstrap/seed.obligations.txt` | the specification split into checkable obligations (see section 6) |
| `bootstrap/protected-paths.txt` | paths the loop may not write, and declarations it may not change |
| `output/` | the project being built or changed -- the only place the loop writes code |
| `meta/` | the loop's own state: `GoalStack.json`, `run-identity.json`, `stuck-ledger.json`, the adoption manifest |
| `mutations/`, `snapshots/`, `checkpoints/`, `checkpoint-archive/` | candidate changes and tree copies for rollback and rewind |
| `logs/` | `autobuilder.log` and `behaviour.ndjson`, the structured event trace |

Packages, by responsibility:

| Package | Responsibility |
|---|---|
| `core.service`, `core.lifecycle`, `core.engine`, `core.deliberation` | the cycle: scheduling, the context it carries, which agent runs |
| `agent.*` | the five agents and their model adapters |
| `protocol.*` | what each agent is asked (prompt contracts) and how its answer is read (parsers) |
| `goal` | the goal tree, the specification's obligations, closure |
| `core.build` | everything that builds, runs or reads the target's code: probes, gates, guards, structural questions |
| `core.stuck` | the stuck layer: one home for "the loop is not getting anywhere" |
| `analysis.*` | heuristics, diff, structure and problem analysis |
| `client` | the model client: budgets, output bounds, the circuit breaker, record/replay |
| `graph.*` | Neo4j: every agent's actions and results, run and lineage identity |
| `core.checkpoint`, `core.merge`, `core.persistence` | snapshots, rollback, rewind, end-of-run reports |
| `observability`, `controller` | the behaviour trace, the REST surface, the browser pages, narration |

---

## 2. The cycle

`AutobuilderService` is the entry point. With `autobuilder.scheduler.enabled` it runs a cycle, waits
`autobuilder.scheduler.inter-cycle-delay-ms`, and runs the next; without it, cycles run on request
(`POST /autobuilder/cycle`, `POST /autobuilder/evolve`). Before the first cycle, `StartModeService` decides
whether the run may begin at all (section 5).

`CycleExecutor` prepares a cycle: it builds the `CycleContextData` (the immutable record every agent reads and
returns a changed copy of -- the current goal and subgoal, the live tree, the last build outcome, the open
requests, the participation windows), and runs the post-cycle steps. `AdaptiveExecutionEngine` runs the cycle
itself, as an explicit selection pass:

1. **Halt** -- a pause or a terminal fact ends the pass before it starts.
2. **Candidates** -- `DeliberationService` decides which roles may run in this cycle (rule-based, no model call).
3. **Ordering** -- the candidates are ordered by explicit constraint, the urgency of open requests addressed to the
   role, the role's participation window, and base priority; ties go by pipeline order
   (planner, analysis, evolution, evaluation, merge).
4. **Execution** -- eligible agents run in that order until the cycle merges or none is left.

### What replaced "signals"

Earlier versions steered the cycle with stored signals that decayed over time. They were replaced by three
mechanisms that cannot go stale, each in `core.lifecycle`:

- **Derived facts** (`DerivedFacts`) -- predicates computed from the context where they are read, never stored.
- **Requests** (`ChannelRequest` on the `RequestChannel`) -- a message from one mechanism to another, with the
  urgency its issuer stated, consumed on read or expiring visibly after a stated number of cycles.
- **Participation windows** (`ParticipationWindows`) -- one open window per role, with a boost and a horizon,
  aged once per cycle.

---

## 3. The agents

Each agent lives in `agent.<role>`, has a prompt contract in `protocol.<role>` and an answer parser beside it, and
calls the model through `LLMAgentProtocolInvoker` (which re-asks once with the reason when an answer does not have
the required shape).

| Role | Class | What it decides |
|---|---|---|
| planner | `PlannerAgent` | splits the goal into subgoals, each with acceptance criteria |
| analysis | `AnalysisAgent` | reads the project's structure and problems; may propose subgoals |
| evolution | `EvolutionAgent` (`EvolutionLLMAdapter`) | proposes the change for the current subgoal |
| evaluation | `EvaluationAgent` | judges the proposed change against the subgoal's criteria |
| merge | `MergeAgent` | applies an accepted change and records it |

`InterpretationService` reads each cycle's outcome back into the context for the next cycle's prompts.

### What the evolution agent is shown

`EvolutionProtocolContract` builds the prompt; blocks are added in `EvolutionLLMAdapter`, each within the prompt
budget `PromptTokenBudget` computes (`llm.api.context-window-tokens` minus `llm.api.response-reserve-tokens`):

- the source of the files the subgoal and the goal name (`SubgoalSourcePromptBlock`), so the model changes what
  it has read rather than what it remembers;
- the paths and declarations it may not change (`ProtectedPaths`);
- the answers the stuck layer found to failures that kept repeating (section 8);
- **the API of the dependencies the work uses** (`DependencyApi`): the classes of every dependency package the
  tree or the last failed attempt imports (an invented class is shown absent), the public members of every
  imported class, and the packages of a library the goal names -- read from the project's own jars;
- values the code was observed to return, and the parts of a target's briefing the subgoal is about
  (`BriefingSource`).

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

## 4. From a proposed change to a merged one

A change passes these checks, cheapest first; each can stop it.

1. **Protected paths and declarations** (`ProtectedPaths`, `PreservedMembers`). `bootstrap/protected-paths.txt`
   lists globs the loop may not write, and `members: <glob> [except <names>]` lines: a file whose existing
   declarations are kept as they were adopted, whatever a writer puts there -- new declarations are kept beside
   them, and the names after `except` (methods, fields, nested types) may be changed.
2. **The compile probe** (`CompileProbeService`). The change is written to `output/` and the target's build
   command runs (`autobuilder.build.*`). A change that does not compile is rolled back and rejected before any
   model judges it.
3. **Evaluation** (`EvaluationAgent`, `HeuristicNegotiator`). The model scores the change against the subgoal's
   criteria; a set of deterministic heuristics (`analysis.heuristic`) then votes, and a weighted majority can
   overrule the model's verdict. There is no single score threshold: acceptance is the vote.
4. **Merge** (`MergeAgent`, `MutationApplier`). The accepted change stays; it is recorded in the graph. A merge
   that breaks the build afterwards is rolled back (`MergeRollbackService`).

### Gates at the completion edge

When every subgoal is accounted for, the goal may close -- only if these pass, each run once per completion
claim, not per cycle:

- **the package gate** -- the project assembles into a startable artifact;
- **the test gate** (`TestRunGate`) -- the project's own test suite passes; its report is also evidence for the
  evaluator and the stuck layer;
- **the boot gate** (`BootGate`) -- the packaged artifact is started and probed the way its specification says
  (endpoints answered, commands run);
- **criteria verification** (`CriteriaVerificationService`) -- every acceptance criterion is confirmed against the
  current code. Criteria whose claim a record can answer are answered by it, and its answer stands over the
  reading's either way -- met or not met: that the tests pass (`TestRunClaims`, from the test run of this exact tree) and that nothing outside the intended change
  was changed (`UnchangedClaims`, from the `AdoptionManifest` of an adopted project). The planner is given the
  sentence forms `UnchangedClaims` proves (`UnchangedClaims.TEMPLATES`).

`BehaviouralSelfCheck` runs the same kind of check *during* a run, so a run that never reaches the completion
edge is still observed.

---

## 5. Starting a run

`StartMode` names the four ways a run can begin; `autobuilder.start.mode` chooses one and `StartModeService`
checks it against what is actually in `output/` before anything is touched:

| Mode | What it does | Refused when |
|---|---|---|
| `NEW_PROJECT` | clears `output/` and seeds the goal from `bootstrap/seed.txt` | there is no seed; or `output/` holds code the loop did not write (unless `autobuilder.start.confirm-discard-foreign-tree`) |
| `CONTINUE_LINEAGE` | continues a lineage this loop built, restoring its artifact and goal state | `output/` is empty |
| `ADOPT_PROJECT` | works on a codebase as it is: nothing cleared, nothing seeded; the loop waits for a goal (`POST /autobuilder/goal`) | `output/` is empty |
| `ASK` | touches nothing and waits | always -- it is a resting state |

An adopted project gets an `AdoptionManifest` (the digest of every file as adopted), which is what lets
"nothing else was changed" be proven rather than read. `GET /autobuilder/cold-read` (`ColdReadService`) reads an
adopted tree before a goal is given.

Each boot mints a run id and inherits or mints a lineage id (`RunIdentityService`, `meta/run-identity.json`);
every graph write carries both.

---

## 6. Goals and the specification

`GoalPlannerService` owns the goal tree: one root (`GOAL`) and its `SUBGOAL`s, each `ACTIVE`, `COMPLETED` or
`GIVEN_UP`, each subgoal with acceptance criteria. The tree is stored in the graph (`GoalGraphService`) and
mirrored to `meta/GoalStack.json`.

The specification's obligations live in `bootstrap/seed.obligations.txt` (`SeedObligationsFile`): one obligation
per line, generated by the model from the seed when absent (`SeedObligationsGenerator`, enabled by
`autobuilder.obligations.generate`) and used as an input afterwards. Its header carries the seed's hash and the
body's hash, so an operator's hand edit is kept and reported as theirs. Line markers:

- `rule:` -- how the work is done; guides the work, never blocks completion;
- `gate: tests` -- answered by the project's own test run, not by a model reading the source.

Closure asks whether every obligation the seed states is met (`SeedRequirements`); an obligation no criterion
covers is still asked at the completion edge.

---

## 7. The model client

All model calls go through `LLMClient` (`client` package), one chokepoint:

- **Budgets.** `PromptTokenBudget` -- the deployed context window minus the response reserve -- bounds every prompt
  block; a block that does not fit is left out whole and counted, never cut.
- **Output bounds** (`OutputBounds`). Every call carries `max_tokens`, learned per role: it starts at the response
  reserve, grows by half a reserve when a clean answer was cut by it, and halves after a *runaway* -- an answer
  cut by the cap whose last 60 lines are at least 80% lines it already wrote -- but never below the reserve nor
  below the role's longest answer that ended by itself. A runaway is never retried identically (at temperature 0
  it would repeat): it is retried with a frequency penalty, then at temperature 0.4, keeping the cap it ran into.
  The read timeout follows the cap at the run's observed pace. Switch: `llm.api.output-bounds.enabled`.
- **The circuit breaker** (`LlmCircuitBreaker`). Repeated failures open it for an escalating cool-down; while open,
  calls fail fast instead of piling onto a saturated endpoint.
- **Record and replay** (`LlmExchangeRecorder`). A run's answers can be recorded and replayed by prompt digest,
  so a recorded state can be re-run without the model.
- `LlmCallContext` carries the calling role and cycle into the trace; every call is an `llm_call` event.

---

## 8. The stuck layer

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
  an allow-list, no shell, compile but never execute).
- **TEST_FAILURES**: **what the failing test's call executed** (`ExecutionTrace`): the failing test is run again
  in a copy of the tree under the JDK debugger (JDI), and every call into the project's own `src/main/java` types
  is reported with its arguments, its result, the line it returned at and the values in scope there. Maven
  projects only; switch `autobuilder.stuck.execution-trace.enabled`.
- **PROTECTED_WRITE**, **CLOSURE_REFUSAL**: what the protected file says; which requirement keeps closure refused.

A signature that comes back after its answer moves to the next question. When no question is left, the layer
asks the operator for a **diagnosis** (a `diagnosis_request` event and log line, with every question asked and its
answer). The loop itself never acts on a model's reading of a stuck case: every move it takes comes from a fact.

**Across runs** (`StuckPriors`, `Neo4jStuckPriors`): how each signature ended is kept in the graph under the task
(the SHA-256 of the seed); a signature that earlier runs of the same task noticed is asked about at its first
sighting here, starting with the question that ended it there.

---

## 9. The graph and the trace

Neo4j (`spring.data.neo4j.*`) holds the orchestration history: each agent's actions and their artifacts, goals,
evolution attempts, evaluation results with every heuristic's opinion, merges, stuck outcomes, terminal-agent
exchanges. Every node carries `runId` and `lineageId`. Reads that span runs are declared with `@HistoryRead`
(how far back they read, and whether the answer may decide anything alone); a contract test fails the build for
an undeclared read.

`BehaviourLogger` writes every behaviour event (`BehaviourEvent`, one record type per event) to
`logs/behaviour.ndjson` and streams it on `GET /events/behaviour`. `RunEndReportService` writes the end-of-run
report; `scripts/run_profile.py` compares a finished run's shape with healthy runs from a curated baseline.

`CheckpointArchive` keeps a source-only copy of the tree at each checkpoint, so `POST /autobuilder/rewind` can
return to an earlier state.

---

## 10. The REST surface and the browser pages

`AutobuilderController` (`/autobuilder`): `status`, `start-state`, `targets`, `cold-read`, `usage`, `checkpoints`
(GET); `start`, `goal`, `cycle`, `evolve`, `pause`, `resume`, `rewind`, `reset`, `bootstrap`, `coverage-review`
(POST). `GoalExplorerController` (`/autobuilder/goals/...`): the goal tree, criteria and their edits, injected
subgoals, pins. `GraphExplorerController` (`/autobuilder/graph/...`): nodes, neighbours, activity.
`BehaviourEventController` and `EventController` (`/events/...`): live event streams. `FileController`
(`/files/...`): files of the target. `TtsController` (`/tts/synthesize`, through `TtsService`): narration.

Pages under `src/main/resources/static`: `start.html` (choosing a start mode), `goals.html` (the goal tree, live),
`graph.html` and `graph-explorer.html` (the graph). Swagger UI: `/swagger-ui/index.html`.

---

## 11. Profiles and configuration

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

## 12. Tests and the measurement apparatus

- **Unit tests** use JUnit 5, Mockito and AssertJ without a Spring context; tests that need Neo4j use
  Testcontainers. `mvn test` runs the suite; `-Dtest=Class#method` one test.
- **Toy cases** are versioned targets under `data/<name>/bootstrap/` (`scripts/toycase_version.py`), each with a
  yardstick script that scores a run's artifact against its specification without trusting the loop's own
  verdict.
- **Runs** are driven by `scripts/preflight.sh` (build, launch, archive, score) and cut early by
  `scripts/early_stop.sh` when the score stops moving; every run is archived with its logs, goal state, artifact
  and summary.
- `scripts/doc_check.py` checks that every class, configuration key, script and endpoint these documents name
  still exists.
