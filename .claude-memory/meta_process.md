---
name: meta-process
description: "YOU+ME development-process tracking — the META axis (how we divide, sequence, and staff the work of building Autobuilder), separate from the product roadmap (T1-T6). 'Model' here = which Claude runs a session (Fable/Opus/Sonnet/Haiku), NOT the autobuilder's local model (qwen3-coder)."
metadata:
  node_type: memory
  type: project
---

# Meta / Process — YOU+ME work (not the Autobuilder product)

This file tracks the **development process**: how the user and the assistant divide, sequence, and staff the work of building Autobuilder. It is a DIFFERENT axis from the product roadmap — `T1-T6` are about what we build/measure IN the product; `M-` items are about how WE work. Nothing here is autobuilder code, and **"model" here means which Claude runs a session (Fable 5 / Opus / Sonnet / Haiku) — NOT the autobuilder's local model (qwen3-coder)**. The roadmap stays the single product queue; M-items never re-order or replace it — they annotate execution.

Status markers: use the roadmap's status legend (`roadmap.md` — currently `[ ]` open · `[~]` in progress · `[x]` done · `[>]` deferred), not a separate list here.

## M-A — Refactor roadmap-overview.md back to a thin current-status index

- [ ] **M-A: the overview drifted from its own contract into an append-only narrative log — rephrase every single-ticket line to current status only, migrating any not-elsewhere-recorded prose into the roadmap body / resume-state rather than losing it.**
  - **Problem:** `roadmap-overview.md`'s own header states "thin index: one-liner per open ticket… recent-state narrative lives in `project_resume_state.md`, NOT here… roadmap wins on disagreement." In practice dated narrative blocks accumulated (some single lines run to thousands of words), so the overview became a SECOND narrative log that duplicates resume-state and carries frozen "filed"/stale mentions. That is the ambiguity/noise the user flagged: reconstructing current status means scanning "newest-mention-wins" across walls of text — exactly the operation an LLM botches under context load, and it burns tokens anchoring on stale lines.
  - **Not a conflict with [[feedback-append-dont-rewrite-tickets]]:** that rule governs ticket BODIES (the reasoning trail). The overview is the INDEX and is meant to be rewritten to current state. This ticket rewrites the index and PRESERVES the trail by migration, never deletion.
  - **DoD:** every overview line is one thin current-status pointer (ticket id + one-line state + tier); dated narrative removed; any prose not already in the ticket body or resume-state is migrated there FIRST; a no-information-loss check (diff removed prose against what now exists in body/resume-state); the header contract re-asserted; each line's status verified against the roadmap CHECKBOX, not the old narrative.
  - **Work nature:** mostly mechanical per-line rewriting, plus one judgment call (which prose is relevant and where it migrates). Delegating the trivial rewrites to a subagent on occasion is reasonable — but the staffing/model choice is M-B's call, not pre-assigned here. Multi-session OK (user: "in one or more following sessions").
  - **Filed 2026-07-22 (user).** Origin: the T4-CO deferral surfaced that the overview's status can lag the roadmap checkbox; root cause is the index-vs-trail drift above.

## M-B — YOU+ME model-usage plan (advisory, capability-vs-cost)

- [ ] **M-B: an advisory, cost-aware plan for which Claude runs which work + wave-2 session bundling — annotating the roadmap's order, never replacing it.**
  - **Capability order (user's assessment, snapshot 2026-07-22):** Fable 5 > Opus 4.8 > Sonnet > Haiku. Fable 5 is the most capable, including for the complex/intelligent/autonomous-reasoning part of a task (design, hard diagnosis, architecture). Point-in-time — new model releases can reorder this; re-confirm before relying on it.
  - **But capability is not the only axis — COST is, and as of 2026-07-22 it pulls the other way.** Fable 5 requires far more resources: it bills by CREDITS (API tokens) rather than the paid-account tokens the other models draw on, so it is expensive both monetarily and environmentally. **This is a 2026-07-22 snapshot, not a durable fact** — likely not permanent; re-weight the plan as it changes, and if this reads as stale, re-check the live billing/capability picture before applying it.
  - **So the heuristic is capability-vs-cost and ADVISORY (this is why the user asked for a plan, not a rule):** do NOT default to the most capable model. Prefer the least-expensive model that can do the task well, and RESERVE Fable 5 for the genuinely-hardest intelligent work where its edge is worth the cost. A single ticket usually SPLITS — the intelligent part may merit Fable/Opus, the routine coding part drops to Sonnet/Haiku. Generalizes [[feedback-sonnet-for-batch-tickets]] (mechanical → cheaper model, design/validation → more capable) into a full ladder with cost as a first-class input. Subagents occasionally, not by default.
  - **Revisable from outcomes AND cost:** calibrate from the session-outcome record already in memory (the dated Fable-5 vs Opus-4.8 blocks — what each produced on which task type) together with the current billing/resource picture, not a frozen ranking.
  - **Not a parallel queue ([[feedback-roadmap-is-the-queue]]):** M-B is EXECUTION metadata layered on the roadmap's existing order — HOW to run the queue (which model, batched how), never WHAT'S next. The roadmap ordering stays authoritative.
  - **Deliverable:** the written capability-vs-cost model heuristic + split-within-a-ticket rule; an advisory session-bundling for wave 2 (which slices batch into one autonomous session vs need a human checkpoint between them); tentative model suggestions for the near-term items (wave 2: T4-CN slice 1, T4-CM ①/②③) marked "re-decide at pickup."
  - **Filed 2026-07-22 (user).**
