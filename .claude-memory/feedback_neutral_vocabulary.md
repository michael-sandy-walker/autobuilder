---
name: feedback-neutral-vocabulary
description: Avoid courtroom and military/action-movie wording in this project — both in my writing and in code/roadmap/memory identifiers and prose. Use plain descriptive words.
metadata: 
  node_type: memory
  type: feedback
  originSessionId: c9e3b0ac-5788-4223-9c33-25e792b76246
---

Drop two registers of wording across the whole project — in conversation, code identifiers, comments, roadmap, and memory:

- **Courtroom / judgment:** verdict, interrogate, judge, critic (as a reviewer), adversarial.
- **Military / action-movie:** blast radius, tactical, kill-switch.

Prefer plain, descriptive words. Suggested replacements: verdict → result / classification / assessment; interrogate → examine / review / check; judge (noun, the scope agent) → assessor; judge (verb) → assess / decide / determine; critic / Critic SPI → reviewer / Reviewer; adversarial → thorough / careful; blast radius → scope / extent / reach; tactical → short-term / stopgap / narrow; kill-switch → off-switch / toggle / opt-out.

**Not in scope:** the `CRITICAL` confidence level (and `criticalConfidence`, `criticalCount`, etc.) is a legitimate severity term, unrelated to "critic" — leave it alone.

**Why:** this isn't a courtroom or a military/Robocop project; the framing should read calmly and descriptively. Loaded metaphors misrepresent what the system does (a self-improvement loop that checks its own work, not one that prosecutes or attacks it).

**How to apply:** when naming a component or describing behaviour, reach for the neutral descriptive word first. Watch myself especially — I drift into "interrogate / verdict / blast radius / tactical" under load. See [[feedback-no-target-domain-in-framework-strings]] and [[feedback-no-ecosystem-jargon-in-framework-code]] for the related "keep framework language clean" principle.
