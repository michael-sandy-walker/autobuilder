---
name: feedback-neutral-vocabulary
description: Avoid courtroom and military/action-movie wording in this project — both in my writing and in code/roadmap/memory identifiers and prose. Use plain descriptive words.
metadata: 
  node_type: memory
  type: feedback
  originSessionId: c9e3b0ac-5788-4223-9c33-25e792b76246
  modified: 2026-08-21T22:21:35.616Z
---

Drop two registers of wording across the whole project — in conversation, code identifiers, comments, roadmap, and memory:

- **Courtroom / judgment:** verdict, **evidence** (2026-08-25, and see the drift note below), interrogate, judge, critic (as a reviewer), adversarial.
- **Death / mortality (SEVENTH correction, 2026-07-11):** death, die/dies/died, killed — pervasive in the T4-BO/T4-BZ lifetime instrumentation (`Death` record, `deathCycle`/`deathReason` event fields, `deathsThisTick`, `ticksToDeath`, "signal dies", "death cycle"). User: "Can you please stop naming the variables and objects 'death'?" All renamed (→ `LifecycleEnd`, `endCycle`/`endReason`, `endedThisTick`, expiry/ending/removal; report script reads both old and new event keys so old corpora still parse). The trigger is the SAME spot as the verdict family: naming what happens when a tracked thing STOPS — the word is end / ending / expiry / removal / closed, never death/die/kill.
- **Military / action-movie:** blast radius, tactical, kill-switch, **attack** (2026-07-21, TENTH correction — I wrote "that's the thing to attack" / "attack the failure class"; the roadmap itself carries "attack the failure class the last runs actually hit". User: *"please avoid phrasings like 'that's the thing to attack'. It gives me shivers."* Use: work on / address / close / focus on / the part to fix), **killer / killer run** (2026-07-07 — overused for the preserved regression runs; repetition breeds a naming bias, exactly the failure [[feedback-no-invented-labels]] warns against; the term is baked into `KillerRunRegressionScenarioTest` + its fixtures, a candidate rename). Use: preserved run, regression fixture, pinned-run scenario, the run-N case.

Prefer plain, descriptive words. Suggested replacements: verdict → result / classification / assessment; interrogate → examine / review / check; judge (noun, the scope agent) → assessor; judge (verb) → assess / decide / determine; critic / Critic SPI → reviewer / Reviewer; adversarial → thorough / careful; blast radius → scope / extent / reach; tactical → short-term / stopgap / narrow; kill-switch → off-switch / toggle / opt-out.

**No emoji decorations in responses (2026-07-23):** don't use emoji as list markers, section decorations, or stop-signs (e.g. a red stop-sign before a checkpoint, check/cross status glyphs). Plain markdown and words only ("Checkpoint A", not a glyph + "Checkpoint A"). Same calm-descriptive spirit as the wording rules; governs response prose, not code. (User asked for the removal directly; keep it folded here, no separate memory file.)

**Not in scope:** the `CRITICAL` confidence level (and `criticalConfidence`, `criticalCount`, etc.) is a legitimate severity term, unrelated to "critic" — leave it alone.

**Why:** this isn't a courtroom or a military/Robocop project; the framing should read calmly and descriptively. Loaded metaphors misrepresent what the system does (a self-improvement loop that checks its own work, not one that prosecutes or attacks it).

