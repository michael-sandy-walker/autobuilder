---
name: feedback-record-author-is-not-the-users-word
description: "A ledger entry \"by operator (home screen)\", decision \"own\", is not proof the user wrote or chose it -- the home screen's Suggest batch records model text that way; check how it was produced before saying \"you decided\"."
metadata:
  node_type: memory
  type: feedback
  originSessionId: d74d867e-f52a-49f6-a267-65717906fe22
  modified: 2026-11-16T19:49:16.228Z
---

Before attributing a decision, rule or text to the user ("you decided X"), check how the record was produced. The
home screen's **Suggest** fills each conflict box with the model's recommended option and **Decide all as shown**
sends it as `decision: own`, `by: operator (home screen)` -- the record cannot tell the model's sentence from the
user's own words (`home.html` tracks `d.suggested` and never sends it; on T4-EY's list to fix).

**Why:** 2026-11-16 (session 39) I wrote on T1-GQ and to the user that "you decided 'remove the menu bar'". The user:
"I didn't decide 'remove the menu bar'. I think you're reading something wrong. Maybe it's the merging of the two
projects which introduced that line?" -- it was the model's suggestion accepted in a batch (seven decisions 5-8 s
apart was the tell).

**How to apply:** say "the record says the operator decided X" or "decided via Suggest" unless the user's own words
are on record; a burst of decisions seconds apart, or text in a model's register ("to match the receiving project's
minimalist aesthetic"), means suggested. Related: [[project-invented-facts-have-authorities]] (authorship is a fact
with an authority -- the page's request, not the ledger's label).
