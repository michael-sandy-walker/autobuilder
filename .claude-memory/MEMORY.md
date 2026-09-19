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
- Targets live under `data/<name>/` (each owns a tracked `bootstrap/seed.txt`, rest ignored by one glob). `mvn test` needs no `FILE_BASE_PATH` since 2026-08-28 (default is relative); the container keeps absolute `/data/firestarter`. Runs select a target by NAME: `PREFLIGHT_TARGET=flightsim`
- Neo4j required for graph-backed features and integration tests
- Goal stack: `meta/GoalStack.json`, relative to `file.base-path` (default `data/firestarter`)
- Spring profiles: `test` (no scheduler/bootstrap), `dev-local` (bootstrap only), `dev-auto` (full autonomous); add `fast` for ~10-15% faster runs
- **`.claude-memory/` IS the loading memory** — `git checkout`/branch/cherry-pick touching it mutates live memory; commit memory on the target branch before switching
- Ticket IDs: T1=Correctness, T2=Steering, T3=Feature completion, T4=New systems, T5=Quality/observability, T6=Harness & yardsticks

## Architecture in one line
`AutobuilderService` (60s scheduler) → `CycleExecutor` → `AdaptiveExecutionEngine` (signal-driven, fitness-ranked; sole orchestrator since the T4-AI-2+5 meta-lane deletion) → 5 agents (planner / evolution / evaluation / merge / analysis) → `GoalPlannerService` + Neo4j graph

## Roadmap
- `roadmap-overview.md` — the thin one-line-per-ticket index over `roadmap.md`, grouped by tier; reconciled against the roadmap 2026-09-16 (3 stale markers, 8 absent tickets, 2 of them OPEN). (reviewed 2026-09-20)
- `roadmap.md` — full backlog and ticket bodies (source of truth). (reviewed 2026-09-20)
- `tier_definitions.md` — canonical tier meanings. (reviewed 2026-09-08)

## Meta / Process (YOU+ME work, not the product)
- `meta_process.md` — how YOU+ME divide/sequence/staff the work, separate from the product roadmap. "Model" = which Claude runs a session, not the autobuilder's local model; model choice is capability-vs-cost and advisory. (reviewed 2026-09-08)

## Project insights

Direction and orientation:
- `project_session_agenda.md`: the live ordering is the appended 2026-08-23 REORDER section at the end of the file; the S1-S6 sections above it are kept for their reasoning, not their sequence. Rationale, never a queue. (reviewed 2026-09-08)
- `project_resume_state.md`: latest field-run state and what the last session proved. Folded per-run, never split. 2026-09-20: T4-CU fired in the field (chain end-to-end, not a livelock) but the repair clause is still unfired; T1-GC filed+fixed, not field-verified; I refuted my own "rare by construction" claim. (reviewed 2026-09-20)
- `project_wave2_structure.md`: wave 2's five layers (substrate -> portability -> reconciliation -> consultation -> yardsticks) and gate-driven sequence; widening what the loop SEES must follow scoping what it CONSULTS. Rationale only. (reviewed 2026-07-26)
- `project_introspection_as_north_star.md`: the goal is a loop with a self-model it consults before acting; triage lens ("substrate or patch?"). (reviewed 2026-09-08)
- `project_three_disciplines_lens.md`: failures to priors, capabilities replace scar tissue, delete-subsumed. (reviewed 2026-07-09)

