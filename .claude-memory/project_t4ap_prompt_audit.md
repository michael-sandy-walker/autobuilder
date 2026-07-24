---
name: project-t4ap-prompt-audit
description: "T4-AP Phase B prompt audit (2026-07-24) — block-level composition of every LLM prompt; bloat lives in raw-payload passthroughs, NOT contract conditional blocks; compact-encoding taxonomy"
metadata: 
  node_type: memory
  type: project
  originSessionId: 80ff4f1f-e032-46c7-bba0-923ec7a61618
---

# T4-AP prompt audit — what the corpus says about prompt composition (2026-07-24)

**Instrument:** `scripts/prompt_block_report.py` — extracts `[USR]` prompts from run logs, classifies by content shape (immune to retry-order drift vs the behaviour telemetry), segments into named blocks, aggregates fires × est-tokens. Real per-call totals live in behaviour `llm_call` events (role, promptTokens); the script's value is per-BLOCK attribution, which telemetry lacks. Full prompts are only in `autobuilder*.log` (`[USR]` marker); system prompts are never logged — compute them from the contracts (jshell over `target/classes`).

**Core finding (stable across three fulfilled runs 07-09/07-23/07-24):** the per-state conditional blocks in the `*ProtocolContract`s (T4-BX relay, criteria progress, analysis observations, locus-stuck…) are LEAN — 58–170 est tokens, firing only in their state. The bloat was in **raw-payload passthroughs**:

1. **Scope classifier `String.valueOf(ctx.projectDiff())`** — 29–35% of each run's user-prompt volume; hit the 32,768 context cap (truncated classifier prompt). FIXED 2026-07-24: declaration-level `DiffSummarizer.summarizeForPrompt`.
2. **Interpretation artifact history** — full artifact content concat, ~12% (→ [[T2-EP]]).
3. **Evolution last-raw-response echo** — verbatim previous response, ~5–7% (→ T2-EP).
4. **Static rule contracts** re-sent per call (planner ~3.7k, evaluation ~2.9k tokens/call; ~25% of total incl. sys prompts). Many rules are state-conditional in text, unconditional in transmission. Revisable NOTE in the T4-AP closure, not built.

**Compact-encoding taxonomy (the "communicate state in least tokens" answer):** the loop already owns validated encodings — structural-diff declaration tree, DIFF SUMMARY counters+paths, criteria ✓/✗ enumerations, errorKind+symbol diagnostics, signal NAME=strength pairs. Winning shape = **enumerated, deterministic, name-anchored lines**; losing shape = toString dumps / full-content concats / verbatim echoes. Judge any new prompt payload against this before it ships.

T4-BN-3's focused test-repair prompt is the working precedent for a real per-pattern template (replaces the whole assembly under one signal); use it as the model if per-state routing is ever revisited.

Related: [[project-t4bz-design-note]] (signal readouts), [[feedback-replay-over-batches]] (one surface per run when acting on T2-EP).
