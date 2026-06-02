---
name: feedback-verify-population-before-claim
description: "Before claiming a pattern \"across runs\" (all/most/never/almost-never), scan the actual population — including backups and compressed logs — not the sample currently in view."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 862bd440-cef5-42c8-9544-b744753d7c48
---

When doing run analysis, do not generalize ("all runs do X", "X almost never happens", "the system can/cannot Y") from the sample currently in view. Scan the actual population first.

**Why:** In the 2026-06-03 session I made three population claims in a row — "all pre-T4-BN runs terminate via GOAL_EXHAUSTED", "GOAL_FULFILLED almost never fires", "the injector chain was silently dead on the maven path" — from samples of 1–14 logs while the actual corpus was 3514 files across `firestarter/logs/` and `backup/<date>_<label>/logs/`. Each claim collapsed when the user pointed me to the wider corpus. The third claim was disprovable by reading one source file (`BlockerExtractor.java:159` strips ANSI before applying the regex).

**How to apply:**
1. Before any "across runs" claim, run `find /home/anugeshtu/IdeaProjects/autobuilder -name "autobuilder-*.log*"` to enumerate the full corpus.
2. Include the backup tree (`backup/<date>_<label>/logs/`) and compressed `.log.gz` (use `zgrep`/`zcat`) — see [[project-log-and-backup-conventions]] for why and how.
3. Deduplicate by base log timestamp before counting — the same log appears in many backups.
4. Cross-check load-bearing claims against the source (one `grep` is cheaper than one wrong ticket).
5. If the sample is < ~20 distinct runs spanning the relevant code era, weaken the claim to "in the runs I checked" — don't generalize.

Pairs with [[feedback-no-defensive-recast]] — the corrective when a population claim has already been made and corrected.
