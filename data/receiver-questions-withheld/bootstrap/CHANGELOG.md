# receiver-questions-withheld

## v1 -- 2026-10-23

- reason: T6-U's withheld-tests rung, cut from receiver-questions v4 by hand (user, 2026-10-23: "state the interface in the seed"): the specification test moved to bootstrap/withheld, which the loop never sees and the yardstick lays over the tree; the seed states the interface and the wording the withheld tests check, never the tests; the obligations drop the specification gate and state the interface; protected paths keep the existing tests, not all of src/test.
- origin-rev: `5b07c6daa46683805ad924593a932b578fd0793e`
- bootstrap digest: `755928a9c466618c`

## v2 -- 2026-10-24

- reason: T6-U Design 2 (user, 2026-10-23): the behavioural obligations marked 'gate: derived-test', so the framework writes their tests from their wording before the code changes and freezes them; the 'tests are added' line made a rule:, since the requirement tests are the framework's; one line added restating the two failure forms the ticket quotes, so the wording check holds the test to them
- bootstrap digest: `e5ab1556db23b3ab`
