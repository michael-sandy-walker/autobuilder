---
name: feedback-no-invented-labels
description: "Don't invent structural labels or role-nouns (arm 1/arm 2, phase A-H, winner/loser, keeper) in code, docs, or analysis — describe the thing itself"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 65b469cd-e175-4378-8186-eb0a5270a472
---

Do not invent shorthand labels or role-nouns for structures I created — "arm 1 / arm 2", "phase A–H", "winner/loser", "the keeper", "chain X" — in code identifiers, event fields, roadmap notes, or analysis prose. Three incidents: phase letters A–H (2026-06-10, user couldn't find them anywhere because they existed only in my head), "arm 1/arm 2" + "loser" in the T4-AJ Part B ship (2026-06-11), and **"keeper" surviving the very fix that removed "loser" the same day** — the rule must be checked against *every* coined noun, not just the ones already flagged.

**Why:** labels force results into stories. Once a label exists, later analysis reports "arm 2 fired" instead of the underlying fact, which invites omission and hallucination — the narrative layer gets confirmed instead of the data. The user: "It's poisoning the analysis by forcing results into stories."

**How to apply:** name things by what happens to them, preferably with participles or plain descriptions: *the kept goal* (not "the keeper"), *the subsumed goal* (not "the loser"), *the GIVEN_UP-blocker fold* (not "arm 2"). Test before writing: would the name mean anything to someone who hasn't read this code/conversation? If a group of items needs referring to twice, repeat the description or use the actual identifiers (ticket IDs, class names, event names). Related: [[feedback-neutral-vocabulary]] (contest/courtroom/military words are a special case of the same problem).

Also from the same review (2026-06-11): don't introduce arbitrary small caps that undermine a mechanism's purpose — a "max 4 criteria in the equivalence prompt" cap hid exactly the distinguishing criteria the prompt was added to expose, and the reviewer's top-5 candidate head-cut was the same flaw one layer up (it ranked by a string metric *measured* to misorder exactly the discrimination that matters, so it could drop the true match while keeping lexical noise). Legitimate bounds target the genuinely expensive resource (LLM *call* budgets) or prune with measured margin (a relevance floor where real duplicates score 0.69-0.81 vs a 0.5 cutoff). Fixed-size truncation of evidence, ranked by an unreliable metric, is never a cost optimisation — it's silent evidence loss.
