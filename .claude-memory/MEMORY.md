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
- See `memory/project_introspection_as_north_star.md`: autobuilder's ultimate goal is a loop with introspective capability — a self-model it consults before acting. Current goal-evolution task is a tractable proxy; existing tickets (T4-E, T4-I, T4-A, T4-J, T2-DY, T1-EX, …) are fragments of this. Use as the lens when triaging tickets ("substrate or patch?").
- See `memory/project_three_disciplines_lens.md`: frame for evaluating autobuilder progress — failures→priors, capabilities-replace-scar-tissue, delete-subsumed.
- See `memory/project_delete_debt_pattern.md`: caller-migration + delete work gets filed as T5 follow-ups and never reaches top of queue; scaffolding accumulates by mechanism, not by neglect.
- See `memory/project_coverage_guard_experiment.md`: runbook for the `autobuilder.evolution.coverage-guard.enabled=false` falsifiable experiment shipped 2026-05-28 — metrics, decision rule, branching actions. Run the comparison before committing to T4-BN or to guard deletion.
- See `memory/project_log_and_backup_conventions.md`: where logs live, why there's no "current" file, why same log appears in many backup dirs, behaviour-ndjson is pretty-printed multi-line JSON.
- See `memory/project_goal_fulfilled_is_real_happy_path.md`: GOAL_FULFILLED fires ~27 times in the 5-week corpus; use length-matched fulfilled runs as yardsticks (notably 2026-05-21_23-08-50 at 51 cycles).

## Feedback
- See `memory/feedback_curly_quotes.md`: fix curly/smart quotes in Java immediately with sed, no confirmation needed.
- See `memory/feedback_run_without_asking.md`: run `mvn` and other build/test commands with dangerouslyDisableSandbox — never prompt for approval.
- See `memory/feedback_no_auto_commit.md`: NEVER commit automatically — always wait for explicit user instruction.
- See `memory/feedback_tts_marytts.md`: for autobuilder narration, prefer in-JVM neural TTS (no separate server); MaryTTS was rejected as too old/inconvenient.
- See `memory/feedback_run_analysis_log_order.md`: analyse last run from `behaviour-*.ndjson` first; fall back to `autobuilder-*.log` only for deeper detail.
- See `memory/feedback_analyse_no_ask.md`: when asked to analyse the last run, proceed directly — no confirmation or clarification needed.
- See `memory/feedback_ticket_tiering.md`: tier tickets by mechanism — policy-behaving-as-designed-but-dumb = T2, missing capability = T4, only real logic bugs = T1.
- See `memory/feedback_ship_full_scope.md`: ship a capability in one scope — build + rewire every caller + delete dead code; never split migration or delete into a Tier 5 follow-up. *(Renamed 2026-05-27 from `feedback_capability_ticket_includes_delete`.)*
- See `memory/feedback_one_invariant_one_home.md`: when two patches would catch the same invariant at two enforcement sites, that's the symptom of a missing capability — file one capability ticket, not two patches.
- See `memory/feedback_no_target_domain_in_framework_strings.md`: never bake target-project class names (`BookService`, `MemberController`, `LoanService`, …) or package prefixes (`com.example.demo`) into framework-level production strings — hints, prompt blocks, error messages. Tests and roadmap narratives only.
- See `memory/feedback_no_ecosystem_jargon_in_framework_code.md`: never name a specific framework/version (`SpringBoot2`, `javax`, `jakarta`, `Mockito`, `JUnit5`) in autobuilder framework-level identifiers, comments, or strings. Ecosystem knowledge lives in data tables; framework code is abstract.
- See `memory/feedback_no_hardcoded_ecosystem_data.md`: even the *data tables* are debt — hardcoded package/GAV/version literals freeze a worldview the loop has no path to correct. Prefer discovery (project pom → classpath → Maven Central → recipes). Short-term row-adds always file the discovery follow-up.
- See `memory/feedback_verify_population_before_claim.md`: before claiming "all runs / most / never / almost-never," scan the actual corpus (including `backup/*/logs/` and `.log.gz`) and dedupe by base timestamp. Generalising from a 14-log sample collapsed 3× in one session.
- See `memory/feedback_no_defensive_recast.md`: after a correction, do not pivot to a fresh confident framing under a new banner. Pause and gather more data before generating the next claim.
- See `memory/feedback_neutral_vocabulary.md`: avoid courtroom (verdict, interrogate, judge, critic-as-reviewer, adversarial) and military/action (blast radius, tactical, kill-switch) wording project-wide; use plain descriptive words. `CRITICAL` confidence level is unrelated and stays.
