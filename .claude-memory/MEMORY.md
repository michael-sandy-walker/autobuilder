# Autobuilder — Persistent Memory

Index only. One line per memory: what it is, and when to open it. Detail lives in the file.
Every index line ends with a review stamp -- `(reviewed YYYY-MM-DD)` -- recording when it was
last checked against the file it points at. `python3 scripts/orientation_check.py` reports a
stamp older than the target's newest dated entry as `stale-index-line`, and a line rewritten
without a restamp will be reported that way too -- restamp it the same day you edit it.

## Orientation
**START HERE** — two live queues answer two different questions; read below to see which one answers yours.
- The product queue is the source of truth for what Autobuilder gets built next; read it at
  `.claude-memory/roadmap.md`, or cheaply via `python3 scripts/next_work.py`. `roadmap-overview.md`
  is its thin one-line-per-ticket index, and `tier_definitions.md` is its tier legend.
- The development-process axis is the sequence for how a session picks work, proves a claim,
  and hands off; it lives in `.claude-memory/meta_process.md`, which holds the `M-` history and,
  since the 2026-09-08 milestone cut deleted `.planning/`, is now the whole of it.

The product queue answers what Autobuilder gets built next; the development-process axis answers
how a session does the work. Neither re-orders the other.

## Project
Spring Boot 3.3 / Java 21 autonomous self-improvement loop. Evolves a target codebase (under `file.base-path`) toward a stated goal using LLMs. Neo4j for graph history; H2 for JPA. OpenAI API.

## Key facts
- Build: `mvn clean install` | Test: `mvn test -Dtest=ClassName#method`
- Targets live under `data/<name>/` (each owns a tracked `bootstrap/seed.txt`, rest ignored by one glob). `mvn test` needs no `FILE_BASE_PATH` since 2026-08-28; runs select a target by NAME: `PREFLIGHT_TARGET=flightsim`
- Neo4j required for graph-backed features and integration tests
- Goal stack: `meta/GoalStack.json`, relative to `file.base-path` (default `data/firestarter`)
- Spring profiles: `test` (no scheduler/bootstrap), `dev-local` (bootstrap only), `dev-auto` (full autonomous); add `fast` for ~10-15% faster runs
- **`.claude-memory/` IS the loading memory** — `git checkout`/branch/cherry-pick touching it mutates live memory; commit memory on the target branch before switching
- Ticket IDs: T1=Correctness, T2=Steering, T3=Feature completion, T4=New systems, T5=Quality/observability, T6=Harness & yardsticks

## Architecture in one line
`AutobuilderService` (60s scheduler) → `CycleExecutor` → `AdaptiveExecutionEngine` (signal-driven, fitness-ranked) → 5 agents (planner / evolution / evaluation / merge / analysis) → `GoalPlannerService` + Neo4j graph

## Roadmap
- `roadmap-overview.md` — thin one-line-per-ticket index over `roadmap.md`, grouped by tier. (reviewed 2026-10-28)
- `roadmap.md` — full backlog and ticket bodies (source of truth). (reviewed 2026-10-28)
- `tier_definitions.md` — canonical tier meanings. (reviewed 2026-09-08)

## Meta / Process (YOU+ME work, not the product)
- `meta_process.md` — how YOU+ME divide/sequence/staff the work. "Model" = which Claude runs a session; advisory. (reviewed 2026-10-20)

## Project insights

Direction and orientation:
- `project_session_agenda.md`: live ordering is the 2026-08-23 REORDER section at the end; S1-S6 kept for reasoning only. Never a queue. (reviewed 2026-09-08)
- `project_resume_state.md`: latest session/run state, folded per run; only the NEWEST block carries the entry marker. Read before quoting any run-window number. (reviewed 2026-10-28)
- `project_wave2_structure.md`: wave 2's five layers and gate-driven sequence; widen what the loop SEES only after scoping what it CONSULTS. (reviewed 2026-07-26)
- `project_introspection_as_north_star.md`: the goal is a loop with a self-model it consults before acting; triage lens ("substrate or patch?"). (reviewed 2026-09-08)
- `project_three_disciplines_lens.md`: failures to priors, capabilities replace scar tissue, delete-subsumed. (reviewed 2026-07-09)

