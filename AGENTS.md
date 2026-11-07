# AGENTS.md

This file provides guidance to Codex (claude.ai/code) when working with code in this repository.

For people: [docs/technical.md](docs/technical.md) and [docs/functional.md](docs/functional.md).

## Commands

```bash
# Build
mvn clean install

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=ClassName

# Run a single test method
mvn test -Dtest=ClassName#methodName

# Generate PlantUML class diagram
mvn de.elnarion.maven:plantuml-generator-maven-plugin:3.0.1:generate
# Render: plantuml target/plantuml/autobuilder-class-diagram.puml

# Start Neo4j (required for graph-backed features and integration tests)
docker run -d --name neo4j -p7474:7474 -p7687:7687 -e NEO4J_AUTH=neo4j/your_password neo4j:5

# Start full stack (app + Neo4j)
docker-compose up
```

**Startup (T4-ED):** `java -jar` with no project opens the **home context** on `http://localhost:8080` (`autobuilder.home.port`): `GET /home/state`, `GET /home/projects`, `POST /home/start {"project","mode","profiles"}`. A project named at startup (`FILE_BASE_PATH` or `--file.base-path`) fast-forwards: the same launcher starts the **run context** at once. There is no default project.

**REST endpoints of the run context** (running at `http://localhost:8081`):
- `POST /autobuilder/evolve` — trigger a single cycle manually
- `GET /autobuilder/status` — current cycle state
- Swagger UI: `http://localhost:8081/swagger-ui/index.html`

## Architecture Overview

Autobuilder is an **autonomous self-improvement loop**: it reads a target project from `file.base-path` (no default -- unset opens the home screen; targets live under `data/<name>/`, each owning a tracked `bootstrap/seed.txt` and nothing else — configured via `FILE_BASE_PATH`, or `PREFLIGHT_TARGET=<name>` for runs) and iteratively evolves it using LLMs toward a stated goal.

### Control Loop

`AutobuilderService` (scheduled; `autobuilder.scheduler.inter-cycle-delay-ms` between cycles) → `CycleExecutor.runCycle()` → `AdaptiveExecutionEngine.runCycle()`

Each cycle:
1. **Deliberation** — `DeliberationService` derives a `DeliberationMode` and signal adjustments (rule-based, no LLM call)
2. **Signal-driven execution** — rank agents by fitness score (signal strength × recency + base priority + eval bias + jitter), execute eligible agents in order
3. **Post-cycle hooks** — scope guard, pattern analysis, merge on accepted mutation, score recording, interpretation, signal decay

The cycle ends when `context.merged()` is true or all signals decay below `SIGNAL_SURVIVAL_THRESHOLD = 0.05`.

### Signal System

`Signal` objects (`strength`, `decayPerCycle`, `age`, `source`) live on `CycleContextData`. They drive agent prioritization. Key signal types defined in `BasicSignals`:
- `ROLE_PLANNER / ROLE_EVOLUTION / ROLE_EVALUATION / ROLE_MERGE / ROLE_ANALYSIS` — agent identity signals
- `EVOLUTION_OCCURRED` — set when an evolution produced a diff
- `REPLAN_REQUESTED` — triggers planner priority
- `SCOPE_RESULT_OUT_OF_SCOPE / SUBGOAL_SCOPE_MISMATCH` — suppresses evaluation/merge/completion in scope-mismatch situations
- `GOAL_EXHAUSTED / SYSTEM_PAUSED` — halt conditions

### Agents (roles)

Each agent implements `ExecutionAgent` and lives under `agent.<role>`:

| Role | Class | Responsibility |
|---|---|---|
| `planner` | `PlannerAgent` | Decomposes the current goal into ordered subgoals via LLM |
| `evolution` | `EvolutionAgent` | Proposes file mutations (`FileMutation`) toward the current subgoal |
| `evaluation` | `EvaluationAgent` | Scores the mutation (LLM + heuristics), sets `MutationAcceptanceState` |
| `merge` | `MergeAgent` | Applies accepted mutations to the filesystem and records them in the graph |
| `analysis` | `AnalysisAgent` | Analyzes project structure and problems, can propose new executable subgoals |

