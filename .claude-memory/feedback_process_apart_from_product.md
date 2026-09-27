---
name: feedback-process-apart-from-product
description: how the user and the session work (reviews, packets, references, session tooling) lives on the process axis and in process/, never filed as product tickets or built into src/ or the product's scripts/
metadata:
  type: feedback
---
Work about HOW we work -- review stages, packets, blind references, session measurement tooling -- is recorded
in `meta_process.md` (M- entries) and kept in `process/`. It is not filed under a product ticket and not built
into `src/` or the product's `scripts/`. A product ticket carries only what changes Autobuilder's behaviour.

**Why:** 2026-09-27 the user, after the port review stage had been filed under T4-CF and its tools put into
`scripts/`: "It seems, that the meta tasks are directly implemented as code. It feels like it's mixed together."
The toy cases already mix layers (the loop builds parts of Autobuilder's own meta layer); keeping the review
machinery apart is what keeps that readable.

**How to apply:** before filing or placing anything, ask whether it changes what the loop does (product: roadmap
ticket, `src/`, `scripts/`) or how the operator and session work (process: `meta_process.md`, `process/`). A
product capability that happens to be discovered during process work (e.g. the loop is information-starved) is
still filed as product. Related: [[feedback-operator-reviews-ports]], [[feedback-roadmap-is-the-queue]].
