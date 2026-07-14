# Autobuilder — Persistent Memory

## Project
Spring Boot 3.3 / Java 21 autonomous self-improvement loop. Evolves a target codebase (under `file.base-path`) toward a stated goal using LLMs. Neo4j for graph history; H2 for JPA. OpenAI API.

## Key facts
- Build: `mvn clean install` | Test: `mvn test -Dtest=ClassName#method`
- `FILE_BASE_PATH=firestarter` must be set when running tests
- Neo4j required for graph-backed features and integration tests
- Goal stack: `meta/GoalStack.json` (relative to `file.base-path`, default `/data/firestarter`)
- Spring profiles: `test` (no scheduler/bootstrap), `dev-local` (bootstrap, no scheduler), `dev-auto` (full autonomous); add `fast` for ~10-15% faster runs
- **`.claude-memory/` IS the loading memory** — `git checkout`/branch/cherry-pick that touches it mutates live memory; commit memory on the target branch before switching
- Ticket IDs: T1=Correctness, T2=Steering, T3=Feature completion, T4=New systems, T5=Quality/observability

## Architecture in one line
`AutobuilderService` (60s scheduler) → `CycleExecutor` → `AdaptiveExecutionEngine` (signal-driven, fitness-ranked — sole orchestrator since the 2026-07-09 T4-AI-2+5 meta-lane deletion) → 5 agents (planner / evolution / evaluation / merge / analysis) → `GoalPlannerService` (goal tree) + Neo4j graph

## Roadmap
- `memory/roadmap-overview.md` — thin active-queue index; start here for triage.
- `memory/roadmap.md` — full backlog and ticket bodies (source of truth).

