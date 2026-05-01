---
name: Analyse last run — no confirmation
description: When asked to analyse the last run, just do it without asking for confirmation or clarification
type: feedback
originSessionId: da07ed1f-c16b-41da-9655-cb63301eb012
---
When the user asks to analyse the last run (behaviour logs, output folder, goal stack, etc.), proceed directly — gather the logs and present the analysis. Do not ask for confirmation, clarification, or permission first.

**Why:** User expectation is immediate analysis. Asking is unnecessary friction.

**How to apply:** On any "analyse the last run" request, go straight to reading behaviour-*.ndjson, output files, and GoalStack.json, then present findings.