Design notes, read before touching their area:
- `project_t4j_design_note.md`: T4-J OPEN signal-system audit (decay vs not, obsolete vs missing; calibration LAST). Read before any signal tuning or deletion. (reviewed 2026-07-09)
- `project_signal_consumer_map.md`: T4-J emitter/clearer/reader map over 35 signal types + `scripts/signal_boundary_report.py`. The LLM is itself both consumer and emitter. (reviewed 2026-07-09)
- `project_signal_dispositions.md`: T4-J answer sheet — the decided disposition per signal. Open: analysis/planner tie-break, evaluation-prompt signal-blindness. (reviewed 2026-09-08)
- `project_t4j_perturbation_lab.md`: frozen-state lab; the decision-sensitive decay constants are the ROLE-identity signals, not the census shortlist. Read before signal tuning. (reviewed 2026-09-08)
- `project_t4bz_design_note.md`: T4-BZ signal re-architecture SHIPPED (all 6 slices closed, deletion complete, 2026-07-11 field run confirms no regression) -- family map and invariants I1-I12 stay the design record. Read for the migration rationale, not as an open task list. (reviewed 2026-09-08)
- `project_t4bo_design_note.md`: T4-BO signal-decay observability — two-snapshot death attribution, causality-preserving snapshot point. Read before touching signal plumbing. (reviewed 2026-07-09)
- `project_t4ai_design_note.md`: T4-AI — the meta lane was decorative; closed by deletion. Read before touching AdaptiveExecutionEngine/DeliberationService. (reviewed 2026-07-09)
- `project_t2ei_design_note.md`: T2-EI structure-first goal closure. Read before implementing T2-EI. (reviewed 2026-07-09)
- `project_t2ei_rule_interaction_map.md`: planner-rule map — FULL_COVERAGE and the coverage gate are one invariant. Read before touching any planner rule. (reviewed 2026-07-09)
- `project_t4ap_prompt_audit.md`: prompt bloat is raw-payload passthroughs, not contract conditionals; enumerated name-anchored lines win, dumps/concats/echoes lose; `scripts/prompt_block_report.py`. Read before ANY prompt-surface change or T2-EP. (reviewed 2026-07-24)
- `project_pattern_recognition_design_note.md`: the 6-slice gated plan for meaningful pattern recognition, plus per-class dispositions. Read before touching `analysis/pattern/` or filing any pattern/self-model ticket. (reviewed 2026-09-08)
- `project_target_domain_plugin_design.md`: T4-DC's field proof landed (a CLI target ran clean, 12/12, no HTTP anywhere) and REVISED the design from five seams to two -- an outcome-representation abstraction beyond HTTP status, plus a handful of leaked REST-specific strings fixable by grep. Read before adding any ecosystem-aware rule. (reviewed 2026-09-08)
- `project_state_time_travel_design_note.md`: T4-DQ checkpoint/rewind/branching, all four slices shipped. A rewind reproduces a STATE, not a TRAJECTORY, and must restore the CARRIED CYCLE CONTEXT or every streak restarts at zero. RULE: a new state component and the instrument that checks it ship in the SAME change. Read before any rewind/branching or T4-CR work. (reviewed 2026-09-19)
- `project_project_portability_vision.md`: import/continue/store/restore/merge a project's CODE layer (T4-CQ/CR/CS, all still open); the import half now has a real first adopted-project run (goal-setting endpoint, awaitingGoal state) and a field finding that a diff cannot survive a tree the loop did not write. Read before any import/export/bundle work. (reviewed 2026-09-08)
- `project_cross_run_knowledge_base.md`: T4-CJ slice 2 to T4-CN to T4-CM are ONE chain about reconciling graphs from DIFFERENT runs, not single-graph browsing. Read before touching any of them. (reviewed 2026-07-23)
- `project_reliability_plan.md`: rationale behind the reliability work (floor up, ceiling free). NOT a queue; everything it selected is filed or closed. (reviewed 2026-07-14)

Measurement and yardsticks:
- `project_yardstick_must_not_outrun_the_spec.md`: a yardstick may only assert what the SPEC states; unstated requirements are unfixable in-loop BY DESIGN. Audit yardstick against seed before attributing any score change. (reviewed 2026-09-08)
- `project_smoke_score_is_chained.md`: the 23-check score is a stateful CHAIN, not 23 measurements. Compare failing check SETS, never totals. (reviewed 2026-08-01)
- `project_first_perfect_smoke_score.md`: 23/23 is routine on the current build; the open question is whether the loop KNOWS it got there -- the best scores keep arriving on `deadline` terminals. (reviewed 2026-09-08)
- `project_goal_fulfilled_is_real_happy_path.md`: GOAL_FULFILLED fires routinely; use length-matched fulfilled runs as yardsticks. (reviewed 2026-07-09)
- `project_rejection_composition.md`: measured why mutations are rejected; the "model cannot see the signatures it calls" hypothesis is REFUTED. Read before filing anything about compile failures. (reviewed 2026-09-08)
- `project_completion_binding_constraint.md`: resolved — the constraint is planner coverage/convergence plus residual variance, two-factor. (reviewed 2026-07-09)
- `project_scenario_replay_harness.md`: freeze a real run's tree and replay it in milliseconds (`scripts/freeze_scenario.sh`). A frozen tree is ONE cycle's input and cannot express a SEQUENCE -- for that, rewind with the carried context. Read before reproducing a bad run by re-running it. (reviewed 2026-09-19)
- `project_coverage_guard_experiment.md`: runbook pattern for falsifiable off-switch experiments — metrics and decision rule stated in advance. (reviewed 2026-07-09)
- `project_t4bq_naming_consistency_ab.md`: naming-consistency A/B stood down; 3-pair minimum before claims; do not name a replacement lever without critical-path evidence. (reviewed 2026-07-09)

