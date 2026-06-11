---
name: project-tests-rotate-live-logs
description: "Running mvn tests (FILE_BASE_PATH=firestarter) while a live run is active rotates the live run's logs out from under it"
metadata: 
  node_type: memory
  type: project
  originSessionId: 65b469cd-e175-4378-8186-eb0a5270a472
---

Spring-context tests run with `FILE_BASE_PATH=firestarter` fire `StartupLogRotator` (T5-AE) on context start, which renames the *live* run's `autobuilder.log` / `behaviour.ndjson` to timestamped files. The running app keeps writing into the renamed file via its open handle; the fresh `behaviour.ndjson` belongs to the test JVM and stays ~empty. Observed 2026-06-11 22:30: a full `mvn test` during a live run blinded the log monitor and orphaned `behaviour.ndjson` (0 bytes).

**Why:** tests *require* `FILE_BASE_PATH=firestarter` (see MEMORY.md), so test JVMs share the live logs directory; the rotator can't tell a test context from a real boot.

**How to apply:** treat tests and live runs as mutually exclusive — stop the app before `mvn test`, or accept that post-rotation analysis must read the timestamped files (match by start time) instead of the live names. Monitors watching `behaviour.ndjson` must be re-armed after any test run. A code fix (rotator skipping test profiles) would be a T5 ticket if this keeps biting.
