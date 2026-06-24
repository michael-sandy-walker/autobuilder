---
name: feedback-stabilize-before-tuning-signals
description: "Don't reach for fundamental signal-dynamics redesign (T4-BO decay constants, T4-AL, brake limits) to fix a residual while the system still produces incoherent output — stabilize the substrate first, tune dynamics only when convergence is reproducible. User steer 2026-06-24."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 537098c0-bf1f-4469-b00e-918397d9d63f
---

When a run's residual failure ("healthy but doesn't fulfill in the window") tempts a **signal-dynamics** fix — re-tuning `decayPerCycle` constants (T4-BO), the signal redesign (T4-AL), or a brake/limit constant — STOP if the system's *output* is still structurally incoherent (the 2026-06-24 artifact: minted-synonym duplicate types, a thrown-but-unhandled exception → 500, missing service layer, fragmented packages).

**Why:** tuning signal dynamics on an unstable substrate is fitting to noise — the constants would be calibrated against runs whose outcomes are dominated by incoherence, not by signal timing. It is a *fundamental* change to how the loop weights/decays evidence, and doing it prematurely drifts the project further off. (Also: a specific brake limit like `root-convergence-cycle-limit` is NOT even a T4-BO decay constant, so T4-BO wouldn't inform it anyway.)

**How to apply:** sequence = stabilize the artifact (coherence/correctness — e.g. T4-AW output reviewer) FIRST; only once honest convergence is *reproducible* do signal-dynamics measurement/tuning become meaningful. When you catch yourself pairing a residual with "measure/tune the signals," check first whether the residual is actually structural. (2026-06-24: I proposed "pair T2-EH with T4-BO before touching the brake limit"; the user corrected it — the brake was already de-fanged by T2-EH's verify-before-pause, and the real gap was artifact coherence, not signal timing.)

Distinct from [[feedback-relative-thresholds]] (how to choose a constant) and [[feedback-activity-not-progress]] (validate the outcome moved) — this is about *ordering*: don't tune the dynamics layer until the layer beneath it is stable. Pairs with the caution in [[project-t4bq-naming-consistency-ab]] that even the coherence fix is correctness, not a proven completion lever.
