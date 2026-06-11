---
name: feedback-no-invented-labels
description: "Don't invent structural labels (arm 1/arm 2, phase A-H, winner/loser) in code, docs, or analysis — describe the thing itself"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 65b469cd-e175-4378-8186-eb0a5270a472
---

Do not invent shorthand labels for structures I created — "arm 1 / arm 2", "phase A–H", "winner/loser", "chain X" — in code identifiers, event fields, roadmap notes, or analysis prose. Two incidents: phase letters A–H (2026-06-10, user couldn't find them anywhere because they existed only in my head) and "arm 1/arm 2" + "loser" in the T4-AJ Part B ship (2026-06-11).

**Why:** labels force results into stories. Once a label exists, later analysis reports "arm 2 fired" instead of the underlying fact, which invites omission and hallucination — the narrative layer gets confirmed instead of the data. The user: "It's poisoning the analysis by forcing results into stories."

**How to apply:** name things by what they do or what they are ("the GIVEN_UP-blocker fold", "the goal that gets subsumed", "the keeper"). If a group of items needs referring to twice, repeat the description or use the actual identifiers (ticket IDs, class names, event names). Related: [[feedback-neutral-vocabulary]] (contest/courtroom/military words are a special case of the same problem).
