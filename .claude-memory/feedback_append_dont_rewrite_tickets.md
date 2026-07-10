---
name: feedback-append-dont-rewrite-tickets
description: "Closing or updating a ticket must APPEND (closure note, timestamps, deviations) — never rewrite/replace the original filed body; the filed scope/DoD must stay visible for transparency."
metadata:
  node_type: memory
  type: feedback
---

# Append to tickets, never rewrite them

When a ticket (roadmap.md or anywhere) is closed, progressed, or otherwise updated: the original
filed text — scope, DoD, open questions, rationale — stays exactly as written. Changes are
ADDITIVE: flip the checkbox, append a **CLOSED <date>** (or status) sub-bullet carrying the
result, timestamps, and any deviations from the filed scope. Do not collapse, reflow, or replace
the body with an "as-built" rewrite.

**Why (user, 2026-07-10):** "The original information should still be visible. If there are
changes, only add changes... Just the necessary instead of a complete refactor. Otherwise it's
not very transparent." A rewritten closure destroys the ability to compare what was PLANNED
against what was DONE — the deviations (descoped items, resolved-differently decisions) are
exactly the information a rewrite silently erases. Git history technically retains it, but the
document must carry it on its face.

**How to apply:** at closure time, write the closure as its own sub-bullet under the untouched
filed body: status + date, gate/verification results, and an explicit "deviations from filed
scope/DoD" list where the delivery differed (with the justification). If the filed text contains
an open decision the work resolved, the resolution goes in the closure note — the question
stays visible above it.

**Incident (2026-07-10):** closing T4-BZ-0/1/2 I replaced each ticket's filed body with a
condensed as-built paragraph (then, on a formatting follow-up, reflowed those rewrites again).
User flagged the transparency loss; original bodies restored from git history with closure notes
appended. Related: [[feedback-ticket-definition-of-done]] (close against the filed DoD — which
requires the filed DoD to still be readable).
