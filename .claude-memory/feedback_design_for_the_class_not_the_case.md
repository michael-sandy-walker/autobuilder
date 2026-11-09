---
name: feedback-design-for-the-class-not-the-case
description: Design for the whole class of projects (enumerate the abstract kinds, one pipeline with adapters); toy cases only measure. Per-kind or per-case designs are the mistake.
metadata:
  type: feedback
---

When a mechanism has to work across kinds of projects, enumerate the ABSTRACT kinds first and design one pipeline
for all of them, with the kinds as small adapters; never a design per kind or per toy case. Toy cases are
instruments: their comparison rules are per case by necessity, the mechanism is not.

**Why:** 2026-11-09, T4-EK slice (a2): I proposed a library design measured on labelmaker and said "CLI gets its
own design". User: "We shouldn't build toy-case-specific designs. This should be general designs." It led to three
entry kinds (library-style / service-style / command-style -- names chosen by the user) under one pipeline, with
project kinds (web app, framework, CLI, GUI, ...) only as a COVERAGE CHECK. The same session's earlier slip was the
same shape: HTTP features grouped by path segment while library features were per method -- two rules for one idea.

**How to apply:** before proposing a design, ask "which other kinds of project would need this, and does my rule
read the same for all of them?" If a rule names one kind's mechanics (routes, argv), it belongs in an adapter.
Present names for new concepts as a few coherent SETS with trade-offs; the user picks (they cared about names
being distinguishable and readable by others). Related: [[feedback-tickets-direct-loop-as-toy-cases]],
[[feedback-no-target-domain-in-framework-strings]], [[feedback-no-hardcoded-ecosystem-data]].
