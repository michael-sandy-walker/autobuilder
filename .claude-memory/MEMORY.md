# Autobuilder — Persistent Memory

Index only: one line per memory, detail in the file. Each line ends `(reviewed YYYY-MM-DD)`;
`python3 scripts/orientation_check.py` flags a stamp older than its file's newest dated entry.
Restamp a line the day you re-check it against its file.

## Orientation
**START HERE** — two queues, two questions; neither re-orders the other.
- The product queue (WHAT gets built): `.claude-memory/roadmap.md` (or `python3 scripts/next_work.py`); `roadmap-overview.md` = thin index, `tier_definitions.md` = tiers.
- The development-process axis (HOW a session picks, proves, hands off): `.claude-memory/meta_process.md` (`M-` history; all of it since `.planning/` was cut 2026-09-08).
## Project and key facts
- Spring Boot 3.3 / Java 21 loop evolving a target (`file.base-path`) toward a goal with LLMs; Neo4j graph, H2 JPA. `AutobuilderService` → `CycleExecutor` → `AdaptiveExecutionEngine` → 5 agents → `GoalPlannerService` + graph
- Build `mvn clean install`, test `mvn test -Dtest=C#m`; targets `data/<name>/`, runs pick by `PREFLIGHT_TARGET=x`; goal stack `meta/GoalStack.json`; profiles `test`, `dev-local`, `dev-auto`, `fast`
- **`.claude-memory/` IS the loading memory** — checkout/branch/cherry-pick touching it mutates live memory; commit first. Tiers T1 correctness .. T6 harness
## Roadmap
- `roadmap-overview.md` — thin per-ticket index by tier. (reviewed 2026-11-04)
- `roadmap.md` — backlog and ticket bodies (source of truth). (reviewed 2026-11-04)
- `tier_definitions.md` — tier meanings. (reviewed 2026-09-08)
## Meta / Process (YOU+ME work, not the product)
- `meta_process.md` — how YOU+ME divide/sequence/staff work; "Model" = which Claude runs a session. (reviewed 2026-10-20)
## Project insights
Direction and orientation:
- `project_session_agenda.md`: 2026-08-23 REORDER section is live; S1-S6 reasoning only; never a queue. (reviewed 2026-09-08)
- `project_resume_state.md`: latest session/run state; only the NEWEST block has the entry marker. Read before quoting run numbers. (reviewed 2026-11-04)
- `project_wave2_structure.md`: wave 2 layers; widen what the loop SEES only after scoping what it CONSULTS. (reviewed 2026-07-26)
- `project_introspection_as_north_star.md`: goal = a loop with a self-model it consults before acting; "substrate or patch?". (reviewed 2026-09-08)
- `project_living_project_vision.md`: a project keeps taking inputs (seed = first); OFFSPRING code+graph become knowledge. Before input/merge/import work. (reviewed 2026-10-30)
- `project_three_disciplines_lens.md`: failures to priors, capabilities replace scar tissue, delete-subsumed. (reviewed 2026-07-09)
Design notes, read before touching their area:
- `project_t4j_design_note.md`: T4-J signal audit (decay, obsolete vs missing; calibration LAST). Before signal tuning/deletion. (reviewed 2026-07-09)
- `project_signal_consumer_map.md`, `project_signal_dispositions.md`, `project_t4j_perturbation_lab.md`: signal emitter/reader map, decided disposition per signal, frozen-state lab (ROLE-identity decay constants matter). Before signal work. (reviewed 2026-09-24)
- `project_t4dy_meta_layer_design_note.md`: T4-DY diagnostic layer: stuck signature -> question -> authority (structure, observation, model last) -> fact + ledger; closed. (reviewed 2026-10-05)
- `project_t4bz_design_note.md`, `project_t4bo_design_note.md`: signal re-architecture (invariants I1-I12) and decay observability. Before signal plumbing. (reviewed 2026-09-08)
- `project_t4ai_design_note.md`: meta lane was decorative; deleted. Before touching AdaptiveExecutionEngine/DeliberationService. (reviewed 2026-07-09)
- `project_t2ei_design_note.md`: T2-EI structure-first goal closure. (reviewed 2026-07-09)
- `project_t2ei_rule_interaction_map.md`: planner rules; FULL_COVERAGE and the coverage gate are one invariant. Before any planner rule. (reviewed 2026-07-09)
- `project_t4ap_prompt_audit.md`: prompt bloat = raw-payload passthroughs; `scripts/prompt_block_report.py`. Before ANY prompt change. (reviewed 2026-07-24)
- `project_pattern_recognition_design_note.md`: 6-slice gated plan; before `analysis/pattern/` or a self-model ticket. (reviewed 2026-09-08)
- `project_target_domain_plugin_design.md`: T4-DC cut five seams to two (T4-DD). Before any ecosystem-aware rule. (reviewed 2026-09-08)
- `project_state_time_travel_design_note.md`: rewind reproduces a STATE not a TRAJECTORY. Before rewind/branching. (reviewed 2026-09-19)
- `project_project_portability_vision.md`: import/continue/store/restore/merge a project's code layer. Before import/export. (reviewed 2026-09-25)
- `project_cross_run_knowledge_base.md`: reconciling graphs of DIFFERENT runs; now under T4-EC. (reviewed 2026-10-20)
- `project_reliability_plan.md`: reliability rationale (floor up, ceiling free); not a queue. (reviewed 2026-07-14)
- `reference_undone_ideas.md`: user's unbuilt ideas with document+line in ~/Documents/autobuilder; never a queue. (reviewed 2026-09-23)
Measurement and yardsticks:
- `project_yardstick_must_not_outrun_the_spec.md`: a yardstick asserts only what the SPEC states. (reviewed 2026-10-07)
- `project_smoke_score_is_chained.md`: the 23-check score is a CHAIN; compare failing SETS, not totals. (reviewed 2026-08-01)
- `project_first_perfect_smoke_score.md`: 23/23 routine; does the loop KNOW it got there? (reviewed 2026-09-08)
- `project_goal_fulfilled_is_real_happy_path.md`: GOAL_FULFILLED routine; length-matched fulfilled runs as yardsticks. (reviewed 2026-07-09)
- `project_rejection_composition.md`: why mutations are rejected; before compile-failure work. (reviewed 2026-09-08)
- `project_completion_binding_constraint.md`: planner coverage/convergence plus residual variance. (reviewed 2026-07-09)
- `project_scenario_replay_harness.md`: freeze a tree, replay one cycle's input (`scripts/freeze_scenario.sh`). (reviewed 2026-09-19)
- `project_coverage_guard_experiment.md`: off-switch experiments with metrics and decision rule stated first. (reviewed 2026-07-09)
- `project_t4bq_naming_consistency_ab.md`: A/B stood down; 3-pair minimum before claims. (reviewed 2026-07-09)
False completions and diagnosis lenses:
- `project_false_completion_unmasked.md`, `_2`, `_3`, `_4`: the four false completions -- stub-blind verifier, unbootable jar (abstain is not pass), DI defect averaged away, bookkeeping ledger never reading the seed. (reviewed 2026-09-28)
- `project_invented_facts_have_authorities.md`: defects are INVENTED FACTS whose cheap authority went unconsulted; structure before model. (reviewed 2026-09-30)
- `project_capability_outruns_routing.md`: a missing behaviour is UNREACHED, not unbuilt; find the link that does not fire. (reviewed 2026-09-21)
- `project_ticket_premises_expire.md`: a ticket's BLOCKER is a dated claim; grep both ways first. (reviewed 2026-09-21)
- `project_detector_went_silent_as_the_fault_spread.md`: ask any majority rule what it does when the anomaly is the majority. (reviewed 2026-09-03)
- `project_completion_edge_gates.md`: coverage, package, test; every diagnosis needs an actuator. (reviewed 2026-07-09)
- `project_grounding_night_insight.md`: prefer belief-to-verification moves. (reviewed 2026-07-09)
- `project_agent_diagnosis_not_shared.md`: T4-BX relays evaluator rejection reasons verbatim. (reviewed 2026-07-09)
- `project_delete_debt_pattern.md`: delete work filed as T5 follow-up never reaches the top. (reviewed 2026-07-09)
- `project_local_first_constraint.md`: local execution is HARD; no hosted-model upgrade. (reviewed 2026-07-09)
- `project_meta_concrete_target_hint_idea.md`: historical; only if reviving a non-LLM concrete-target hint. (reviewed 2026-07-09)
Operations:
- `project_run_lifecycle_runbook.md`: start/stop/monitor runs, watcher traps, `early_stop.sh`, `bundle.py`, versioned toy cases. (reviewed 2026-11-01)
- `project_log_and_backup_conventions.md` (+ superseded `project_tests_rotate_live_logs.md`): current run = unsuffixed `logs/behaviour.ndjson`, concatenated pretty JSON; tests use a scratch `FILE_BASE_PATH`. (reviewed 2026-09-08)
## Feedback
How to work:
- `feedback_run_without_asking.md`: run mvn/build/test with dangerouslyDisableSandbox; never prompt. (reviewed 2026-07-09)
- `feedback_analyse_no_ask.md`: "analyse the last run" = proceed directly. (reviewed 2026-07-09)
- `feedback_run_analysis_log_order.md`: behaviour ndjson first, autobuilder log for depth. (reviewed 2026-07-09)
- `feedback_no_auto_commit.md`: commit only with authorization; NEVER push (except own claude/ branch in cloud sessions). (reviewed 2026-09-24)
- `feedback_commit_the_run_ledger.md`: commit `scripts/run_outcomes.json` with the session's work. (reviewed 2026-10-31)
- `feedback_no_branch_ops.md`: no merge/rebase/reset/branch -d/push without 'do it'. (reviewed 2026-09-08)
- `feedback_operator_reviews_ports.md`: a ported loop-built solution gets a user review packet first. (reviewed 2026-09-27)
- `feedback_process_apart_from_product.md`: how we work lives in meta_process.md + process/, never src/ or tickets. (reviewed 2026-09-27)
- `feedback_tickets_direct_loop_as_toy_cases.md`: implement tickets directly; loop measured on VERSIONED toy cases. (reviewed 2026-09-27)
- `feedback_local_means_no_call_cost.md`: token cost is free; wall-clock, context and memory are not. (reviewed 2026-09-27)
- `feedback_never_edit_a_running_script.md`: never edit preflight.sh or its inputs mid-run (git ops count). (reviewed 2026-09-24)
- `feedback_no_heavy_mvn_beside_a_live_run.md`: no suite/tests/graph queries beside a live run; pgrep the driver first. (reviewed 2026-11-03)
- `feedback_side_tests_must_not_share_lineage.md`: a scratch copy must not carry `meta/run-identity.json` -- it writes onto the real project in the shared graph. (reviewed 2026-11-03)
- `feedback_options_before_building_and_stop_weak_batches.md`: real options before building; stop weak batches; prose, recommend, no lettered menu. (reviewed 2026-10-22)
- `feedback_flag_ceremony_cost_early.md`: say so BEFORE process costs more than the task. (reviewed 2026-09-08)
- `feedback_sonnet_for_batch_tickets.md`: mechanical tickets to a Sonnet subagent; design/validation stay here. (reviewed 2026-07-14)
- `feedback_roadmap_is_the_queue.md`: work comes from the roadmap ONLY. (reviewed 2026-09-10)
- `feedback_no_per_run_memory_files.md`: fold runs into `project_resume_state.md`. (reviewed 2026-09-25)
Evidence discipline:
- `feedback_baseline_before_accuracy.md`: four ways a predictor-hunt lies. (reviewed 2026-09-10)
- `feedback_verify_population_before_claim.md`: scan the full corpus before all/most/never. (reviewed 2026-07-09)
- `feedback_grep_the_behaviour_not_the_name.md`: test a predicate on a known positive before reporting zero. (reviewed 2026-09-13)
- `feedback_count_events_not_log_lines.md`: count the EVENT, not lines mentioning it. (reviewed 2026-08-01)
- `feedback_dont_group_defects_by_vocabulary.md`: a shared description is not a shared cause. (reviewed 2026-08-29)
- `feedback_compare_against_the_last_good_build.md`: compare to the last GOOD build; name it in the batch header. (reviewed 2026-10-11)
- `feedback_dont_evaluate_a_build_with_older_runs.md`: a window is a rate only if build AND target are constant. (reviewed 2026-09-21)
- `feedback_read_test_results_from_the_run.md`: quote test counts only from THIS run's output. (reviewed 2026-11-03)
- `feedback_verify_diff_is_substantive.md`: prove which input now yields different output. (reviewed 2026-09-08)
- `feedback_verify_at_decision_timestamp.md`: diagnose against state AT decision time. (reviewed 2026-07-09)
- `feedback_activity_not_progress.md`: a mechanism firing is not helping; check the outcome. (reviewed 2026-07-09)
- `feedback_replay_over_batches.md`: validate by deterministic replay; a DoD names its instrument. (reviewed 2026-10-20)
- `feedback_measure_before_widening_a_working_rule.md`: a gap in a run that still closes is to measure, not widen. (reviewed 2026-10-20)
- `feedback_short_field_run_after_context_change.md`: short live run after any cycle-context/persisted-record change. (reviewed 2026-07-30)
- `feedback_llm_probe_realistic_context.md`: replays use real contexts and the run's request settings. (reviewed 2026-11-01)
- `feedback_runs_are_variant_by_design.md`: keep cross-run resemblances family-level. (reviewed 2026-07-09)
- `feedback_stabilize_before_tuning_signals.md`: stabilize before signal redesign. (reviewed 2026-07-09)
Reasoning and correction:
- `feedback_calibrate_not_flip.md`: do not over-retract on one noisy result. (reviewed 2026-07-09)
- `feedback_no_defensive_recast.md`: after a correction, gather data before a new framing. (reviewed 2026-07-09)
- `feedback_capability_gap_not_prohibition.md`: limitations are revisable gaps, not bans. (reviewed 2026-07-09)
Ticket and scope discipline:
- `feedback_ticket_tiering.md`: tier by mechanism: policy T2, missing capability T4, logic bug T1. (reviewed 2026-07-09)
- `feedback_ticket_definition_of_done.md`: state the DoD, close then; fix or drop before filing. (reviewed 2026-10-28)
- `feedback_append_dont_rewrite_tickets.md`: ticket updates APPEND. (reviewed 2026-07-10)
- `feedback_ship_full_scope.md`: build, rewire callers, delete dead code in one scope. (reviewed 2026-07-09)
- `feedback_instrument_needs_an_actuator.md`: observability ships its actuator in the same scope. (reviewed 2026-07-31)
- `feedback_one_invariant_one_home.md`: two patches for one invariant = one capability ticket. (reviewed 2026-07-09)
Code and design constraints:
- `feedback_no_internal_ids_in_model_facing_text.md`: an identifier in a prompt only if the prompt DEFINES it. (reviewed 2026-09-17)
- `feedback_no_target_domain_in_framework_strings.md`: no target-shaped names in framework strings; targets are TOY CASES. (reviewed 2026-09-23)
- `feedback_no_ecosystem_jargon_in_framework_code.md`: no framework/version names in framework code. (reviewed 2026-07-09)
- `feedback_no_hardcoded_ecosystem_data.md`: prefer discovery over data tables. (reviewed 2026-07-09)
- `feedback_ecosystem_gated_heuristics.md`: ecosystem heuristics self-gate on their markers. (reviewed 2026-07-09)
- `feedback_relative_thresholds.md`: thresholds relative to the run; `Recurrence` ratios. (reviewed 2026-09-30)
- `feedback_llm_probes_logs_dynamically.md`: log analysis = an LLM probing iteratively; never code knowing the log format. (reviewed 2026-11-02)
- `feedback_string_consumption_path.md`: no string-scraping for CODE control flow; prose relayed to prompts is fine. (reviewed 2026-07-09)
Watchers:
- `feedback_batch_watcher_alarms_on_the_environment.md`: alarm on Neo4j down and <2 GB free. (reviewed 2026-10-11)
- `feedback_monitor_every_terminal.md`: monitors match EVERY terminal state; pgrep must not match itself. (reviewed 2026-10-26)
- `feedback_escalate_unrecoverable_stagnation.md`: detect periodic failure early; same decision while nothing moves = grind. (reviewed 2026-10-19)
- `feedback_waiter_fires_on_loop_terminal.md`: waiters fire on the LOOP's terminal line. (reviewed 2026-09-24)
- `feedback_fix_first_when_a_finding_changes_the_measurement.md`: stop and fix first; no batch from uncommitted code. (reviewed 2026-09-30)
- `feedback_progress_updates_during_batches.md`: update after every batch run. (reviewed 2026-09-30)
- `feedback_cut_flat_runs_short.md`: `early_stop.sh` beside every field run; patience rules inside. (reviewed 2026-10-05)
Writing:
- `feedback_curly_quotes.md`: ASCII only (`scripts/charset_guard.py`); no `jq`, use python3. (reviewed 2026-08-28)
- `feedback_name_outcomes_for_what_they_are.md`: a run I stop is `jvm-stopped`; stop via the recording mechanism. (reviewed 2026-10-17)
- `feedback_neutral_vocabulary.md`, `feedback_no_symbolic_enumerations.md`, `feedback_no_invented_labels.md`, `feedback_no_co_mention_pairing.md`: no courtroom/military words or emoji; no circled numbers; no invented labels; no co-listing unrelated tickets. (reviewed 2026-09-15)
- `feedback_tts_marytts.md`: narration is in-JVM neural TTS; MaryTTS rejected. (reviewed 2026-07-09)
