# Assessment: criterion-status (SeedStatusSupport.of)

Independent comparer, 2026-09-27. Opinion only; the operator decides.

**Headline: loop, reference and port behave identically on every input I could construct.** Port is
byte-for-byte the loop plus a trailing newline, and matches `src/` exactly. Loop and reference are
the same decision table reached in two orders. I checked this by reasoning through each branch and
by compiling both against `target/classes` and running 15 off-spec inputs (see Probes): 15/15
verdicts matched. So the review question is about code shape, plus the risks all three **inherit from
the helpers**. It is not about which version is correct.

## Per version

**loop / port (80 lines)**
- Better: explicit null/blank criterion guard, so a reader sees the class comment's first rule
  without knowing the parser's contract. Braced, conventional control flow.
- Worse: the rule is written twice. `>= 200 && <= 299` appears twice, and the exact-match stream
  `stated.stream().anyMatch(b -> b.status() == assertedStatus)` appears twice, once in each branch.
  Its structure is "success branch / non-success branch" when the rule's real shape is "exact match,
  else vacuous success". The criterion null check repeats `BehaviouralCriterionParser.parse`, which
  already returns empty for null/blank. That is harmless but redundant. A `Map` local and a `Set`
  local exist only to be read once.
- Hard-coded: only 200-299, which is the rule's own range from the class comment. Acceptable.

**reference (67 lines)**
- Better: one ordering (exact match, then vacuous success) that covers both branches. The success
  range has one home (`isSuccess`). Statuses are collapsed to a `Set<Integer>` once. Its notes flag
  the real open question (whether document-wide statuses should count).
- Worse: brace-less one-line `if`s and chained `assertion.get()` calls are terser than the
  surrounding code in places (the parser uses the same style, so this is taste). It relies on the
  parser for the null criterion case, which is correct but less self-evident.

**Shared by all three (inherited from the helpers, not introduced by the port)**
- *Quantity read as a status:* `SpecEndpointBehaviourQuery` treats any bare 3-digit number in the
  endpoint's clause that is in its status table as a stated status. "max 200 chars" in a POST clause
  makes 200 a stated success, so a 201 criterion becomes UNSUPPORTED. "max 201 chars" makes 201
  SUPPORTED by accident.
- *Document-wide statuses bind only to clauses with a constraint word:* "Unknown ids return 404" in
  its own paragraph does not support `GET /items/{id} -> 404` unless that clause says
  "must/required/max...". Result: UNSUPPORTED.
- *Status table gaps:* the helper's fixed `RESPONSE_STATUSES` has no 1xx, 207, 418 or 451. A spec that
  literally states 451 or 207 is not read, so the criterion is UNSUPPORTED (or 207 is vacuously
  SUPPORTED when no success is stated). Any 2xx the parser accepts, including 299, is vacuously
  SUPPORTED on an endpoint that states no success.
- *Verb mismatch:* UNSUPPORTED (exact verb + normalized path). This matches the rule. HEAD/OPTIONS
  are invisible to `MENTION`.
- Both call sites in `CriterionProbeSynthesizer` (lines 212, 321) turn a wrong UNSUPPORTED into an
  abstention (no probe), which is the safe direction. So these gaps cost coverage, not false failures.
  The exception is the line-212 path, where a spurious SUPPORTED (the "max 201" case) lets a probe be
  grounded. That path still requires a 2xx and a created resource, so the exposure is small.
- Minor: the spec is scanned twice per call (`declaredIn` + `statedBehaviours`). The synthesizer can
  call `of` twice per criterion. This is negligible at spec sizes.

## Verdict

**Approve with changes (small, behaviour-neutral):** replace the body of `of` with the reference's
shape. At minimum, extract the success range to one helper and fold the two branches into
"exact match -> SUPPORTED; else success and no success stated -> SUPPORTED; else UNSUPPORTED".
Keeping the loop's explicit null criterion guard is fine. Approving as it is would also be
defensible, since the difference is duplication, not correctness.

Unsure: whether the seed intends document-wide statuses to count only through the helper's
constraint-word binding. That is a helper/seed question, not this port's. I would not file it
unless a run shows a stated requirement abstained on for that reason.

## Probes (inputs that pin the behaviour; all three versions agree on every one)

No input I found separates loop from reference. These probes pin the inherited edges, so a later
differential check (or a helper change) shows up as a verdict flip:

1. `POST /items returns HTTP 201` / `POST /items: name required, max 200 chars.` -> UNSUPPORTED (quantity read as success)
2. `GET /items/{id} returns HTTP 404` / `GET /items/{id} returns the item.\n\nUnknown ids return 404.` -> UNSUPPORTED; add "; id must be numeric" to the clause -> SUPPORTED
3. `POST /items returns HTTP 207` / `POST /items (201 or 207).` -> UNSUPPORTED; `/ POST /items creates an item.` -> SUPPORTED
4. `POST /items returns HTTP 451` / `POST /items creates an item (451 if blocked).` -> UNSUPPORTED (table gap)
5. `DELETE /items returns HTTP 204` / `GET /items lists items.` -> UNSUPPORTED (verb mismatch); criterion null, or spec `"   "` -> NO_OPINION
6. `POST /items returns HTTP 299` / `POST /items creates an item.` -> SUPPORTED (vacuous success on a non-status)
