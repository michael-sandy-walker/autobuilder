---
name: project-rejection-composition
description: "Measured composition of why mutations are rejected, over 33 run corpora — compile failures dominate at 62.6%, and the leading hypothesis for them (the model cannot see the API it calls) is REFUTED"
metadata: 
  node_type: memory
  type: project
  originSessionId: f54dc5b0-d9fb-4267-bae8-a3bce5956657
  modified: 2026-08-02T10:35:34.304Z
---

Measured 2026-08-01 over the 33 run corpora under `/tmp/preflight_runs` (all runs with a behaviour log): **1530 cycles, 952 proposals, 438 rejections, 502 merges.**

Why proposals are rejected:

| share | cause |
|---|---|
| 62.6% | compile failure (the probe, plus fragmentation) |
| 11.6% | the mutation does not address its own subgoal / touched an unrelated file |
| 9.4% | `ScoreRegressionHeuristic` |
| 5.0% | no meaningful change |
| 5.0% | `MergeRegressionHeuristic` |
| 4.6% | other evaluator prose |

Inside the compile-failure bucket, from `blocker_extraction_outcome` (n=298): **52.3% `REFUSED_AUTHOR_ERROR`**, 22.1% `DEFERRED` (the injector chain can act), 15.1% `NO_DEFERRABLE`, 10.4% `REFUSED_UNKNOWN`. The dominant error shapes are `cannot find symbol` (126), `incompatible types` (96), `symbol: method` (83), `method cannot be applied to given types` (47).

So roughly **a third of all rejections are the model writing code that does not type-check against symbols that already exist.**

**The obvious hypothesis is refuted; do not file it.** "The generator cannot see the signatures it is calling" is wrong. Measured with `scripts/prompt_block_report.py` over run `2026-07-31_03-50-01`: of 91 evolution prompts, `STRUCTURE_SUMMARY` fires **91/91** at ~1012 est tokens, and `extractMethodSignaturesResolved` keeps whole declaration lines — parameter types included. Verified against a rendered prompt in the log, not inferred from the code: `- public void setTitle(String title) {`. (`REQUIRED FILES`, the narrower per-file block, fires only 10/91 — but it is not the channel that matters, because the structure summary already carries the same information more broadly.)

The information arrives and the code still does not type-check. That makes this bucket another instance of the shape [[feedback-instrument-needs-an-actuator]] names, not an information gap — with the difference that here the actuator would have to constrain generation against a compiler, which is what the compile probe already does after the fact.

**REFINED 2026-08-02 — the compile failures are not spread evenly; they are concentrated in ONE resource, and the missing symbols are mostly types nobody has written yet.** Measured with `scripts/attention_report.py` over 25 runs, paired within each run so run length and difficulty cancel: the model's own output fails the pre-merge probe **61% of the time on loan work against 24% on book/member work**, and after the injector's repair it is **still red 54% vs 14%**. Of 2574 attributed missing symbols, book/member misses are importable library annotations (`NotBlank` 54, `Pattern` 41, `Valid` 35, `Size` 33) — precisely what `symbol-import`, the dominant injector rule at 349 fires, exists for. Loan misses are project-internal types that do not exist yet (`Loan` 236, `BookRepository` 128, `LoanAlreadyReturnedException` 106, `LoanService` 96) plus **31% missing METHODS on those invented types**. No import can fix a type nobody wrote.

So the third of all rejections above splits in two: the part landing on already-existing symbols is the refuted information-gap story, and the larger part, on the hardest resource, is a **missing-type-closure** story — the model writes the caller before the callee, across several files at once. That is why this bucket resists prompt-side fixes. See T6-L for the full chain from attention to smoke outcome, and `T2-EZ` (2026-08-01) for the actuator that stopped discarding the skeletons.

**How to use this:** before filing anything about compile failures, note that the prompt-side answer has been measured and refused, and that the bucket is resource-concentrated — a fix aimed at the average proposal is aimed at the wrong one. The live options are a pre-generation constraint (checking a proposal against known signatures before it costs a probe), better use of the 22.1% the injector already classifies as deferrable, or accepting it as a model-capability limit and spending the cycles elsewhere. Related: [[project-completion-binding-constraint]], [[feedback-activity-not-progress]].
