# firestarter-cashbook -- T4-EI's toy case (capability import)

cashbook (a finished, JDK-only ledger library) takes chosen capabilities from firestarter (a finished Spring Boot
book-lending REST API) through a capability import: discovery, choices, a narrowed input, today's import behind it.

## What is frozen here

- `source/` -- the firestarter artifact of run `2026-11-06_01-27-03` (GOAL_FULFILLED, smoke 23/23): `output/` is
  byte-identical to `~/.autobuilder/run-archive/2026-11-06_01-27-03/artifact-src.tar.gz` (checked 2026-11-06), plus
  its `meta/GoalStack.json` and `bootstrap/seed.txt`. No `meta/run-identity.json`: a copy carrying it would write onto
  the real lineage in the shared graph.
- `receiver/` -- cashbook as it finished (`output/`, `meta/GoalStack.json`), no `run-identity.json` either. The
  receiver's seed, obligations and protected paths are this bootstrap's own (`seed.txt`, ...).
- `SOURCE-DIGESTS.txt` -- sha256 of every frozen file; the yardstick checks the source is unchanged by the run.

## The assignment (decided with the user, 2026-11-06)

| firestarter capability | choice |
|---|---|
| validation with error-to-status mapping | adapt (source kept as a reference) |
| the in-memory store | take as code |
| the loan rules | leave out |
| anything else discovery names (REST layer, application bootstrap, ...) | leave out |

Discovery names the capabilities in its own words, so the operator maps them to this assignment by reading the list
(`scripts/capability_import.py discover` prints it); which discovered capability got which choice is in the run's
`meta/inputs/<id>/capabilities.json`.

## The two receiver goals -- ENGINEERING ASSUMPTIONS, not the user's words

The user's intent for the case: *"It should evolve into better code and more capabilities."* The two descriptions
below translate that into minimal, receiver-relevant goals, written from the actual source and receiver code. They
add no feature beyond what the source does, and keep cashbook's existing behaviour (LedgerSpecTest expects
IllegalArgumentException).

- **The store, taken as code:** "Bring firestarter's in-memory store into cashbook as a working part of its build: its
  repositories keep objects in memory, give each saved object a new id counting from 1, and find it again by id.
  Cashbook's build compiles it, and its whole test suite passes."
- **Validation and error mapping, adapted:** "Give cashbook typed refusals the way firestarter types its errors, in
  cashbook's own package and without a web layer: invalid input to the ledger -- an entry without an account or with
  a zero amount, a transfer of a non-positive amount or from an account to itself -- raises cashbook's own validation
  exception, a subclass of IllegalArgumentException whose message says what was wrong, so existing callers and
  LedgerSpecTest keep working; and one place in cashbook maps any such refusal to its kind and message, as
  firestarter's handler maps an error to a status and a message body."

## Yardstick (`scripts/smoke_capability_import.sh`, terminating)

Asserts only what the assignment and the two descriptions state: the source is unchanged; the import recorded a
capability taken as code and one adapted; the code capability's placed files are in the tree; no loan-rule
declaration (`LoanService`, `hasActiveLoanForBook`, `countActiveLoansForMember`) and no web layer
(`@RestController`, `@RestControllerAdvice`, `GlobalExceptionHandler`) is in `src/main`; none of firestarter's
exception classes was placed; a cashbook class extends IllegalArgumentException and the ledger throws it;
LedgerSpecTest passes; the whole suite passes. Absence checked here is absence of the declarations named -- not proof
that no loan behaviour came along in other words.

## Running it

    scripts/prepare_capability_run.sh firestarter-cashbook      # lays out output/ and meta/GoalStack.json
    PREFLIGHT_TARGET=firestarter-cashbook PREFLIGHT_SMOKE_MODE=terminating \
      PREFLIGHT_EXTRA_FLAGS="--autobuilder.start.mode=ASK --autobuilder.goal.sync-from-graph=false --autobuilder.goal.migrate-to-graph=true" \
      setsid nohup scripts/preflight.sh live 45 > <launch log> 2>&1 < /dev/null &
    scripts/capability_import.py discover data/firestarter-cashbook/bootstrap/source
    scripts/capability_import.py choose <selection> <choices.json>      # review the preview
    scripts/capability_import.py import <selection>                    # lands, keeps, releases (ADOPT_PROJECT)
