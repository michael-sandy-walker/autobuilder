---
name: feedback-flag-ceremony-cost-early
description: "When the process around a task costs more than the task, say so BEFORE running it — measure delivered code against process artifacts rather than defending the workflow."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 31dca2a3-461d-4514-9365-255e2e2846ac
  modified: 2026-09-08T09:25:02.675Z
---

Stop and flag when the ceremony is about to outweigh the work. On 2026-09-08 I had run most of
`/gsd-plan-phase 2 --gaps` — init, gates, capability hooks, a spec-less edge probe, a composed
planner prompt — and was one call away from spawning an Opus planner plus a Haiku checker plus a
revision loop, to produce a `PLAN.md` for **roughly 30 lines of Python**: add `os.path.realpath`
to one function, wrap a date in `try/except ValueError` in two paths, add two tests.

The user stopped it: *"Much shit is done, but almost no non-shit is done."*

**Why:** measured over the milestone window, 56 commits produced `scripts/` +1,538/-24 against
`.planning/` +14,109 — about nine lines of planning prose per line of delivered code, and not one
line of it in the product the project exists to build. Half of two Pro plans' weekly budget went
with it. The disproportion was visible from the moment the gap list turned out to be two small
defects; I kept executing the workflow because it was the workflow, and did not weigh it.

**How to apply:**
- Before dispatching planner/checker/executor agents, compare the fix's size against the machinery
  about to be spent on it. Two named defects with prescribed fixes do not need a planning cycle.
- Say it in one sentence and offer the direct route — do not silently skip the process either.
- When a user challenges the ratio, **measure it** (`git log` by touched path, `git diff --shortstat`
  per area) and report the number, whether or not it flatters the work. It confirmed the complaint
  here. See [[feedback-verify-population-before-claim]], [[feedback-count-events-not-log-lines]].
- A rule nobody executes is not a control: the watcher-streaming rule had been written down since
  the eighth blindness variant and still had not prevented it. Ship the actuator
  ([[feedback-instrument-needs-an-actuator]]).
- Related: [[feedback-ticket-definition-of-done]] (close on DoD, do not add), and
  [[feedback-ship-full-scope]] (one scope, not a follow-up).
