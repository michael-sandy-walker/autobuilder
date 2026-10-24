# edit-hunks-withheld

## v1 -- 2026-10-23

- reason: T6-U's withheld-tests rung, cut from edit-hunks v2 by hand (user, 2026-10-23: "state the interface in the seed"): the specification test moved to bootstrap/withheld, which the loop never sees and the yardstick lays over the tree; the seed states the interface and the wording the withheld tests check, never the tests; the obligations drop the specification gate and state the interface; protected paths keep the existing tests, not all of src/test.
- origin-rev: `6b1ddd926fb343faefc4826100c42947fabbe6e1`
- bootstrap digest: `facb4d8b36de0ae2`

## v2 -- 2026-10-24

- reason: T6-U Design 2 (user, 2026-10-23): the behavioural obligations marked 'gate: derived-test', so the framework writes their tests from their wording before the code changes and freezes them; the 'tests are added' line made a rule:, since the requirement tests are the framework's
- bootstrap digest: `a457e4ef554d465c`
