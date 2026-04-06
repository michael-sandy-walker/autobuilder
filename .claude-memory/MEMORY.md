# Autobuilder — Persistent Memory

## Project
Spring Boot 3.3 / Java 21 autonomous self-improvement loop. Evolves a target codebase (under `file.base-path`) toward a stated goal using LLMs. Neo4j for graph history; H2 for JPA. OpenAI API.

## Key facts
- Build: `mvn clean install` | Test: `mvn test -Dtest=ClassName#method`
- Neo4j required for graph-backed features and integration tests
- Goal stack: `meta/GoalStack.json` (relative to `file.base-path`, default `/data/firestarter`)
- Active Spring profiles: `test` (no scheduler/bootstrap), `dev-local` (bootstrap, no scheduler), `dev-auto` (full autonomous)

## Architecture in one line
`AutobuilderService` (60s scheduler) → `CycleExecutor` → `AdaptiveExecutionEngine` (signal-driven, fitness-ranked) → 5 agents (planner / evolution / evaluation / merge / analysis) → `GoalPlannerService` (goal tree) + Neo4j graph

## Roadmap
See `memory/roadmap.md` for the full prioritized backlog and status.
See `memory/roadmap_log_findings_2026-03-24.md` for findings from live session analysis (2026-03-24).

**Ticket ID convention (as of 2026-04-02):** prefix T1/T2/T3/T4/T5 = tier number. T1=Correctness, T2=Steering quality, T3=Feature completion, T4=New systems, T5=Quality/observability.

- `FILE_BASE_PATH=firestarter` must be set when running tests (e.g. `FILE_BASE_PATH=firestarter mvn test`).

## Feedback
- See `memory/feedback_curly_quotes.md`: fix curly/smart quotes in Java immediately with sed, no confirmation needed.
- See `memory/feedback_run_without_asking.md`: run `mvn` and other build/test commands with dangerouslyDisableSandbox — never prompt for approval.
- See `memory/feedback_no_auto_commit.md`: NEVER commit automatically — always wait for explicit user instruction.
