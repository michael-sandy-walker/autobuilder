---
name: project-tests-rotate-live-logs
description: "mvn tests DO rotate a live run's logs when FILE_BASE_PATH=firestarter — but the destination is that env var alone, so FILE_BASE_PATH=<scratch> mvn test runs the FULL suite during a live run with the live logs byte-identical (verified 2026-07-30, 2267 tests, log unchanged then still growing). Tests also TARGET the live Neo4j by design (application-test.yml → localhost:7687) but measurement (T4-CL audit) shows the routine suite does NOT actually pollute it — isolation holds incidentally (testcontainers reuse + 0-goal migration). So do NOT stop the app to test — redirect the env var instead; graph-safety is latent-fragile until T4-CL."
metadata: 
  node_type: memory
  type: project
  originSessionId: 65b469cd-e175-4378-8186-eb0a5270a472
---

Spring-context tests run with `FILE_BASE_PATH=firestarter` fire `StartupLogRotator` (T5-AE) on context start, which renames the *live* run's `autobuilder.log` / `behaviour.ndjson` to timestamped files. The running app keeps writing into the renamed file via its open handle; the fresh `behaviour.ndjson` belongs to the test JVM and stays ~empty. Observed 2026-06-11 22:30: a full `mvn test` during a live run blinded the log monitor and orphaned `behaviour.ndjson` (0 bytes).

**Why:** tests *require* `FILE_BASE_PATH=firestarter` (see MEMORY.md), so test JVMs share the live logs directory; the rotator can't tell a test context from a real boot.

**How to apply (SUPERSEDED by the third variant below — redirect `FILE_BASE_PATH` instead of stopping the app):** treat tests and live runs as mutually exclusive — stop the app before `mvn test`, or accept that post-rotation analysis must read the timestamped files (match by start time) instead of the live names. Monitors watching `behaviour.ndjson` must be re-armed after any test run. A code fix (rotator skipping test profiles) would be a T5 ticket if this keeps biting.

**SECOND VARIANT (2026-07-17): the test profile TARGETS the live Neo4j by design — but measurement shows the routine suite does NOT actually pollute it (a LATENT channel, not an active one).** `application-test.yml` points at `bolt://localhost:7687` with production credentials and `migrate-to-graph: true`. Under the old per-run-disposable graph contract this was known/fine (reset purged everything). BUT the T4-CL audit (2026-07-17, cypher-shell before/after against the live store) found the routine suite leaves prod UNCHANGED: the `*IT` + graph tests override the URI to a SEPARATE testcontainers container (`withReuse` + `testcontainers.reuse.enable=true`, random port), and the one full-context non-IT test (`FileServiceTest`) migrates "0 goals". So the isolation holds today — but INCIDENTALLY/FRAGILELY (if testcontainers reuse fails, an IT's `cleanDb` `MATCH (n) DETACH DELETE n` would WIPE prod). The observed ghost root "A" in prod was real but its trigger is UNCONFIRMED (not reproduced by `mvn test` — plausibly an IDE run or a container-creation race). **Practical rule unchanged as caution, not as observed-fact:** don't assume a suite run is graph-safe between a halt and its resume until T4-CL closes the latent channel by construction; the resume side is already defended by the T1-FM ownership check regardless of how a foreign root arrives.


**THIRD VARIANT — THE WORKAROUND (verified 2026-07-30): tests and live runs are NOT mutually exclusive after all.** `logback-spring.xml` resolves its destination from exactly one place: `<property name="ROOT_PATH" value="${FILE_BASE_PATH:-/data/firestarter}"/>`, then writes `${ROOT_PATH}/logs/autobuilder.log` and `${ROOT_PATH}/logs/behaviour.ndjson`. So pointing the env var somewhere else redirects the rotation with it:

```bash
FILE_BASE_PATH=/tmp/<scratch>/testroot mvn test        # full suite, live run untouched
```

**Measured, not assumed:** during live run `2026-07-30_00-21-29`, `firestarter/logs/autobuilder.log` was **429257 bytes before and 429257 after** a targeted run, and grew normally (→479517) across a full 2267-test suite — no rotation, monitors stayed armed. The `file.base-path` the tests themselves use is unaffected (`application-test.yml` sets `./test-root` independently), so nothing about test behaviour changes.

**How to apply:** during a live run, prefix every `mvn test` with a scratch `FILE_BASE_PATH`. This removes the serialisation that used to force offline work to wait for a 90-minute run to finish. The graph caution in the second variant still stands on its own terms — it is unrelated to the log path.