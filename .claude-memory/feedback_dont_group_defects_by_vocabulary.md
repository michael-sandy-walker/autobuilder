---
name: feedback-dont-group-defects-by-vocabulary
description: "N instances of the same pattern" is a claim about MECHANISM — verify each one's mechanism before grouping; a shared description is not shared cause
metadata:
  type: feedback
---

Declaring several findings "the same pattern" is a claim about effect, and needs the
same verification as any other. **A shared description is not a shared mechanism.**

**Why:** on 2026-08-29 I grouped four findings as one recurring error — "matching on
words / mentions rather than effect" — spanning T4-DD clause 4, the seed-coverage
reading, an M-E grep, and goal subsumption. The user asked whether that grouping was
itself the error it named. It was. Reading `GoalConsolidationService` showed
subsumption's string-similarity floor is only a **cost guard** ("candidates below a
string-similarity floor never reach the LLM"); an **LLM equivalence reviewer** makes
the verdict. So that member was a semantic misjudgement by a model, not lexical
matching — a different mechanism, grouped in on vocabulary alone.

**How to apply:**
- Before writing "the Nth instance of X", open the code or data for EACH member and
  name its mechanism. Drop any that only sound alike; the count is worth less than
  its weakest member.
- The pull is strongest when the pattern is genuinely real for two or three cases —
  the momentum of a good abstraction is what recruits the bad fourth.
- Same family as [[feedback-count-events-not-log-lines]] and
  [[project-invented-facts-have-authorities]]: the authority here is the code that
  implements the mechanism, and it is cheap to consult.