**Enforcement is light-touch BY CHOICE (user steer, 2026-07-07):** do NOT build a lint gate / pre-commit wordlist / mechanical blocklist for this — the user explicitly rejected word censorship (a blocklist can't tell an apt word from a lazy one, and freezes a list that has legitimate carve-outs like `CRITICAL` and prompt text). The agreed mechanism is to CORRECT IT WHEN IT COMES UP TOO MUCH — human notices recurrence, we prune. Reactive-by-design, not automated. Don't re-propose the gate.

**How to apply:** when naming a component or describing behaviour, reach for the neutral descriptive word first. Watch myself especially — I drift into "interrogate / verdict / blast radius / tactical" under load. See [[feedback-no-target-domain-in-framework-strings]] and [[feedback-no-ecosystem-jargon-in-framework-code]] for the related "keep framework language clean" principle.

**Drift incident (2026-08-22, ELEVENTH correction — "blast radius", a word this file already named):** during the qwen3.8 model swap I wrote "Let me check the blast radius first" before grepping for the callers of a DTO constructor I was about to change. User: "please don't mention 'blast radius' again." It was listed in the military register and in the replacement table (→ scope / extent / reach) from the day this file was written, so this is pure recurrence, not a gap. **The trigger is a new spot, worth naming:** not the output of a check (the verdict family) but *the moment before editing shared code, when I ask how far a change reaches*. The words there are: how many callers / what this touches / the scope of the change / where this is used. Say the concrete thing — "one call site" — rather than any metaphor for it.

**Drift incident (2026-06-10, second correction):** three days after this rule was filed I shipped `GoalAdoptionService.parseVerdict` + a `noneVerdict` test, wrote "progress-aware verdicts" in a commit message and roadmap, and used "Verdict:" as a summary label — all caught by the user, all renamed (→ `parseMatchResponse`, "decisions", "Decision:"). The drift happens at *naming time*, not review time — the check has to run before I write an identifier or label, not after. Method/label names involving the result of an LLM/reviewer call are the highest-risk spot.

**Drift incident (2026-07-10, SIXTH correction — "verdict" again, ONE DAY after the fifth, T4-BZ-2):**
named a new context enum `ScopeVerdict` + field `scopeVerdict`, a field `terminalVerdictReason`,
and wrote "terminal verdict record" / "detector VERDICTS" through the T4-BZ-0 emission-site
table, resume-state block, and Java comments. User: "Can you please stop with the verdict naming
again?" All renamed pre-commit (→ `ScopeGuardResult`/`scopeGuardResult`, `goalExhaustedReason`,
"halt-reason record"). The trigger was AGAIN the fifth incident's exact spot — naming the output
of a check (the scope guard's conclusion; a halt decision). Standing pre-write check: before
introducing ANY name or doc phrase for "what a check/guard/detector concluded", the word is
result / reason / conclusion / assessment — never verdict.

**Drift incident (2026-07-09, FIFTH correction — "verdict" again, T4-J Phase 3d):** framed the
multi-cycle replay as "the decay-verdict instrument" and shipped "first decay verdict" in a commit
title, test javadoc, report text, and memory notes — plus "adjudicate/adjudication" (same
courtroom register) in the T4-J design note and consumer map from an earlier session. User:
"Can we please stop again with the 'verdict' naming?" All swept (→ result / answer / finding /
conclusion; adjudicate → resolve / decide / settle). Pattern across all five incidents: the word
attaches itself to *the output of a measurement or check* — that exact moment ("what do I call
what this instrument produces?") is when the rule must fire.

**Drift incident (2026-07-06, FOURTH correction — "verdict" in analysis prose and a memory file):** run-18 analysis used "verdict" throughout (memory file body, index line, user-facing summary). The rule covers ANALYSIS PROSE AND MEMORY FILES, not just code identifiers — the check must run when writing any summary of a checker/reviewer result, the highest-frequency spot of all. Use: result, answer, assessment, classification.

**Drift incident (2026-07-17, NINTH correction, same day as the eighth — "trap", plus the translation lesson recurring, plus MIS-FRAMING a known design as a bug):** hours after the eighth correction I (a) used "pollbox series" and "the rescue" in reports without translating them — the user had to ask what they mean (the eighth's translation rule, violated immediately); (b) leaned on "trap" throughout new ticket/memory prose ("the trap, precisely", "live-state trap") — **"trap" joins the avoid list** (→ known interaction / recorded assumption / constraint); (c) framed the tests-write-to-live-graph behavior as a bug/trap when it was KNOWN and acceptable under the old per-run-disposable graph contract — the truthful framing is an ASSUMPTION/CONTRACT CHANGE (cross-run continuity ended disposability), and the ticket was re-tiered T1→T4 accordingly (T1-FN→T4-CL). **Lesson: when new capability work makes previously-fine behavior harmful, describe the contract change, don't retro-label the old behavior a defect.**

**Drift incident (2026-07-17, EIGHTH correction — "campaign" + "verdict" + "testament", entered via ARTIFACT NAMES):** sessions earlier the same day shipped `scripts/lineage_campaign.sh` (military) printing `CAMPAIGN: <verdict>` lines, `watch_report.py` emitting `verdict=` fields, and `RunTestamentService`/`meta/run-testament.json` ("testament" = legal-document register, same courtroom family) — then I amplified all three heavily in reports as if they were shared vocabulary. User: "Why did you mention 'campaign' and 'verdict' and other vague jargons?" All renamed same day (campaign → run series, `run_series.sh`, `SERIES:`; verdict → result; testament → run end report, `RunEndReportService`, `meta/run-end-report.json`; old-name fallbacks kept where scripts read HISTORICAL corpora). **New lesson on top of the naming-time check: INHERITED names are not license — when a term already in the repo violates the rule, flag/rename it, don't adopt and extend it. And my summaries must translate repo codenames into plain words regardless.** "testament" is now explicitly on the avoid list (→ run end report / terminal summary).

**Drift incident (2026-07-02, THIRD correction — "verdict" again, plus "evidence" added to the list):** I built a whole API around "verdict" (`findMissingFeaturesVerdict`, `verdictObtained`, "NO verdict" log lines) and leaned on "evidence" in new logs/comments; user caught both. All "verdict" occurrences purged project-wide the same day (→ `reviewMissingFeatures`, `answered`, "no answer"), including the pre-existing T4-BR `recordCompletionVerdicts`/`findCriterionVerdicts` (→ `...Results`). **"evidence" is now on the avoid list too** (→ observation / fact / record / check-result). **Boundary note:** legacy "evidence" still lives in LLM-facing prompt text and response-format keys (e.g. `SCORE_FROM_EVIDENCE` rule, parsers reading an `evidence` field) and in identifiers like `WiringEvidenceAnalyzer` — renaming those changes prompts/parsing (behavior), so it is filed as its own validated pass (see roadmap T5-AG), not done as a blind sweep.

**Drift incident (2026-08-25) — "verdict" was ALREADY banned and I used it ~90 times in one session; "evidence" added the same day.** Across a long autonomous session I wrote `verdict` 82 times in `roadmap.md` and 32 in the resume state, plus 13 commit messages carrying one or both words — including subject lines. The user: *"can you please avoid the words 'verdict' and 'evidence'? Now they're even polluting simple commit lines."* The word was on this list since the eighth correction (2026-07-17), so this is a regression on a recorded rule, not a new preference.

**Why it spread:** the session was one long chain of measure-then-conclude, and both words are the path of least resistance for "what the measurement showed". That is exactly when the register creeps in — the vocabulary follows the ACTIVITY, so a session spent judging things writes judging words unless it is watched.

**Replacements that keep the meaning:** verdict → result / conclusion / call / outcome · evidence → measurements / data / observations / grounds / support · "no evidence of X" → "no sign of X" / "nothing showing X" · "evidence-gated" → "measurement-gated" · "field evidence" → "field results".

**Swept 2026-08-25** through `roadmap.md`, `project_resume_state.md`, `roadmap-overview.md`. **Deliberately NOT changed:** references to real code identifiers (`hasOutputEvidenceForSubgoal`, `RolledBackEvidence`, and two test method names) — the roadmap quotes symbols that exist, and renaming them there would make it describe code that does not. **Those identifiers are pre-existing debt in 3 production files**; renaming them is its own change, not a documentation edit. Git history is left alone: rewriting published commit messages is a branch operation, and [[feedback-no-branch-ops]] governs.

**Swept 2026-09-01 (user-directed): "suspected"/"exonerated" replaced.** The accusation register pair — a mechanism "suspected" then "exonerated" — is the same courtroom framing. Replacements: exonerated → cleared (by the audit) / needs no change · indicts → points at · the `T1-ET` marker `livelock-suspected` → `livelock-signature` (log WARN, behaviour guard message, signal source `T1-ET-livelock-signature`; no script or test matched the old literal, but ARCHIVED run logs still carry it, so readers accept both). Ordinary uses of "suspected" as plain English ("confirmed, not suspected", "I suspected the probe missed…") were left — the rule is about register, not the word list growing without bound. Commit messages in history are left alone, as with the verdict sweep.
