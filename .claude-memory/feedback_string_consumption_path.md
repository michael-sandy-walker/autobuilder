---
name: feedback-string-consumption-path
description: "'Structured, don't paste the sentence, don't string-scrape' governs CODE that consumes a string for control flow — NOT relaying an agent's authored rationale into another agent's PROMPT, where prose is the right medium. A discriminator, not a 'prose good' rule."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: d2fe3c13-b85a-4a78-913f-e465c5df99e5
---

Before rejecting prose as unstructured, ask **who consumes it**:

- **Deterministic code branches on it** (a heuristic/brake/injector deciding from the text, a regex extracting control-flow meaning) → structure it. This is the fragile path [[feedback-activity-not-progress]] ("source from structure, not string-scraping"), [[feedback-no-invented-labels]], and the T4-BD lesson ("don't paste the reason back — that's the text-matching we deleted") are all about. T4-BD deleted a channel that *parsed* a `"Compile probe failed:"` prefix in code.
- **Another agent's LLM prompt consumes it** → the authored `rationale`/`feedbackMessage` sentence is the correct medium. One agent telling another "here's why, don't do it again" is natural language by nature; forcing it into a kind+name enum loses the actual reasoning.

**Why:** on T4-BX (2026-07-06) the code-path rule was mis-applied to a prompt-path task and drove two wrong turns — first an invented `TEST_ONLY_CHANGE` enum re-derived from the diff, then a pivot to [[project-agent-diagnosis-not-shared]]'s ProblemContext/give-up-brake — before the user re-anchored: "the rationale/feedback message of different agents communicated back to other agents so they know better what to do / not do."

**How to apply:** use this as a *discriminator per consumption site*, NOT as a new "always relay prose" absolute (the user explicitly warned against flipping to the opposite strict rule). Structure still wins wherever code makes the decision; prose still wins wherever an LLM reads it. Many features touch both paths — split by consumer, don't pick one globally.
