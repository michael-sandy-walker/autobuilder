---
name: project-rejection-composition
description: "Measured composition of why mutations are rejected, over 33 run corpora — compile failures dominate at 62.6%, and the leading hypothesis for them (the model cannot see the API it calls) is REFUTED"
metadata: 
  node_type: memory
  type: project
  originSessionId: f54dc5b0-d9fb-4267-bae8-a3bce5956657
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

**How to use this:** before filing anything about compile failures, note that the prompt-side answer has been measured and refused. The live options are a pre-generation constraint (checking a proposal against known signatures before it costs a probe), better use of the 22.1% the injector already classifies as deferrable, or accepting it as a model-capability limit and spending the cycles elsewhere. Related: [[project-completion-binding-constraint]], [[feedback-activity-not-progress]].