Design notes, read before touching their area:
- `project_t4j_design_note.md`: T4-J OPEN signal-system audit (decay vs not, obsolete vs missing; calibration LAST). Read before any signal tuning or deletion. (reviewed 2026-07-09)
- `project_signal_consumer_map.md`: T4-J emitter/clearer/reader map over 35 signal types + `scripts/signal_boundary_report.py`. The LLM is itself both consumer and emitter. (reviewed 2026-07-09)
- `project_signal_dispositions.md`: T4-J answer sheet — the decided disposition per signal. Open: analysis/planner tie-break, evaluation-prompt signal-blindness. (reviewed 2026-09-24)
- `project_t4j_perturbation_lab.md`: frozen-state lab; the decision-sensitive decay constants are the ROLE-identity signals, not the census shortlist. Read before signal tuning. (reviewed 2026-09-08)
- `project_t4dy_meta_layer_design_note.md`: T4-DY meta layer = DIAGNOSTIC layer on the informational axis; stuck signature -> question -> authority (structure, observation, model last) -> fact + ledger; slice A offline router, slice B in the loop (core/stuck, DoD met 2026-09-26). The dossier was removed 2026-10-04 (T4-DY closed). Read before building under T4-DY. (reviewed 2026-10-05)
- `project_t4bz_design_note.md`: T4-BZ signal re-architecture SHIPPED; family map and invariants I1-I12 are the design record. (reviewed 2026-09-08)
- `project_t4bo_design_note.md`: T4-BO signal-decay observability — two-snapshot death attribution, causality-preserving snapshot point. Read before touching signal plumbing. (reviewed 2026-07-09)
- `project_t4ai_design_note.md`: T4-AI — the meta lane was decorative; closed by deletion. Read before touching AdaptiveExecutionEngine/DeliberationService. (reviewed 2026-07-09)
- `project_t2ei_design_note.md`: T2-EI structure-first goal closure. Read before implementing T2-EI. (reviewed 2026-07-09)
- `project_t2ei_rule_interaction_map.md`: planner-rule map — FULL_COVERAGE and the coverage gate are one invariant. Read before touching any planner rule. (reviewed 2026-07-09)
- `project_t4ap_prompt_audit.md`: prompt bloat is raw-payload passthroughs; `scripts/prompt_block_report.py`. Read before ANY prompt-surface change. (reviewed 2026-07-24)
- `project_pattern_recognition_design_note.md`: 6-slice gated plan for pattern recognition. Read before touching `analysis/pattern/` or filing a self-model ticket. (reviewed 2026-09-08)
- `project_target_domain_plugin_design.md`: T4-DC field proof (CLI target 12/12) cut the design from five seams to two; the design ticket is T4-DD. Read before adding any ecosystem-aware rule. (reviewed 2026-09-08)
- `project_state_time_travel_design_note.md`: T4-DQ rewind reproduces a STATE, not a TRAJECTORY; restore the carried cycle context. Read before rewind/branching work. (reviewed 2026-09-19)
- `project_project_portability_vision.md`: import/continue/store/restore/merge a project's code layer; T4-CQ and T4-CR closed (rung1, structdiff, participation), T4-CS slice 1 built (test layers over identical library code). Read before import/export work. (reviewed 2026-09-25)
- `project_cross_run_knowledge_base.md`: reconciling graphs from DIFFERENT runs at whole-tree altitude; T4-CN/T4-CM now subsumed by T4-EC (project merge). Read before T4-EC's matching or view stages. (reviewed 2026-10-20)
- `project_reliability_plan.md`: rationale behind the reliability work (floor up, ceiling free). NOT a queue; everything it selected is filed or closed. (reviewed 2026-07-14)
- `reference_undone_ideas.md`: the user's unbuilt ideas with document and line in ~/Documents/autobuilder; a reference, never a queue. (reviewed 2026-09-23)

