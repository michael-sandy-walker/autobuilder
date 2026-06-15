---
name: feedback-activity-not-progress
description: "A mechanism firing in the logs is not it helping — validate the run OUTCOME moved, not activation; source signals from STRUCTURE (compiler grammar + symbol model), not string-scraping; don't build inert steering when the lever is elsewhere."
metadata:
  type: feedback
---

From the 2026-06-15 T2-EF / T1-EZ session, where I drifted until the user named it. Three linked reflexes to resist:

1. **Firing ≠ helping.** A steering mechanism activating in the logs is *not* evidence it improved the outcome. T2-EF's redirect fired 14× and the run still exhausted; a later run reached GOAL_FULFILLED with the same redirect firing **0×**. Activation and outcome are independent. Validate that the *run* moved (converged, fewer cycles, a real defect resolved) — never report "it fired" as the win. This is the run-level twin of [[feedback-verify-diff-is-substantive]].

2. **Source signals from structure, not scraped strings.** "What's missing" is answered by the compiler's own grammar (`symbol: class|interface` = a missing type; `method`/`variable` = something else) and by the symbol model already in the codebase (`SymbolGroundingService.definedSymbolsInOutput` / `symbolAllowlisted`, over the configured JavaParser solver). Mining the `cannot find symbol` *text* and patching it with heuristics is scar tissue: T1-EZ tried to dig the missing type out of a `method NAME(Arg)` token and immediately mis-fired on `Long` (a real JDK arg; the *method* was the gap). The structured source usually already exists — use it and **delete** the regex, don't extend it. Kin to [[feedback-no-hardcoded-ecosystem-data]].

3. **Don't build steering reflexively; gate it on evidence.** When runs keep ending the same way regardless of which gadget fires, the binding constraint is probably elsewhere (here: local-model last-mile capability — the parked verifier/model-upgrade decision). Added steering is then truthful-but-inert. Before building or extending a steering mechanism, require evidence it changes run *outcomes*; otherwise defer (e.g. T4-BQ deferred 2026-06-15).

**Why:** the drift cost real time and a shipped-then-reverted ticket (T1-EZ) — patch-on-patch on a string-scraper, plus treating activation as success.

**How to apply:** when a mechanism misbehaves, ask first — *is this signal sourced from structure or scraped from text?* and *am I validating it HELPS or just that it FIRES?* Prefer AST/symbol-solver/compiler-grammar sources over string heuristics. After a correction, don't spin a fresh confident framing ([[feedback-no-defensive-recast]]) — re-ground in the actual data first.
