---
name: feedback-compile-right-before-a-code-commit
description: Compile the exact working tree immediately before a code commit and check only intended files changed since the suite; the user's editor can write into files in between.
metadata:
  type: feedback
---

Immediately before a code commit: `mvn -o -B -q compile` on the tree being committed, and `find src -newer <suite log>`
to see that only files I changed since the suite are touched. A green suite an hour earlier is not evidence about
the bytes being committed.

**Why:** 2026-11-09, T4-EK: commit `2fcbf2c6` contained `Yeimport` on line 5 of ReverseEngineeringService -- written
at 22:43, after the suite (22:14) and the field runs passed; most likely the start of the user's "Yes, commit it"
typed into an open IntelliJ editor. The commit did not compile; fixed in `5e1127a0`. The user works in IntelliJ on
the same checkout ([[feedback-no-heavy-mvn-beside-a-live-run]]).

**How to apply:** in the commit command itself, compile first and abort on errors; diff-check that the staged set is
exactly what the suite ran on. Related: [[feedback-read-test-results-from-the-run]].
