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
- `memory/roadmap-overview.md` — thin active-queue index (one-liner per open ticket, grouped by tier + active chain). Start here for triage.
- `memory/roadmap.md` — full backlog and ticket bodies (source of truth).

**Ticket ID convention (as of 2026-04-02):** prefix T1/T2/T3/T4/T5 = tier number. T1=Correctness, T2=Steering quality, T3=Feature completion, T4=New systems, T5=Quality/observability.

- `FILE_BASE_PATH=firestarter` must be set when running tests (e.g. `FILE_BASE_PATH=firestarter mvn test`).

## Project insights
- See `memory/project_three_disciplines_lens.md`: frame for evaluating autobuilder progress — failures→priors, capabilities-replace-scar-tissue, delete-subsumed.
- See `memory/project_delete_debt_pattern.md`: caller-migration + delete work gets filed as T5 follow-ups and never reaches top of queue; scaffolding accumulates by mechanism, not by neglect.

## Feedback
- See `memory/feedback_curly_quotes.md`: fix curly/smart quotes in Java immediately with sed, no confirmation needed.
- See `memory/feedback_run_without_asking.md`: run `mvn` and other build/test commands with dangerouslyDisableSandbox — never prompt for approval.
- See `memory/feedback_no_auto_commit.md`: NEVER commit automatically — always wait for explicit user instruction.
- See `memory/feedback_tts_marytts.md`: for autobuilder narration, prefer in-JVM neural TTS (no separate server); MaryTTS was rejected as too old/inconvenient.
- See `memory/feedback_run_analysis_log_order.md`: analyse last run from `behaviour-*.ndjson` first; fall back to `autobuilder-*.log` only for deeper detail.
- See `memory/feedback_analyse_no_ask.md`: when asked to analyse the last run, proceed directly — no confirmation or clarification needed.
- See `memory/feedback_ticket_tiering.md`: tier tickets by mechanism — policy-behaving-as-designed-but-dumb = T2, missing capability = T4, only real logic bugs = T1.
- See `memory/feedback_capability_ticket_includes_delete.md`: capability tickets must rewire existing callers + delete the now-dead code in the same scope; never split migration or delete into a Tier 5 follow-up.