False completions and diagnosis lenses:
- `project_false_completion_unmasked.md`: the first — a correct fix unmasked verifier stub-blindness. Re-validate outcomes after correct fixes. (reviewed 2026-07-09)
- `project_false_completion_2.md`: unbootable jar; abstain is not pass at the completion edge; a gate can be satisfied instead of its requirement. (reviewed 2026-07-09)
- `project_false_completion_3.md`: DI-wiring defect invisible to compile gates; a correct REJECTED vote averaged away. (reviewed 2026-07-09)
- `project_false_completion_4.md`: the first by BOOKKEEPING — the closure ledger counts dispositions, never the seed. Taints the GOAL_FULFILLED population until audited. (reviewed 2026-08-28)
- `project_invented_facts_have_authorities.md`: every defect is an INVENTED FACT whose cheap authority sat unconsulted -- or, 2026-09-11, one the loop CROPPED and then trusted. Ask what the consultation actually SAW before concluding the model cannot read. (reviewed 2026-09-11)
- `project_capability_outruns_routing.md`: assume a missing behaviour is UNREACHED, not unbuilt. Trace to the link that does not fire before writing code. (reviewed 2026-08-24)
- `project_ticket_premises_expire.md`: a ticket's stated BLOCKER is a claim with a date on it; four expired unnoticed on 2026-09-08, and the fourth hid COMPLETED work credited under another ticket. Grep in both directions before designing, and fix the index line in the same edit. (reviewed 2026-09-08)
- `project_detector_went_silent_as_the_fault_spread.md`: a majority rule stopped firing when the anomaly became the majority. Ask any majority rule what it does then. (reviewed 2026-09-03)
- `project_completion_edge_gates.md`: completion edge is coverage then package then test; every diagnosis needs an actuator; brakes must count every evidence class. (reviewed 2026-07-09)
- `project_grounding_night_insight.md`: prefer belief-to-verification moves in triage. (reviewed 2026-07-09)
- `project_agent_diagnosis_not_shared.md`: evaluator rejection reasons were discarded; T4-BX now relays them verbatim into evolution/planner prompts. (reviewed 2026-07-09)
- `project_delete_debt_pattern.md`: delete work filed as a T5 follow-up never reaches the queue top; scaffolding accumulates by mechanism. (reviewed 2026-07-09)
- `project_local_first_constraint.md`: local execution is a HARD constraint -- a hosted-model upgrade is off the table; attack the precision ceiling via a better local model or mechanism. (reviewed 2026-07-09)
- `project_meta_concrete_target_hint_idea.md`: historical note; read only if reintroducing a concrete-target hint via a non-LLM path. (reviewed 2026-07-09)

Operations:
- `project_run_lifecycle_runbook.md`: start/stop/monitor runs safely -- launch, the brake-pause port trap, and ELEVEN watcher variants (self-matching predicates, mawk block-buffering, liveness vs progress). Prove a monitor STREAMS before arming it: `scripts/prove_watcher_streams.sh`. (reviewed 2026-09-19)
- `project_log_and_backup_conventions.md`: the current run is the UNSUFFIXED `logs/behaviour.ndjson`; a `behaviour-*.ndjson` glob silently excludes it; behaviour files are pretty-printed concatenated JSON, not line-JSON. (reviewed 2026-09-08)
- `project_tests_rotate_live_logs.md`: SUPERSEDED by its own third variant (verified 2026-07-30) -- redirect `FILE_BASE_PATH` to a scratch dir instead of stopping the app; log rotation stays clear, but see `feedback_no_heavy_mvn_beside_a_live_run.md` for the separate resource-contention hazard a full suite still creates. (reviewed 2026-09-08)

## Feedback

