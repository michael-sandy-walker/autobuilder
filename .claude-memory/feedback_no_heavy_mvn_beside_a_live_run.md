---
name: feedback-no-heavy-mvn-beside-a-live-run
description: "The scratch-FILE_BASE_PATH unlock makes mvn safe for the live LOGS, not for the live RUN — a full suite next to a run starves the box and the run dies of resource contention"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: f54dc5b0-d9fb-4267-bae8-a3bce5956657
---

Do not run a full `mvn test` while a live run is in flight, even with `FILE_BASE_PATH` pointed at a scratch directory. Run targeted classes (`-Dtest=X`) if anything, and prefer waiting.

**Why:** Burned 2026-08-01. The recorded unlock — `FILE_BASE_PATH=<scratch> mvn test` leaves the live log byte-identical — is about *log rotation*, which is what [[project-tests-rotate-live-logs]] warns about. It says nothing about *resource* contention, and I read it as a general clearance. A full suite (254 test classes, 27 minutes) alongside the live JVM and the local model drove load average to 159 and available memory to zero. Neo4j then failed every connection with `ServiceUnavailableException: Unable to establish connection in 30000ms`, `docker ps` itself hung, and the loop spent its remaining cycles on `Aborting cycle: unable to prepare context`. The run had to be killed at 10 cycles.

The diagnosis is worth keeping separate from the symptom: the ports stayed bound and the container still reported `Up`, so every surface-level check said the database was fine. What actually said otherwise was `/proc/loadavg` and `free`. This is the same environment-pressure failure [[project-run-lifecycle-runbook]] records for the Docker backend, reached by a different route — and that route was self-inflicted.

**Sharpened the same night, after doing it again at smaller scale:** "targeted test classes are fine" is too generous. `mvn test -Dtest=OneClass` still recompiles the whole module — 420 main sources and 254 test sources here — so it is a full build with a short test phase, not a cheap operation. Four of them in half an hour coincided with the live run stalling in LLM read-timeout retries with the circuit breaker at failure #2. That one was survivable (load 5, 8 GB free, ollama at 92% CPU and GPU 80% — the model was busy, not starved), but the compile share is real and the margin is not large.

**A control arrived the same night and I over-attributed blame.** I recorded the 00-23-26 run's circuit-breaker halt as probably mine because four LLM failures began one minute after a build finished. The next run — with **no builds at all** and load at 2.96 — produced the same failures. Elapsed time to the FIRST failure: **31.1 minutes** in the run I interfered with, **30.1 minutes** in the run I did not. That is the far better explanation, and it is not me: something degrades roughly half an hour into a run regardless (a growing context lengthening generations past the read timeout is the obvious candidate, unmeasured). The first run's halt is still partly on me — the second and third failures came during a second recompile — but the first failure was not.

The lesson survives with a different shape: **the concurrency rule is right for the reasons in the first paragraph** (load 159, zero memory, Neo4j down) and was wrong as an explanation of the breaker trip. Correlation with something I did is not evidence when the same thing happens without me, and the control was one run away.

**How to apply:** While a run is live, treat the repo build as off-limits except for single test classes needed to unblock an edit, and batch even those — every invocation is a full recompile. Editing source is free — the run uses the jar built at launch, not the tree. Before blaming a live run's Neo4j errors on the environment, read `/proc/loadavg` and `free -g` first; a load average in the hundreds with zero available memory is the answer, and a container that reports `Up` is not evidence against it. Note also that a jar built at launch does not carry changes made during the run, so a mid-run rework means the run is testing a superseded design and its field evidence does not transfer.
