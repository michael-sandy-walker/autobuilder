---
name: feedback-check-a-mechanisms-last-event
description: To judge whether a multi-step mechanism works, count its LAST event across the run archive, and distrust a test that mocks that last step -- T4-BE's restage fired 0 times in 581 runs behind a green replay test.
metadata:
  type: feedback
---

**Rule:** before keeping, tuning or extending a multi-step mechanism, count the event of its FINAL
step across the whole run archive -- not its entry event. And read its end-to-end test for what it
mocks: a test that mocks the last step proves the wiring, never the outcome.

**Why (2026-11-08, T5-BU):** the T4-BE deferred-mutation restage logged 267 runs of deferrals and
85 cleared blockers -- busy at every early step -- and `deferred_mutation_restaged` fired **0 times
in 581 runs**. Its replay test (`Cycle1779492075276ReplayTest`) MOCKED the restager, so the drift
check (which hashed the proposed content instead of the file on disk) was never run by any test.
Two tickets (T5-AB TTL tuning, T4-BK partial restage) were filed to refine a mechanism that had
never completed once. Found only by a premise sweep of the deferred list. Same family as
[[feedback-activity-not-progress]] (firing is not helping) and [[project-delete-debt-pattern]].

**Also from the same measurement:** a defect in a dead mechanism is not automatically its cause of
death -- 28 of 33 drift drops were REAL (the clearing merge rewrote the same file); the hash defect
caused 5. Measure the split before proposing "fix the bug" over "delete it"
([[feedback-verify-population-before-claim]]).

**How to apply:** for any ticket that refines, tunes or extends a mechanism, first `zgrep -l` the
final-step event over `~/.autobuilder/run-archive/*/behaviour.ndjson.gz` (positive-check the
predicate on an event that does fire). Zero final events = the ticket's premise is unmet; the
question becomes fix-or-delete, with the measurement on the ticket.
