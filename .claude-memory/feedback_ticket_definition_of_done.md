---
name: feedback-ticket-definition-of-done
description: "Tickets state when they are done and close then; work must trace to the filed scope; residuals stay notes, not new tickets or prose. Prefer completing important existing roadmap tickets over adding new ones. From the 2026-07-03 T1-FD / T5-AH steers."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 2bd3938e-a5a3-4cc7-8962-9f589cd19232
---

Two corrections from the same session (2026-07-03):

1. On T1-FD I shipped retry work as "slice 4" — user: *"when is T1-FD completed? Further slices are appearing magically."* The work did trace to a filed fix direction, but the ticket never said what done means, so slices looked open-ended.
2. I then moved the ticket's residual design question out as a NEW ticket (T5-AH) with several paragraphs of prose — user removed it: it was not really "spun off" from T1-FD (a different question entirely), the lingo was drifting, and the roadmap needs completed tickets, not new ones.

**Why:** every new ticket, label, and paragraph is roadmap surface someone must re-read forever; filing residuals as tickets feels like diligence but is the same drift as minting slices — scope grows, nothing finishes. The process vocabulary itself ("spun off", "accreting", "definition of done") is a drift signal.

**How to apply:**
- A ticket states when it is done (usually its filed fix directions) and is closed in the same change that ships the last one.
- Work must trace to the filed scope; if it doesn't, stop and reconsider — don't relabel it into the ticket.
- Residual design questions stay as short notes where they arose. No new ticket, no prose.
- When choosing what to do next, complete an important open roadmap ticket rather than creating anything new.
- Related: [[feedback-ship-full-scope]], [[feedback-no-invented-labels]], [[feedback-neutral-vocabulary]].
