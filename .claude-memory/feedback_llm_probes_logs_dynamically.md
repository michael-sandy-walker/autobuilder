---
name: feedback-llm-probes-logs-dynamically
description: "log analysis for the loop is an LLM probing the log iteratively, never code that knows the log's format"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 34c12a15-6e66-4e51-abe7-271ed7c6f0d9
  modified: 2026-11-02T17:16:08.611Z
---

When the loop needs to learn from its logs, the capability is an LLM that analyses the log dynamically -- it probes (search, read, count), sees the result, and chooses the next probe. Do not build format-specific parsers/scanners for it.

**Why:** user, 2026-11-02, stopping my hand-written archive scan mid-way: "Seeing which format the log has, etc., is exactly the wrong approach. We want the LLM to analyse the log dynamically. That is, probe for things iteratively." Format knowledge in code is the same debt as hardcoded ecosystem data ([[feedback-no-hardcoded-ecosystem-data]]), and it breaks whenever a log line changes.

**How to apply:** probes are generic over text (pattern search, line range, counts, per-cycle narrowing); the model decides what to look at. Hand-checked cases from the archive are fine as REPLAY QUESTIONS with known answers, not as the mechanism. Also before measuring a need with a scanner, ask whether the capability itself can be the instrument. Related: [[project-introspection-as-north-star]], [[feedback-replay-over-batches]].
