---
name: feedback-read-test-results-from-the-run
description: read a test result from THIS mvn run's output (or a report newer than the run), never from target/surefire-reports alone -- a failed compile leaves the last run's reports standing
metadata:
  type: feedback
---

A `mvn -q test -Dtest=X > /dev/null` whose test sources did not compile prints nothing and writes no report, and
`sed -n 4p target/surefire-reports/*.X.txt` then shows the PREVIOUS run's "Tests run: 23, Failures: 0" -- I reported a
new test as passing on 2026-10-27 that had never compiled (an unreported IOException); the full suite caught it.

**Why:** the report directory is not cleared by a build that fails before the test phase, so a green line there
proves only that some earlier run passed.

**How to apply:** grep the run's own output for `Tests run:` / `BUILD SUCCESS|FAILURE` / `ERROR]`, or check the
report's mtime against the run's start, before quoting a count. Related: [[feedback-verify-diff-is-substantive]].
