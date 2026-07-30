---
name: feedback-no-symbolic-enumerations
description: "Never use symbolic enumeration marks (①②③, ➀, ⓵) in tickets, notes or responses — name the items instead"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: df54f45e-184f-4e0a-8554-38e62ac1303c
  modified: 2026-07-30T15:11:22.932Z
---

Do not use symbolic enumeration marks — `①②③`, `➀`, `⓵`, and the like — anywhere: roadmap tickets, memory notes, commit messages, or responses. Give each item a short **name** and refer to it by that name ("the unclaimed question", "the readout consumer"), or use ordinary numbered/bulleted lists.

**Why:** the symbols do not help later analysis. A reference to "②" three paragraphs or three sessions later carries no meaning on its own — the reader must re-find the list and count. A named item survives quoting, grepping, and partial reading. It is the same failure as [[feedback-no-invented-labels]] ("arm 1/2", "winner/keeper"): a positional token standing in for a description.

**How to apply:** when about to write `①`, ask what the item *is* and use that as its label in bold. In a ticket, prefer a sub-bullet per item over an inline enumerated sentence. Existing `①②` occurrences in older notes are legacy — fix them when editing that text for another reason, not as a sweep.