Measurement and yardsticks:
- `project_yardstick_must_not_outrun_the_spec.md`: a yardstick may only assert what the SPEC states. Audit it against the seed before attributing a score change. (reviewed 2026-10-07)
- `project_smoke_score_is_chained.md`: the 23-check score is a stateful CHAIN, not 23 measurements. Compare failing check SETS, never totals. (reviewed 2026-08-01)
- `project_first_perfect_smoke_score.md`: 23/23 is routine; the open question is whether the loop KNOWS it got there. (reviewed 2026-09-08)
- `project_goal_fulfilled_is_real_happy_path.md`: GOAL_FULFILLED fires routinely; use length-matched fulfilled runs as yardsticks. (reviewed 2026-07-09)
- `project_rejection_composition.md`: why mutations are rejected; "model cannot see signatures" REFUTED. Read before filing compile-failure work. (reviewed 2026-09-08)
- `project_completion_binding_constraint.md`: resolved — the constraint is planner coverage/convergence plus residual variance, two-factor. (reviewed 2026-07-09)
- `project_scenario_replay_harness.md`: freeze a run's tree and replay in ms (`scripts/freeze_scenario.sh`); one cycle's input, not a sequence. (reviewed 2026-09-19)
- `project_coverage_guard_experiment.md`: runbook pattern for falsifiable off-switch experiments — metrics and decision rule stated in advance. (reviewed 2026-07-09)
- `project_t4bq_naming_consistency_ab.md`: naming-consistency A/B stood down; 3-pair minimum before claims; do not name a replacement lever without critical-path evidence. (reviewed 2026-07-09)

False completions and diagnosis lenses:
- `project_false_completion_unmasked.md`: the first — a correct fix unmasked verifier stub-blindness. Re-validate outcomes after correct fixes. (reviewed 2026-07-09)
- `project_false_completion_2.md`: unbootable jar; abstain is not pass at the completion edge; a gate can be satisfied instead of its requirement. (reviewed 2026-07-09)
- `project_false_completion_3.md`: DI-wiring defect invisible to compile gates; a correct REJECTED vote averaged away. (reviewed 2026-07-09)
- `project_false_completion_4.md`: the first by BOOKKEEPING — the closure ledger counts dispositions, never the seed. Audited 2026-09-28: 54 of 60 later GOAL_FULFILLED at full yardstick, the rest other mechanisms. (reviewed 2026-09-28)
- `project_invented_facts_have_authorities.md`: defects are INVENTED FACTS whose cheap authority was unconsulted, cropped, or asked the WRONG question; ask whether structure can answer before asking a model; ask of any summary in a prompt whether the task can be done from it. (reviewed 2026-09-30)
- `project_capability_outruns_routing.md`: assume a missing behaviour is UNREACHED, not unbuilt; trace to the link that does not fire. (reviewed 2026-09-21)
- `project_ticket_premises_expire.md`: a ticket's stated BLOCKER is a dated claim; grep both directions before designing. (reviewed 2026-09-21)
- `project_detector_went_silent_as_the_fault_spread.md`: a majority rule stopped firing when the anomaly became the majority. Ask any majority rule what it does then. (reviewed 2026-09-03)
- `project_completion_edge_gates.md`: completion edge is coverage then package then test; every diagnosis needs an actuator; brakes must count every evidence class. (reviewed 2026-07-09)
- `project_grounding_night_insight.md`: prefer belief-to-verification moves in triage. (reviewed 2026-07-09)
- `project_agent_diagnosis_not_shared.md`: evaluator rejection reasons were discarded; T4-BX now relays them verbatim into evolution/planner prompts. (reviewed 2026-07-09)
- `project_delete_debt_pattern.md`: delete work filed as a T5 follow-up never reaches the queue top; scaffolding accumulates by mechanism. (reviewed 2026-07-09)
- `project_local_first_constraint.md`: local execution is a HARD constraint; no hosted-model upgrade. (reviewed 2026-07-09)
- `project_meta_concrete_target_hint_idea.md`: historical note; read only if reintroducing a concrete-target hint via a non-LLM path. (reviewed 2026-07-09)

Operations:
- `project_run_lifecycle_runbook.md`: start/stop/monitor runs; watcher traps; adopted-target runs, `early_stop.sh`, `bundle.py`. Prove a monitor STREAMS first: `scripts/prove_watcher_streams.sh`. Toy cases are versioned (`toycase_version.py`). (reviewed 2026-10-22)
- `project_log_and_backup_conventions.md`: current run is UNSUFFIXED `logs/behaviour.ndjson`; files are concatenated pretty JSON, not line-JSON. (reviewed 2026-09-08)
- `project_tests_rotate_live_logs.md`: SUPERSEDED: redirect `FILE_BASE_PATH` to a scratch dir instead of stopping the app. (reviewed 2026-09-08)

