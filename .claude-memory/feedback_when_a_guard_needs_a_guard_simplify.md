---
name: feedback-when-a-guard-needs-a-guard-simplify
description: When a mechanism needs a rule to protect the rule below it, step back and propose the simpler cut; the user calls such layering over-complicated and is right.
metadata:
  type: feedback
---

When a design keeps growing guards for its own guards, stop and propose a SIMPLER CUT instead of the next layer --
and say plainly which parts would go (usually deletion).

**Why:** 2026-11-09/10, T4-EK, twice. (1) Per-run guard controls needed a premise check, the premise check needed body
shapes, body shapes opened a hole needing an asymmetry rule and a wholesale-breakage rule; user: "the whole control
thing just makes the logic over-complicated" -- removed, the runs kept as evidence. (2) Three entry kinds each with
their own syntactic attribution parser plus a precedence rule; user: "Maybe it's a bit too complex to divide ... into 3
separate categories?" -- simplified to observed attribution and a call/start split, mostly deletion, and it fixed a
real defect on the way.

**How to apply:** the signal is "each layer guards the one below" or "the same idea re-implemented per kind". Offer
2-4 real options with the deletion count, recommend one, and prefer the option where one observed rule replaces several
parsed ones. Related: [[feedback-design-for-the-class-not-the-case]], [[feedback-options-before-building-and-stop-weak-batches]].
