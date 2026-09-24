---
name: reference-undone-ideas
description: "Pointer list of the user's most interesting ideas that are NOT built, each with its source document and line in ~/Documents/autobuilder. A reference, never a queue: work still comes from the roadmap."
metadata:
  type: reference
---

Compiled 2026-09-23 on the user's request (TODO_20260921: "read documents within Documents/autobuilder
and write reference file regarding most interesting undone ideas (ref. document names and line
numbers)"). Paths are relative to `~/Documents/autobuilder/`; `file:N` is the line of the user's own
words. Status says whether the roadmap already carries the idea, checked by grepping `roadmap.md`
on the same day. **This is not a work queue** ([[feedback-roadmap-is-the-queue]]): an idea enters
the queue only by being filed as a ticket.

How it was found: every user prompt line (the transcript prompt marker) across 373 transcripts, filtered to prompts over 100
characters carrying an idea word (idea, maybe, what about, could we, and so on), 294 hits, plus the
hand-written TODO/notes files. Repeated sessions copy the same prompt into several files; each idea
is cited once, at its earliest clear statement.

## Self-hosting and generality (the stated north star)

| idea | source | status |
|---|---|---|
| Make autobuilder modular and let its own modules be the toy cases ("!!!") | `TODO_20260921:62`, combined with the LLM nudge at `:97` | unfiled; nearest is T4-CF/T4-CG, both placeholders with unmet gates |
| Let autobuilder incrementally build parts of itself | `TODO_20260825:7`; plan request `analysis_20260903:753` | T4-CF placeholder |
| Load a raw project, an existing autobuilder project, or start fresh, choosable from a landing page or a JVM flag | `analysis_20260904_20260905:998`, `:1022` | T4-CQ open; the landing page and the launch choice are in no ticket |
| Set the floor and ceiling of a run dynamically instead of per toy case | `analysis_toycases_20260910:617` | T6-S audited "not yet"; the ceiling half has no owner |
| Apply recursion to everything that defines the project | `analysis_T1-EX_T2-DY_new_ticket_T4-AJ_20260605:3204` | unfiled |

## Using the model where determinism runs out

| idea | source | status |
|---|---|---|
| "Nudge" non-parseable criteria with the LLM or a heuristic instead of more hand-written readers | `analysis_T2-FL_20260923:4164` | recorded as NEXT in the resume state (LLM canonicalisation at authoring time), not built |
| An "autobuilder-parsed" intermediate of the seed, hashed, regenerated when edited out of format | `analysis_20260911_20260912:1981`, `:2010`, `:2041` | not in the roadmap |
| Let the local LLM split the seed into items instead of fixed `and`/clause rules | `analysis_20260911_20260912:1932`; `review_T4-CQ--T2-FI-T6-S-etc_202609x:23` | T6-S note only |
| Derive a problem's complexity X and require X confirmations (possibly leaving input out) | `analysis_20260911:550` | not in the roadmap |
| Divide and conquer: link information in meta layers so the model never needs all of it | `analysis_T5-AF_chain_20260617:2059` | not filed as such |
| A "meta narrator" and better use of externalized memory for the model's decisions | `TODO_20260921:60`, `:103` | not in the roadmap; T5-BI (narrate the model's own prose) is the nearest |
| Ask an LLM whether fast-growing new goals are the same goal (sampled over a time window) | `analysis_new-horizons_20260923:3031` | note on T4-AJ, deliberately not a ticket |

## Introspection and learning from its own history

| idea | source | status |
|---|---|---|
| Meta graph-traversal pattern recognition: remember walked paths, record the overall shape of the walks | `TODO_20260921:49` | not in the roadmap; the pattern-recognition design note is the nearest home |
| Learn from the YOU+ME process: compare periods, find thresholds that widen the view | `analysis_opus-5_qwen3.8_T4-D...-queue_stabilization_20260830:698` | unfiled |
| Periodically walk all goals and retire meaningless ones | `analysis_fable-5_rest_20260703_until_20260704:94` | partly T4-AJ; no periodic walker |
| When a completion is found false, backtrack the merges and criteria that rested on it and replace them | `analysis_opus-5_qwen3.8_T4-D...-queue_verification_20260829:1065` | partly (T4-CU refuses, T1-FG reopens); no backtracking of dependent merges |
| Signal tuning by short trial-and-error bursts over parameter combinations | `analysis_T4-J_20260709:36` | perturbation lab built; calibration deliberately last |
| Planner decides dynamically between breadth-first and depth-first | `TODO-20260726:14` | T2-EI made it depth-first; the dynamic choice is unfiled |
| Hierarchical, more granular scoring per goal level | `evaluation_20260415:1149`; again `analysis_20260911:976` | T4-H declined `[n]`; the 2026-09-11 re-ask is a note on T6-H |

## Mechanics

| idea | source | status |
|---|---|---|
| Real diff technology instead of whole-file LLM rewrites | `TODO_20260921:113` | not in the roadmap |
| Dynamic capability injection per detected technology (dependency download and so on) | `TODO_20260921:112` | partly: T1-GF dependency discovery for one ecosystem |
| Several compile/build attempts in one cycle to settle cross-dependencies | `analysis_phase_a_b_20260611:515` | partly (build-fix loops); no in-cycle cascade |
| Walk back to an earlier mutation and recover the file that would have fixed a later failure | `analysis_new_tickets_20260523:117` | partly: T4-BE restages whole deferred mutations |
| Keep the most promising snapshot by periodically asking "are we still on track?" | `analysis_T6-A_20260724:1764` | T4-CP declined; this framing was never tried |

## Interface and operator

| idea | source | status |
|---|---|---|
| Comment on goals or code in the interface, queue the comments, process them in one LLM batch | `TODO_20260921:47` | not in the roadmap |
| Real-time build view, spoken, with progress elements | `really_cool_20260503:18` | partly: mission control, TTS; T5-BO open |
| Fast-forward / slow-down controls for a run | `todo_20260708:13` | not in the roadmap |
| Layered TTS tracks so speech is never cut off | `analysis_T4-AR_T5-BF_T4-CC_T2-EN_T5-J_20260616:4096` | not in the roadmap |
| In-browser editor for mutation and output files, editing merged mutations | `graph_features_20260428:1`, `:915` | not in the roadmap |
| Show criteria in the goal graph with lines flowing to the ones being worked on; show the edited file briefly | `review_T4-CQ--T2-FI-T6-S-etc_202609x:4`, `:5` | partly T5-BO |

## Far horizon (recorded, parked by the user)

| idea | source | status |
|---|---|---|
| Graph parts as identities with their own narratives and voices that talk to each other | `TODO-20260726:1` | T4-DE parked |
| Translate those processes into physical 2D or 3D actions | `TODO_20260724:7` | unfiled, explicitly far-future |
