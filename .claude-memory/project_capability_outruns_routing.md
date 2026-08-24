---
name: project-capability-outruns-routing
description: "The default hypothesis for 'the loop never does X' is that X EXISTS and is not reached — not that X must be built. Measured: 4 mechanisms in one session, plus prior instances."
metadata: 
  node_type: memory
  type: project
  originSessionId: 0e00c7eb-58de-44b6-a31e-8d879612b96a
  modified: 2026-08-24T15:38:07.141Z
---

**When a run fails to do something obvious, assume the mechanism exists and is unreached before assuming it is missing.** This codebase was assembled mechanism-by-mechanism across many sessions; capability accumulates faster than the routing that reaches it, and the resulting failure is a working repair sitting one wrong label, gate, or call site away from being entered.

**Why:** on 2026-08-23/24 I went to build four mechanisms in the compile-repair chain that already existed — `BuildBlockGate.ensureRecurringDefectRedirection` (already handles pre-merge failures, and its own javadoc documents the exact diagnosis I "discovered"), `PomDependencyEditor` (already splices dependencies), `SymbolImportResolver.resolveMissingPackage` (already handles `PACKAGE_DOES_NOT_EXIST`), and `WellKnownPackageRegistry`'s prefix match (already resolved the exact coordinate the failing run needed). The actual defect was one line of precedence in `BuildErrorClassifier`: it tested `CANNOT_FIND_SYMBOL` before `PACKAGE_DOES_NOT_EXIST` and returned on first match, so a complete repair chain was never entered and a run burned 20 cycles editing a file no edit could fix. This is not new — [[project-resume-state]] records 2026-07-30's T3-BX and T2-CI as "WIRING, not missing logic", and T3-BW's rule already fired on 6/6 corpora while the only path into the prompt returned 0.

**How to apply:** before writing code for a missing behaviour, trace the chain end to end from the observation to the actuator and find the link that does not fire — grep the class names of every adjacent mechanism, and read the javadoc of the one that looks closest, because past sessions often documented the same problem there. Expect the fix to be a gate, an ordering, or a call site rather than a new class. A ticket that says "build X" deserves a check that X is not already built and unreached. Related: [[feedback-instrument-needs-an-actuator]] (the readout half exists, the actuator does not) and [[feedback-one-invariant-one-home]] (the same invariant patched at two sites means the capability has no home) — this note is the third shape: the home exists and nothing knocks on the door.