How to work:
- `feedback_run_without_asking.md`: run mvn/build/test with dangerouslyDisableSandbox; never prompt. (reviewed 2026-07-09)
- `feedback_analyse_no_ask.md`: "analyse the last run" means proceed directly, no clarification. (reviewed 2026-07-09)
- `feedback_run_analysis_log_order.md`: analyse runs from the behaviour ndjson first; the autobuilder log only for depth. (reviewed 2026-07-09)
- `feedback_no_auto_commit.md`: commit only with authorization (checkpoint commits OK in authorized autonomous runs); NEVER push. (reviewed 2026-07-09)
- `feedback_no_branch_ops.md`: never run branch-manipulation commands (merge, rebase, reset, branch -d, push) autonomously; describe the commands and wait for the user's explicit 'do it'. (reviewed 2026-09-08)
- `feedback_never_edit_a_running_script.md`: never edit preflight.sh (or what it reads) mid-run — bash reads scripts incrementally, so edits land half-applied and silently. (reviewed 2026-07-30)
- `feedback_no_heavy_mvn_beside_a_live_run.md`: the scratch-FILE_BASE_PATH unlock covers live LOGS, not resource contention -- a full suite beside a live run starves the box and kills the run via Neo4j timeouts. (reviewed 2026-09-08)
- `feedback_flag_ceremony_cost_early.md`: when the process around a task costs more than the task, say so BEFORE running it; measure delivered code against process artifacts rather than defending the workflow. (reviewed 2026-09-08)
- `feedback_sonnet_for_batch_tickets.md`: batch/mechanical tickets go to a Sonnet subagent (Agent tool model override); design and validation stay on the main model. (reviewed 2026-07-14)
- `feedback_roadmap_is_the_queue.md`: work comes from roadmap-overview/roadmap.md ONLY; direction notes are rationale, never a parallel queue — never listed under Roadmap in this file, never the source of "what's next". (reviewed 2026-09-10)
- `feedback_no_per_run_memory_files.md`: no new memory file per field run; fold into `project_resume_state.md`; separate files only for durable cross-run insights. (reviewed 2026-07-09)