## Feedback

How to work:
- `feedback_run_without_asking.md`: run mvn/build/test with dangerouslyDisableSandbox; never prompt. (reviewed 2026-07-09)
- `feedback_analyse_no_ask.md`: "analyse the last run" means proceed directly, no clarification. (reviewed 2026-07-09)
- `feedback_run_analysis_log_order.md`: analyse runs from the behaviour ndjson first; the autobuilder log only for depth. (reviewed 2026-07-09)
- `feedback_no_auto_commit.md`: commit only with authorization (checkpoint commits OK in authorized autonomous runs); NEVER push, except to the session's own claude/ branch in cloud-container sessions (CLAUDE_CODE_REMOTE_ENVIRONMENT_TYPE=cloud*; not Remote Control). (reviewed 2026-09-24)
- `feedback_no_branch_ops.md`: never merge/rebase/reset/branch -d/push autonomously; describe and wait for 'do it'. (reviewed 2026-09-08)
- `feedback_operator_reviews_ports.md`: IF a loop-built solution is ever ported, the user reviews a packet first (process/, M-E); since M-F ports are off the default path. (reviewed 2026-09-27)
- `feedback_process_apart_from_product.md`: how we work (reviews, packets, session tooling) lives in meta_process.md + process/, never as product tickets or in src/ / scripts/. (reviewed 2026-09-27)
- `feedback_tickets_direct_loop_as_toy_cases.md`: implement tickets directly; the loop is tested separately on VERSIONED toy cases, output measured not ported. (reviewed 2026-09-27)
- `feedback_local_means_no_call_cost.md`: local model -- LLM call/token cost is no constraint; wall-clock time, context size and box memory still are. (reviewed 2026-09-27)
- `feedback_never_edit_a_running_script.md`: never edit preflight.sh (or what it reads) mid-run, and a git merge/rebase/checkout counts as an edit — bash reads scripts incrementally, so edits land half-applied and silently. (reviewed 2026-09-24)
- `feedback_no_heavy_mvn_beside_a_live_run.md`: a full suite beside a live run starves the box and kills the run via Neo4j timeouts; in a batch, scoring the last run's tree or targeted tests count too (2026-10-02); a per-run notification is not the batch ending -- pgrep the driver before any edit or mvn (2026-10-06). (reviewed 2026-10-07)
- `feedback_options_before_building_and_stop_weak_batches.md`: real design options before building a non-trivial capability; stop weak batches early; options are alternatives, not stages (2026-10-20); and NOT a lettered menu by reflex -- answer questions in prose, recommend, ask openly (2026-10-22). (reviewed 2026-10-22)
- `feedback_flag_ceremony_cost_early.md`: when process costs more than the task, say so BEFORE running it. (reviewed 2026-09-08)
- `feedback_sonnet_for_batch_tickets.md`: batch/mechanical tickets go to a Sonnet subagent (Agent tool model override); design and validation stay on the main model. (reviewed 2026-07-14)
- `feedback_roadmap_is_the_queue.md`: work comes from the roadmap ONLY; direction notes are rationale, never a parallel queue. (reviewed 2026-09-10)
- `feedback_no_per_run_memory_files.md`: no new memory file per field run; fold into `project_resume_state.md`; separate files only for durable cross-run insights. (reviewed 2026-09-25)

