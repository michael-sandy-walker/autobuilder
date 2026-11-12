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

**2026-11-03 (T4-ED), the result was read and the commit went in anyway.** I chained `mvn test` -> print the tally ->
`git commit` in one command; the tally said 5 errors and the commit ran regardless (`d319e34c`, a run context that
could not start -- a second unmarked constructor, the same mistake made earlier that day in `HomeController`). Rule:
a commit after a suite is GATED on the parsed numbers in the same command (`[ failures = 0 ] && [ errors = 0 ] &&
git commit ... || echo NOT COMMITTED`), never just printed beside it. And a Spring bean given a second constructor
for tests gets `@Autowired` on the one Spring should use, in the same edit.

## 2026-11-12 -- summing `target/surefire-reports` counts stale reports too

With `mvn -q` there is no summary line, so I summed the `TEST-*.xml` reports and quoted 4587 for a
suite that had run about 4540: nine report files from earlier days (classes since deleted or not
run) were still in the directory and were added in.

**Why:** the directory is not cleared by `mvn test`; a count over all of it is not this run's count.

**How to apply:** sum only reports whose modification time is after the suite's start (or run
without `-q` and read the summary line). Print the names of the stale files once so the gap is
visible.
