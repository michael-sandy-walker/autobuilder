---
name: project-ticket-premises-expire
description: "A ticket's stated blocker is a claim with a date on it — verify the block against the code before accepting it, because three of them expired unnoticed on 2026-09-08 alone."
metadata: 
  node_type: memory
  type: project
  originSessionId: c4f69f77-3abe-421e-8fbf-3ae7b4e7160b
  modified: 2026-09-08T14:47:47.711Z
---

A ticket that says it is blocked is making a claim about the world at the moment someone
wrote the line. The code moves; the line does not. **Verify the block before you accept it**,
and verify it against the code rather than against a later paragraph of the same ticket.

Three expired on 2026-09-08, each of which had changed what work looked possible:

- **`T1-FW`'s index caveat** — *"the fix AS FILED adds a fifth HTTP field to the record
  `T4-DD` wants dissolved; re-derive before building"*. The ticket's own design read had
  replaced that fix thirteen days earlier. The body was updated by appending; the **index
  line was not**, and the index line is what selection reads.
- **`T1-FV` and `T1-FW` both said "nothing reads handler parameters"** — `WiringFactsAnalyzer`
  had read the `@RequestBody` parameter and resolved its declared type since `T3-BX`, wired to
  one consumer answering a different question. See [[project-capability-outruns-routing]]: the
  behaviour was unreached, not unbuilt.
- **`T1-FV`'s "blocked on the 2026-08-26 correction"** — that correction rejected a
  handler-parameter reader **in `core/build`**, as a new web subsystem there. It said nothing
  about `analysis/structure/`, which already reads the artifact's web surface and which the
  seam report counts as behind the seam. The objection was about placement and had an answer
  nobody had gone looking for.
- **`T1-FW`'s "none of these have been run"** (2026-09-08, the fourth) — the filed body listed
  the non-HTTP shapes the invariant would take and said the project had only ever driven REST
  targets. By then `clitarget` had run, the invariant had appeared there (a criterion naming
  `column <file> salary`, a probe running `check <absent_path>`), the fix had shipped, and the
  field had confirmed it: **13 closure blocks to 0**. The ticket's generality clause was
  satisfied and its body still said the case had never arisen.
  - **⚠ THIS ONE EXPIRED IN THE OPPOSITE DIRECTION, WHICH IS THE MORE EXPENSIVE ONE.** The first
    three hid work that was still to do, so the cost was a re-derivation. This hid work that was
    already DONE, so the cost is re-BUILDING a clause someone already satisfied. **The credit had
    landed under a different ticket's section** — the clitarget run was written up under `T4-DK`
    — so nothing pointed back, and a grep for the ticket id would not have found it.

**Why:** an append-only ticket record ([[feedback-append-dont-rewrite-tickets]]) keeps the
filed body visible on purpose, which is right — but it means the oldest sentence and the
newest sit in the same document with equal authority, and a blocker is exactly the kind of
sentence that gets overtaken. The index line is worse, because it is a summary nobody appends
to.

**How to apply:** when a ticket names a blocker, spend the grep before spending the design.
Ask what the blocker asserts about the CODE, then check that assertion — `grep` for the
capability said to be missing, and read what a rejection actually rejected rather than what it
is remembered as rejecting. When a design read supersedes a caveat, **fix the index line in the
same edit**, since that is the surface selection reads. And check the clause in BOTH directions:
before building to satisfy a DoD clause, grep the corpus for the clause's own subject, because the
work may already have been done under another ticket's write-up and credited there. Related:
[[feedback-dont-evaluate-a-build-with-older-runs]] is the same hazard for measurements.