Evidence discipline:
- `feedback_baseline_before_accuracy.md`: four ways a predictor-hunt lies (baseline, best-of-N, extremes-only, shifting denominator). (reviewed 2026-09-10)
- `feedback_verify_population_before_claim.md`: before any all/most/never claim, scan the full corpus including backups and dedupe by timestamp. (reviewed 2026-07-09)
- `feedback_grep_the_behaviour_not_the_name.md`: a grep finds a SPELLING, not a BEHAVIOUR; test the predicate on a known positive before reporting zero. (reviewed 2026-09-13)
- `feedback_count_events_not_log_lines.md`: count the EVENT, not log lines mentioning it; re-rendered records inflated two ticket headlines 23x and 3x. (reviewed 2026-08-01)
- `feedback_dont_group_defects_by_vocabulary.md`: a shared description is not a shared cause; verify each member's mechanism. (reviewed 2026-08-29)
- `feedback_compare_against_the_last_good_build.md`: judge a new line against the last GOOD build's runs (scores, failing sets, best measure), never its own predecessor; name the reference in the batch header. (reviewed 2026-10-11)
- `feedback_dont_evaluate_a_build_with_older_runs.md`: a window is a rate only if build AND target are constant; split by target first. (reviewed 2026-09-21)
- `feedback_read_test_results_from_the_run.md`: quote a test count from THIS run's output or a report newer than it; a failed compile leaves the last run's green reports standing. (reviewed 2026-10-27)
- `feedback_verify_diff_is_substantive.md`: prove which input now yields different output; green tests on a no-op diff is not progress. (reviewed 2026-09-08)
- `feedback_verify_at_decision_timestamp.md`: diagnose against the state AT decision time (per-cycle snapshots), not the final artifact. (reviewed 2026-07-09)
- `feedback_activity_not_progress.md`: a mechanism firing is not helping; validate the outcome moved. (reviewed 2026-07-09)
- `feedback_replay_over_batches.md`: validate with deterministic corpus replay + watch items, not small live batches; a DoD names its instrument; a rule reading model answers is checked against the archive's real answers first (2026-10-19). (reviewed 2026-10-20)
- `feedback_measure_before_widening_a_working_rule.md`: a gap seen in a run that still closes is evidence to measure, not a reason to widen the rule (user, 2026-10-19). (reviewed 2026-10-20)
- `feedback_short_field_run_after_context_change.md`: do a short live run after any cycle-context or persisted-record change. (reviewed 2026-07-30)
- `feedback_llm_probe_realistic_context.md`: synthetic probe contexts must match the real run distribution or they manufacture false findings. (reviewed 2026-07-24)
- `feedback_runs_are_variant_by_design.md`: runs are variant BY DESIGN; keep cross-run resemblances family-level, never "same as run N". (reviewed 2026-07-09)
- `feedback_stabilize_before_tuning_signals.md`: stabilize the substrate before signal-dynamics redesign; tune only when convergence is reproducible (gate met 2026-07-09). (reviewed 2026-07-09)

Reasoning and correction:
- `feedback_calibrate_not_flip.md`: do not over-retract a grounded diagnosis on one noisy result; resist single-cause commitment on multi-factor data. (reviewed 2026-07-09)
- `feedback_no_defensive_recast.md`: after a correction, do not pivot to a fresh confident framing; gather data first. (reviewed 2026-07-09)
- `feedback_capability_gap_not_prohibition.md`: model limitations as revisable capability gaps -- not permanent bans, and not TTL'd stored facts when derivable live. (reviewed 2026-07-09)

Ticket and scope discipline:
- `feedback_ticket_tiering.md`: tier by mechanism — designed-but-dumb policy is T2, missing capability T4, real logic bug T1. (reviewed 2026-07-09)
- `feedback_ticket_definition_of_done.md`: tickets state their DoD and close then; the filing reflex is the bigger trap -- fix it or drop it, file last; once the DoD is met, name any new mechanism as a new capability before building it under the ticket (T6-U, 2026-10-28). (reviewed 2026-10-28)
- `feedback_append_dont_rewrite_tickets.md`: ticket updates APPEND; the filed body stays untouched. (reviewed 2026-07-10)
- `feedback_ship_full_scope.md`: ship a capability in one scope — build, rewire every caller, delete dead code; never split into a T5 follow-up. (reviewed 2026-07-09)
- `feedback_instrument_needs_an_actuator.md`: an observability ticket ships its actuator in the SAME scope; prompt prose is not an actuator. (reviewed 2026-07-31)
- `feedback_one_invariant_one_home.md`: two patches for one invariant at two sites means a missing capability; file one capability ticket. (reviewed 2026-07-09)