Each agent has a corresponding **protocol contract** (`protocol.<role>.*ProtocolContract` — prompt builder) and **response parser** (`*ResponseParser`). LLM calls go through `LLMAgentProtocolInvoker` → `LLMClient` (OpenAI REST). Planner and analysis prompts carry `SystemCapabilityManifest`, built from the switches of the checks that are on. On a retry the evolution agent may first ask one question of its run (`core.investigate`: `LogConsultation` → `LogInvestigator`, which probes the run's logs and the current project files step by step).

### Goal System

`GoalPlannerService` manages a hierarchical goal tree persisted in two places:
- **Primary**: Neo4j graph (via `GoalGraphService`)
- **Derived snapshot**: `meta/GoalStack.json` under `file.base-path`

Goals have type `GOAL` (root) or `SUBGOAL`, and status `ACTIVE / COMPLETED / GIVEN_UP`. `currentSubGoalId` tracks which leaf the system is currently working on. Subgoal promotion (`promoteCurrentSubGoal`) advances to the next sibling or ancestor when a subgoal is fulfilled.

Config toggles in `application.yml`:
- `autobuilder.goal.sync-from-graph: true` — load goal state from Neo4j on startup (production default)
- `autobuilder.goal.migrate-to-graph: true` — push JSON state to Neo4j (used in tests)

### Heuristics and Evaluation

After the LLM evaluation, `HeuristicNegotiator` runs a set of `EvaluationHeuristic` implementations to potentially override the LLM score/state via weighted majority vote. Key heuristics:
- `ScoreRegressionHeuristic` — penalizes score drops
- `ProblemReductionHeuristic` — rewards reduction in open problems
- `NoMeaningfulChangeHeuristic` — rejects mutations with no structural diff
- `DeterministicScoreHeuristic` — overrides with the deterministic heuristic score when confidence is high

Score strategy is configurable (`autobuilder.heuristic.score-strategy`): `weightedAverageScoreAdjustmentStrategy` (default), `maxConfidence`, or `noOp`.

### Graph Layer

Neo4j stores the full orchestration history: goals, evolution attempts, evaluation scores, planner actions, merge records. Each agent has a corresponding `*GraphFacade` / `*ActionRegistrar` pair. `OrchestrationSnapshotService` produces a structured snapshot of recent graph state used as context for the meta-deliberation agent.

### Spring Profiles

| Profile | Bootstrap | Scheduler |
|---|---|---|
| (default) | disabled | disabled |
| `test` | disabled | disabled |
| `dev-local` | enabled | disabled |
| `dev-auto` | enabled | enabled (autonomous) |

### Key Configuration (`application.yml`)

- `file.base-path` — root of the target project being evolved
- `openai.api.key` / `openai.api.model` — LLM credentials
- `spring.data.neo4j.*` — Neo4j connection
- `autobuilder.evaluation.fileContentBudgetChars` — per-file character budget for the evaluation prompt (T2-FI: the 150-line cap it replaced was in practice a 4000-character one, so the model scored mutations from roughly the first hundred lines of a changed file). Acceptance itself is a weighted heuristic vote in `HeuristicNegotiator` (confidence >= 0.6, margin >= 0.15) — there is no score threshold knob
- `autobuilder.diff.exclude-folders/exclude-files` — paths excluded from diff analysis

### Test Infrastructure

- Unit tests use Mockito + AssertJ; no Spring context for pure unit tests
- Integration tests requiring Neo4j use Testcontainers (`@Testcontainers` + `testcontainers-neo4j`)
- `application-test.yml` disables bootstrap and scheduler; sets `file.base-path: ./test-root`
- The `test-root/` directory contains fixture files for filesystem-based tests
