---
name: sonnet-for-batch-tickets
description: Run not-so-complex batch/mechanical tickets (e.g. T5-AG sweeps) on a Sonnet subagent instead of the main-session model
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 9b9951ff-b48a-49a1-b408-4d3bacce986c
---

For not-so-complex batch tickets (mechanical renames, sweep work — the user named T5-AG as the example, 2026-07-14), don't burn the main-session model: delegate execution to a subagent with the Agent tool's `model: "sonnet"` override.

**Why:** cost/capability matching — a ~200-occurrence mechanical rename sweep doesn't need the top model; the user explicitly asked for this.

**How to apply:** When a queued ticket is batch/mechanical, note the model choice in the ticket body (append, per [[append-dont-rewrite-tickets]]) and at execution time spawn a Sonnet subagent for the mechanical families. Judgment-heavy parts (LLM-contract changes, schema migrations, validation design) stay with the main model. The main session's own model is user-controlled via `/model` — ticket text alone never changes it; the subagent override is the mechanism.
