---
name: project-tests-rotate-live-logs
description: "mvn tests touch LIVE state two ways: they rotate a live run's logs (FILE_BASE_PATH=firestarter) AND they write into the LIVE Neo4j graph (application-test.yml → localhost:7687, migrate-to-graph on). Stop the app before testing; expect graph pollution until T1-FN lands."
metadata: 
  node_type: memory
  type: project
  originSessionId: 65b469cd-e175-4378-8186-eb0a5270a472
---

Spring-context tests run with `FILE_BASE_PATH=firestarter` fire `StartupLogRotator` (T5-AE) on context start, which renames the *live* run's `autobuilder.log` / `behaviour.ndjson` to timestamped files. The running app keeps writing into the renamed file via its open handle; the fresh `behaviour.ndjson` belongs to the test JVM and stays ~empty. Observed 2026-06-11 22:30: a full `mvn test` during a live run blinded the log monitor and orphaned `behaviour.ndjson` (0 bytes).

**Why:** tests *require* `FILE_BASE_PATH=firestarter` (see MEMORY.md), so test JVMs share the live logs directory; the rotator can't tell a test context from a real boot.

**How to apply:** treat tests and live runs as mutually exclusive — stop the app before `mvn test`, or accept that post-rotation analysis must read the timestamped files (match by start time) instead of the live names. Monitors watching `behaviour.ndjson` must be re-armed after any test run. A code fix (rotator skipping test profiles) would be a T5 ticket if this keeps biting.

**SECOND VARIANT (2026-07-17, the T1-FM field case): tests also WRITE to the LIVE Neo4j graph.** `application-test.yml` points at `bolt://localhost:7687` with the production credentials and `migrate-to-graph: true` — any graph-touching test context pushes fixture goals into the production store and IT `cleanDb` purges real lineage history. Masked for months by reset-purge-per-run; became harmful once T4-CD resume started TRUSTING surviving graph state (three zero-work ghost resumes onto a test root "A"). The resume-side damage channel is closed by the T1-FM ownership gate; the pollution itself remains until **T1-FN** (isolate the test profile from the production store) lands. Until then: a suite run invalidates the live graph's between-run continuity — don't run `mvn test` between a halt and its intended resume.
