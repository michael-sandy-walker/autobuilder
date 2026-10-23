# receiver-questions-withheld

## v1 -- 2026-10-23

- reason: T6-U's withheld-tests rung, cut from receiver-questions v4 by hand (user, 2026-10-23: "state the interface in the seed"): the specification test moved to bootstrap/withheld, which the loop never sees and the yardstick lays over the tree; the seed states the interface and the wording the withheld tests check, never the tests; the obligations drop the specification gate and state the interface; protected paths keep the existing tests, not all of src/test.
- origin-rev: `5b07c6daa46683805ad924593a932b578fd0793e`
- bootstrap digest: `755928a9c466618c`
