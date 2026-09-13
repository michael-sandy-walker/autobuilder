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

**A third correction, 2026-09-13, and it is about the FILING reflex rather than the closing one.** In one
session I filed four tickets while doing other work; the user stopped me on the fourth — *"Why do we add
just another ticket if everything is done? Just for the sake of adding tickets?"* — and three of the four
should not have existed:

- **`T5-BP`** described a real blind spot in the roadmap checker. It was meta-work on meta-work that would
  have sat at the bottom of a 33-item queue permanently. **The check it asked for took one minute to run**
  (43 live tickets, six candidates, one real), so running it was strictly better than recording it.
- **`T2-FK`** was filed with no actuator and an explicit note that `T2-ES` owned the action — a ticket
  nobody could finish on its own. Folded into `T2-ES`, where the identity and the escalation are one
  capability in one scope.
- **`T4-DV`** generalised from a single site. Measured on review: **31 of 41 literal emitters issue at the
  maximum and reason about no threshold at all**, and its one real risk was removed in five lines of code.

**The default was wrong.** On finding a gap while doing something else: **fix it, or drop it — filing is the
last resort, not the tidy one.** A ticket that cannot reach the top of the queue is not a record, it is
debt, which is precisely what `T5-BL` ("the apparatus only ever grows, and nothing argues for its own
removal") exists to say. Two questions before filing: *can this be done right now in less time than the
ticket takes to write?* and *if nobody ever picks this up, what actually breaks?* If the answer to the
second is "nothing", it is a note or a code comment, not a queue entry.