Evidence discipline:
- `feedback_baseline_before_accuracy.md`: FOUR ways a predictor-hunt lies — unstated baseline, best-of-N reported as one test (permutation p), extremes-only comparison (best rho with leave-one-out BELOW baseline), and a denominator that means different things per row (this one manufactured T6-R's only significant result and survived 12 tests). A correlation and a discriminator are different claims. (reviewed 2026-09-10)
- `feedback_verify_population_before_claim.md`: before any all/most/never claim, scan the full corpus including backups and dedupe by timestamp. (reviewed 2026-07-09)
- `feedback_grep_the_behaviour_not_the_name.md`: a grep finds a SPELLING, a claim is about a BEHAVIOUR; four instances in one session (a dead knob generalised, one call form mistaken for all readers, an invented token whose absence read as zero, a field dismissed by its name). Run the predicate against a known-POSITIVE case before reporting any zero. (reviewed 2026-09-13)
- `feedback_count_events_not_log_lines.md`: count the EVENT, not log lines mentioning it; re-rendered records inflated two ticket headlines 23x and 3x. (reviewed 2026-08-01)
- `feedback_dont_group_defects_by_vocabulary.md`: "N instances of the same pattern" is a claim about MECHANISM -- verify each member's mechanism before grouping; a shared description is not a shared cause. (reviewed 2026-08-29)
- `feedback_dont_evaluate_a_build_with_older_runs.md`: a window is a rate only if the build is constant across it; this session's runs evaluate the PREVIOUS build, so a fix's verdict belongs to the next session. (reviewed 2026-08-24)
- `feedback_verify_diff_is_substantive.md`: before reporting an achievement, prove what input now yields different output; green tests on a behaviour-preserving diff is not progress. (reviewed 2026-09-08)
- `feedback_verify_at_decision_timestamp.md`: diagnose against the state AT decision time (per-cycle snapshots), not the final artifact. (reviewed 2026-07-09)
- `feedback_activity_not_progress.md`: a mechanism firing is not the same as helping; validate that the outcome moved; source "what's missing" from structure, not string-scraping. (reviewed 2026-07-09)
- `feedback_replay_over_batches.md`: never validate a change with a small-N live batch under measured variance; use deterministic corpus replay plus passive watch items. Live batches only for rare strategic questions, with N and the decision rule stated in advance. A definition of done names its instrument when the claim is made. (reviewed 2026-09-08)
- `feedback_short_field_run_after_context_change.md`: a green suite and clean replay cannot see integration defects; do a short live run after any cycle-context or persisted-record change. (reviewed 2026-07-30)
- `feedback_llm_probe_realistic_context.md`: synthetic probe contexts must match the real run distribution or they manufacture false findings. (reviewed 2026-07-24)
- `feedback_runs_are_variant_by_design.md`: runs are variant BY DESIGN; keep cross-run resemblances family-level, never "same as run N". (reviewed 2026-07-09)
- `feedback_stabilize_before_tuning_signals.md`: stabilize the substrate before signal-dynamics redesign; tune only when convergence is reproducible (gate met 2026-07-09). (reviewed 2026-07-09)

Reasoning and correction:
- `feedback_calibrate_not_flip.md`: do not over-retract a grounded diagnosis on one noisy result; resist single-cause commitment on multi-factor data. (reviewed 2026-07-09)
- `feedback_no_defensive_recast.md`: after a correction, do not pivot to a fresh confident framing; gather data first. (reviewed 2026-07-09)
- `feedback_capability_gap_not_prohibition.md`: model limitations as revisable capability gaps -- not permanent bans, and not TTL'd stored facts when derivable live. (reviewed 2026-07-09)

Ticket and scope discipline:
- `feedback_ticket_tiering.md`: tier by mechanism — designed-but-dumb policy is T2, missing capability T4, real logic bug T1. (reviewed 2026-07-09)
- `feedback_ticket_definition_of_done.md`: tickets state their DoD and close then; residuals stay NOTES. **And the filing reflex is the bigger trap** — 2026-09-13: four tickets filed in one session, three deleted or folded on review; fix it or drop it, file last. (reviewed 2026-09-13)
- `feedback_append_dont_rewrite_tickets.md`: ticket updates APPEND (status, date, deviations); the filed body stays untouched and visible — never rewrite into an as-built paragraph. (reviewed 2026-07-10)
- `feedback_ship_full_scope.md`: ship a capability in one scope — build, rewire every caller, delete dead code; never split into a T5 follow-up. (reviewed 2026-07-09)
- `feedback_instrument_needs_an_actuator.md`: an observability ticket ships its actuator in the SAME scope; prose in a prompt is not an actuator; readout-first must be argued for and carry the condition that wires it. (reviewed 2026-07-31)
- `feedback_one_invariant_one_home.md`: two patches for one invariant at two sites means a missing capability; file one capability ticket. (reviewed 2026-07-09)

Code and design constraints:
- `feedback_no_internal_ids_in_model_facing_text.md`: an identifier belongs in a prompt only if that prompt DEFINES it -- ticket ids and gate slugs never do and are noise; three of ~35 signal names are defined and parsed back, so those are an interface. Corrected after asserting the signal half without checking. (reviewed 2026-09-17)
- `feedback_no_target_domain_in_framework_strings.md`: never bake target-project class or package names into framework-level production strings. (reviewed 2026-07-09)
- `feedback_no_ecosystem_jargon_in_framework_code.md`: never name a specific framework or version in framework-level identifiers, comments or strings; ecosystem knowledge lives in data tables. (reviewed 2026-07-09)
- `feedback_no_hardcoded_ecosystem_data.md`: even data tables are debt; prefer discovery (pom -> classpath -> Maven Central), and row-adds always file the discovery follow-up. (reviewed 2026-07-09)
- `feedback_ecosystem_gated_heuristics.md`: ecosystem-specific heuristics must self-gate on the ecosystem's own markers; no opinion on marker-free projects. (reviewed 2026-07-09)
- `feedback_relative_thresholds.md`: triggers and limits relative to the run's environment; constants only for measurable environmental facts. (reviewed 2026-07-09)
- `feedback_string_consumption_path.md`: "structured, do not string-scrape" governs CODE consuming strings for control flow, NOT prose relayed into another agent's PROMPT. (reviewed 2026-07-09)

Watchers:
- `feedback_monitor_every_terminal.md`: a monitor must match EVERY terminal state; stopping one for noise means re-arming a narrower one, never going blind. (reviewed 2026-07-27)
- `feedback_escalate_unrecoverable_stagnation.md`: watchers must detect periodic failure patterns and inform early, not just terminal markers and silence. (reviewed 2026-07-09)

Writing:
- `feedback_curly_quotes.md`: no curly quotes or stray non-Latin characters; ENFORCED by `scripts/charset_guard.py` as a PostToolUse hook (em dashes, box-drawing and arrows are legitimate). `jq` is not installed; hooks use python3. (reviewed 2026-08-28)
- `feedback_neutral_vocabulary.md`: no courtroom or military wording, no emoji decorations. Records the amputation-to-truncated rename, why `rehabilitationVotes` was NOT renamed (persisted schema key), and the 2026-09-15 emoji drift: 64 put into the roadmap in one session against a rule written in July, plus the trap of citing my own accumulated drift back as the convention. (reviewed 2026-09-15)
- `feedback_no_symbolic_enumerations.md`: never use circled-number characters anywhere; name each item instead -- positional tokens carry no meaning on re-read. (reviewed 2026-07-30)
- `feedback_no_invented_labels.md`: no invented structural labels or role-nouns; use participles and descriptions. Do not silently truncate criteria for cost. (reviewed 2026-07-09)
- `feedback_no_co_mention_pairing.md`: do not co-list unrelated tickets across reports; name the category, not a recurring pair. (reviewed 2026-07-09)
- `feedback_tts_marytts.md`: narration is in-JVM neural TTS; MaryTTS rejected. (reviewed 2026-07-09)
