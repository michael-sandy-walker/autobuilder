---
name: feedback-ticket-definition-of-done
description: "Tickets need a definition of done; don't mint new slice numbers for unplanned work or let a ticket accrete scope — close it when its filed fix directions are shipped and spin residuals off as their own tickets. From the 2026-07-03 T1-FD 'slices appearing magically' steer."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 2bd3938e-a5a3-4cc7-8962-9f589cd19232
---

When work lands under an existing ticket, it must trace to that ticket's FILED scope (its observed/fix-direction text), and the ticket must state when it is DONE. Do not invent new slice numbers for work the ticket never enumerated — the user (2026-07-03, on T1-FD "slice 4"): *"when is T1-FD completed? Further slices are appearing magically."*

**Why:** open-ended tickets become scope magnets — every related idea files itself as "slice N+1", the ticket never closes, and the user cannot tell planned work from improvisation. (T1-FD's fix direction 3 *did* cover the retry-backoff work, but numbering it "slice 4" made it look like new scope; the real omission was never stating the definition of done.)

**How to apply:**
- When filing a ticket, write the definition of done (usually: the enumerated fix directions).
- When shipping part of a ticket, tie the work explicitly to which filed direction it completes — if it doesn't map to one, it's a NEW ticket, not a slice.
- When the last filed direction ships, mark the ticket complete in the same change; spin any residual design questions off under their own ID (T1-FD → [[project-resume-state]] T5-AH example).
- Related: [[feedback-ship-full-scope]] (one capability = one complete scope), [[feedback-one-invariant-one-home]].