## Project insights
- `project_resume_state.md`: **START HERE** — 2026-07-13: 7a1fda2 stuck-signal guard REFUTED by corpus replay (trigger 0× in 20 runs; branch unmerged); per-change batch gating dropped (user steer); then the corrected anatomy's floor moves SHIPPED (suite 1944/0): T4-CA preserve-best-artifact-at-halt + T5-BE honest-halt classification + tail metric. T4-CB shipped + field-validated same day (POST probe blocked a live completion claim) and CLOSED. Session closed 2026-07-14: finishing tier UNPARKED — next session starts at the roadmap-overview QUEUE HEAD (T4-BM remainder first). Full history in dated blocks.
- `project_reliability_plan.md`: RATIONALE note behind the 2026-07-12–14 reliability work (floor up, ceiling free; batch gating dropped for corpus replay). NOT a queue — the roadmap decides order; everything it selected is now filed/closed as T4-CA/T5-BE/T4-CB.
- `project_t4j_design_note.md`: T4-J = OPEN signal-system audit (which signals should decay vs not, obsolete vs missing; calibration LAST), Q1-Q7 + layered cheap-first experiment stack. Read before any signal-tuning/deletion work.
- `project_signal_consumer_map.md`: T4-J Phases 0+1 done (2026-07-09) — 35-type emitter/clearer/reader map + `scripts/signal_boundary_report.py`. COMPILE_DIAGNOSTIC_FED_BACK has zero consumers; 3 signals fully dead; EVOLUTION_OCCURRED most load-bearing (462/1163 ablation flips); calibration shortlist = MISSING_SYMBOL, PLANNING_THROTTLED, ROLE_MERGE. The LLM is itself a consumer AND emitter (analysis renders all signal names; interpretation renders the full map and emits 3 types via parser; defect FIXED 2026-07-09: analysis SIGNALS block now renders NAME=strength pairs).
- `project_t4bz_design_note.md`: **T4-BZ plan (2026-07-10)** — signal re-architecture: family map (5 attention windows / 9 derived views / 10 requests / 2 staleness / 2 switches) + readout; 6 slices, readout extraction FIRST, deletion LAST; equivalence via the T4-J instruments; invariants I1-I12. READ BEFORE any T4-BZ slice.
- `project_signal_dispositions.md`: **T4-J answer sheet (2026-07-09)** — decided disposition per signal: 5 deleted, 2 operator switches documented, 3 markers rebranded truthful, 25 leave; census calibration shortlist DISSOLVED (diagnostic decays inert; ROLE_ANALYSIS/ROLE_PLANNER decay = the real Q7 surface, flagged for live confirmation). Open: analysis/planner tie-break, evaluation-prompt signal-blindness.
- `project_t4j_perturbation_lab.md`: T4-J Phase 3 done (2026-07-09) — frozen-state lab (`FrozenCycleObserver`, fidelity 12/14). Planner/analysis fitness often EXACTLY tied (saturation + shared set members) → Phase-0 coin-flips are exact ties; PLANNING_THROTTLED blocks planner while boosting its fitness; decay is single-cycle-inert (Q7 decay needs 3d replay); eligibility, not rank, often picks the executor. 3c prompt-diff: interpretation prompt sees everything (decay's only same-cycle consumer), evaluation prompt is signal-blind. 3d replay over ALL signals: the decision-sensitive decay constants are the ROLE-identity signals (ROLE_ANALYSIS everywhere, ROLE_PLANNER where eligible), NOT the census shortlist — MISSING_SYMBOL decay is an inert plateau [0.0,0.25]; reinforce() ratchets decay downward (min), so re-emitted signals' decay resists state perturbation. Read before Phase 4/5 or any signal tuning.
- `project_meta_concrete_target_hint_idea.md`: historical NOTE — meta's per-step request text may have reduced #NoDiff attempts; mechanism deleted with T4-AI-2+5; read only if reintroducing a concrete-target hint via a non-LLM path.
- `project_first_perfect_smoke_score.md`: smoke-score yardstick — 23/23 once (run 24, 2026-07-07), 22/23 twice; ceiling sightings, not proof false-completions are solved.
- `project_completion_binding_constraint.md`: RESOLVED 2026-06-24 — T4-BN-2 test-compile flip was the bisect-confirmed regression; constraint now planner-coverage/convergence + residual env/variance (two-factor). Earlier finding: binding constraint was keeping a large main+test build compiling, not model naming.
- `project_t4ai_design_note.md`: T4-AI design — meta lane was decorative/duplicate; closed by deletion 2026-07-09. Read before touching AdaptiveExecutionEngine/DeliberationService.
- `project_t4bo_design_note.md`: T4-BO signal-decay observability design — two-snapshot death attribution, causality-preserving snapshot point. Read before touching signal plumbing.
- `project_t2ei_design_note.md`: T2-EI structure-first goal closure design (2026-06-24). Read before implementing T2-EI.
- `project_t2ei_rule_interaction_map.md`: T2-EI planner-rule map — FULL_COVERAGE + coverage gate are one invariant; depth-first must move enforcement to the empty-backlog edge. Read before touching any planner rule.
- `project_false_completion_unmasked.md`: 2026-06-16 — first false completion; a correct fix (T1-FA) unmasked verifier stub-blindness. Re-validate outcomes after correct fixes.
- `project_false_completion_2.md`: 2026-07-02 — second false completion (unbootable jar); abstain≠pass at the completion edge; a gate can be satisfied instead of its requirement.
- `project_false_completion_3.md`: 2026-07-04 — third false completion (DI-wiring defect invisible to compile gates; correct REJECTED vote averaged away).
- `project_local_first_constraint.md`: local execution is a HARD constraint — hosted-model upgrade off the table; attack the precision ceiling via better LOCAL model or mechanism.
- `project_completion_edge_gates.md`: completion edge = coverage → package → test gate sequence; every diagnosis needs an actuator; brakes must count every evidence class.
- `project_grounding_night_insight.md`: prefer belief→verification moves in triage; the evidence/verify-before-celebrate loop shape recurs at every scale.
- `project_introspection_as_north_star.md`: the ultimate goal is a loop with a self-model it consults before acting; use as triage lens ("substrate or patch?").
- `project_three_disciplines_lens.md`: failures→priors, capabilities-replace-scar-tissue, delete-subsumed.
- `project_agent_diagnosis_not_shared.md`: evaluator rejection reasons were discarded → T4-BX shipped 2026-07-06 as verbatim prose relay into evolution/planner prompts.
- `project_t4bq_naming_consistency_ab.md`: naming-consistency A/B stood down (0/6); ≥3-pair minimum before claims; don't name a replacement lever without critical-path evidence.
- `project_delete_debt_pattern.md`: caller-migration/delete work filed as T5 follow-ups never reaches the queue top; scaffolding accumulates by mechanism.
- `project_coverage_guard_experiment.md`: runbook pattern for falsifiable off-switch experiments — metrics, decision rule, branching actions, stated in advance.
- `project_log_and_backup_conventions.md`: **current run = UNSUFFIXED `logs/behaviour.ndjson`/`autobuilder.log`** — a `behaviour-*.ndjson` glob silently excludes it; behaviour files are pretty-printed concatenated JSON, not line-JSON.
- `project_tests_rotate_live_logs.md`: mvn tests rotate a live run's logs; stop the app before testing, re-arm monitors after.
- `project_run_lifecycle_runbook.md`: start/stop/monitor runs safely — pause keeps JVM on port 8081 (kill before relaunch), pgrep self-match trap, FILE_BASE_PATH, watcher patterns.
- `project_goal_fulfilled_is_real_happy_path.md`: GOAL_FULFILLED fires routinely (~27x in 5 weeks); use length-matched fulfilled runs as yardsticks.

## Feedback
- `feedback_no_per_run_memory_files.md`: no new memory file per field run — fold into `project_resume_state.md`; separate files only for durable cross-run insights.
- `feedback_curly_quotes.md`: fix curly/smart quotes in Java immediately with sed, no confirmation.
- `feedback_run_without_asking.md`: run mvn/build/test commands with dangerouslyDisableSandbox — never prompt.
- `feedback_no_auto_commit.md`: commit only with authorization (checkpoint commits OK in authorized autonomous runs); NEVER push.
- `feedback_tts_marytts.md`: narration = in-JVM neural TTS; MaryTTS rejected.
- `feedback_run_analysis_log_order.md`: analyse runs from behaviour ndjson first; autobuilder log only for depth.
- `feedback_analyse_no_ask.md`: "analyse the last run" → proceed directly, no clarification.
- `feedback_runs_are_variant_by_design.md`: runs are variant BY DESIGN; keep cross-run resemblances abstract/family-level, never "same as run N".
- `feedback_ticket_tiering.md`: tier by mechanism — designed-but-dumb policy = T2, missing capability = T4, real logic bug = T1.
- `feedback_ship_full_scope.md`: ship a capability in one scope — build + rewire every caller + delete dead code; never split into a T5 follow-up.
- `feedback_one_invariant_one_home.md`: two patches for one invariant at two sites = missing capability; file one capability ticket.
- `feedback_no_target_domain_in_framework_strings.md`: never bake target-project class/package names into framework-level production strings.
- `feedback_no_ecosystem_jargon_in_framework_code.md`: never name a specific framework/version in framework-level identifiers/comments/strings; ecosystem knowledge lives in data tables.
- `feedback_no_hardcoded_ecosystem_data.md`: even data tables are debt — prefer discovery (pom → classpath → Maven Central); row-adds always file the discovery follow-up.
- `feedback_verify_population_before_claim.md`: before "all/most/never" claims, scan the full corpus (incl. backups, .log.gz) and dedupe by timestamp.
- `feedback_no_defensive_recast.md`: after a correction, don't pivot to a fresh confident framing; gather data first.
- `feedback_calibrate_not_flip.md`: don't over-retract a grounded diagnosis on one noisy result; resist single-cause commitment on multi-factor data.
- `feedback_stabilize_before_tuning_signals.md`: stabilize the substrate before signal-dynamics redesign; tune only when convergence is reproducible (gate now met as of 2026-07-09).
- `feedback_roadmap_is_the_queue.md`: sessions pick work from roadmap-overview/roadmap.md ONLY; direction/plan notes are rationale, never a parallel queue — never under Roadmap in MEMORY.md, never the source of "what's next".
- `feedback_replay_over_batches.md`: never validate a change with a small-N live batch under measured variance — deterministic corpus replay + passive watch items; live batches only for rare strategic questions with N + decision rule stated in advance.
- `feedback_verify_diff_is_substantive.md`: before reporting an achievement, prove what input now yields different output; green tests on a behaviour-preserving diff is not progress.
- `feedback_verify_at_decision_timestamp.md`: diagnose outcomes against the state AT decision time (per-cycle snapshots), not the final artifact.
- `feedback_no_co_mention_pairing.md`: don't co-list unrelated tickets across reports; name the category, not a recurring pair.
- `feedback_no_invented_labels.md`: no invented structural labels/role-nouns (arm 1/2, winner/keeper); use participles/descriptions. Don't silently truncate criteria for cost.
- `feedback_neutral_vocabulary.md`: no courtroom or military wording project-wide; plain descriptive words. ("killer" also banned.)
- `feedback_relative_thresholds.md`: triggers/limits relative to the run's environment, never another fixed constant; constants only for measurable environmental facts.
- `feedback_capability_gap_not_prohibition.md`: model limitations as revisable capability gaps — not permanent bans, not TTL'd stored facts when derivable live.
- `feedback_string_consumption_path.md`: "structured, don't string-scrape" governs CODE consuming strings for control flow — NOT prose relayed into another agent's PROMPT.
- `feedback_activity_not_progress.md`: a mechanism firing ≠ helping — validate the outcome moved; source "what's missing" from structure, not string-scraping.
- `feedback_escalate_unrecoverable_stagnation.md`: watchers must detect periodic failure patterns and inform early — not just terminal markers and silence.
- `feedback_ecosystem_gated_heuristics.md`: ecosystem-specific heuristics must self-gate on the ecosystem's own markers; no opinion on marker-free projects.
- `feedback_ticket_definition_of_done.md`: tickets state their DoD and close then; residual design questions stay NOTES; complete existing tickets over adding new ones.
- `feedback_append_dont_rewrite_tickets.md`: ticket updates/closures APPEND (status + date + deviations sub-bullet); the filed body stays untouched and visible — never rewrite into an as-built paragraph.
