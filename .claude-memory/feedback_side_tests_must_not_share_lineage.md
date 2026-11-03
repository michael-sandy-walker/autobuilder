---
name: feedback-side-tests-must-not-share-lineage
description: "A scratch copy of a project for a side test must NOT carry meta/run-identity.json -- it shares the real project's lineage in the shared Neo4j and its writes land on the real project"
metadata:
  node_type: memory
  type: feedback
  originSessionId: de848f5a-073c-4ee9-9d19-8e683ff3f6e8
  modified: 2026-11-03T14:53:32.092Z
---

A side test on a scratch copy of a real project must never copy `meta/run-identity.json` (copy `output/` and
`bootstrap/`, and `meta/` only without that file -- or let the copy mint its own lineage).

**Why:** 2026-11-03 (T4-ED) I copied `data/firestarter/{output,meta,bootstrap}` to scratch to test "Manage imports"
and withdrew its three imports there. The copy inherited firestarter's lineage, the graph (Neo4j) is shared and its
ledger reads are lineage-scoped, so the withdrawals and goal subsumptions were written onto the USER'S project: their
run then showed no imports, while their files (`inputs.json`, the 8 placed files) still had them. A real project's
state changed without being asked.

**How to apply:** before any side run on a copy, check what lineage it will run under (`meta/run-identity.json`);
delete that file from the copy. The same holds for any test that boots a run context on copied project state.
Related: [[feedback-no-heavy-mvn-beside-a-live-run]] (the other side-test hazard: builds rewrite `target/classes`
a running IntelliJ instance serves).