Code and design constraints:
- `feedback_no_internal_ids_in_model_facing_text.md`: an identifier belongs in a prompt only if that prompt DEFINES it. (reviewed 2026-09-17)
- `feedback_no_target_domain_in_framework_strings.md`: no target-shaped names or constants in framework strings or analysis scripts. 2026-09-23: the user reminded that current targets are TOY CASES -- rules over conventions, invented-coordinate tests. (reviewed 2026-09-23)
- `feedback_no_ecosystem_jargon_in_framework_code.md`: never name a framework/version in framework code; ecosystem knowledge lives in data tables. (reviewed 2026-07-09)
- `feedback_no_hardcoded_ecosystem_data.md`: even data tables are debt; prefer discovery (pom -> classpath -> Maven Central), and row-adds always file the discovery follow-up. (reviewed 2026-07-09)
- `feedback_ecosystem_gated_heuristics.md`: ecosystem-specific heuristics must self-gate on the ecosystem's own markers; no opinion on marker-free projects. (reviewed 2026-07-09)
- `feedback_relative_thresholds.md`: triggers and limits relative to the run's environment; constants only for measurable environmental facts. 2026-09-29: use `core/stuck/Recurrence` ratios, not counts; 2026-09-30: candidate-level failures (build, refused write/closure) keep the cycle count -- replay first. (reviewed 2026-09-30)
- `feedback_string_consumption_path.md`: "structured, do not string-scrape" governs CODE consuming strings for control flow, NOT prose relayed into another agent's PROMPT. (reviewed 2026-07-09)

Watchers:
- `feedback_batch_watcher_alarms_on_the_environment.md`: the watcher alarms on Neo4j down and under 2 GB available; a run with almost no cycles is an environment failure, not a score. (reviewed 2026-10-11)
- `feedback_monitor_every_terminal.md`: a monitor must match EVERY terminal state; stopping one for noise means re-arming a narrower one, never going blind; a driver's `until grep` wait also ends when the run ends (2026-10-26); anchor pgrep on argv start -- a check must not match itself or hide a wrapper containing it (2026-10-26 night). (reviewed 2026-10-26)
- `feedback_escalate_unrecoverable_stagnation.md`: watchers -- and the loop itself -- must detect periodic failure patterns and act early; any gate deciding the same way while nothing moves is a grind (2026-10-18). (reviewed 2026-10-19)
- `feedback_waiter_fires_on_loop_terminal.md`: a run waiter fires on the LOOP's terminal line, not only preflight's final line minutes later. (reviewed 2026-09-24)
- `feedback_fix_first_when_a_finding_changes_the_measurement.md`: when a finding changes what a running batch measures, stop and fix first (user's choice twice); never let a batch build from uncommitted code. (reviewed 2026-09-30)
- `feedback_progress_updates_during_batches.md`: post a short update after every run of an unattended batch; arm a per-run watcher. (reviewed 2026-09-30)
- `feedback_cut_flat_runs_short.md`: start `scripts/early_stop.sh` beside every field run; firestarter patience 30, minimum 45; a STEP-SCORED ladder case needs patience half the budget and no stop before three quarters (2026-10-05). (reviewed 2026-10-05)

Writing:
- `feedback_curly_quotes.md`: no curly quotes/stray non-Latin chars; enforced by `scripts/charset_guard.py`. No `jq`; use python3. (reviewed 2026-08-28)
- `feedback_name_outcomes_for_what_they_are.md`: name an outcome for what happened -- a run I stop is `jvm-stopped`, never `jvm-died`; stop runs via the recording mechanism. (reviewed 2026-10-17)
- `feedback_neutral_vocabulary.md`: no courtroom/military wording, no emoji; `rehabilitationVotes` kept (persisted key). (reviewed 2026-09-15)
- `feedback_no_symbolic_enumerations.md`: never use circled-number characters anywhere; name each item instead -- positional tokens carry no meaning on re-read. (reviewed 2026-07-30)
- `feedback_no_invented_labels.md`: no invented structural labels or role-nouns; use participles and descriptions. Do not silently truncate criteria for cost. (reviewed 2026-07-09)
- `feedback_no_co_mention_pairing.md`: do not co-list unrelated tickets across reports; name the category, not a recurring pair. (reviewed 2026-07-09)
- `feedback_tts_marytts.md`: narration is in-JVM neural TTS; MaryTTS rejected. (reviewed 2026-07-09)
