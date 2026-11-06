# firestarter-cashbook -- toy-case versions


Versions v1..v0 are reconstructed from the git history of `data/firestarter-cashbook/bootstrap/` (one per commit that changed it); each names that commit. Later versions are recorded as they are made (`toycase_version.py bump`).


## v1 -- 2026-11-06

- reason: created for T4-EI (capability import): firestarter run 2026-11-06_01-27-03 frozen as the source, finished cashbook as the receiver, assignment and two receiver goals in README.md
- bootstrap digest: `2c3d04de76d8a7b5`

## v2 -- 2026-11-07

- reason: yardstick check 4 narrowed to the loan rules themselves (LoanService and its refusals); LoanRepository's queries are the store's, as the model's cut made them -- v1 named them before any run, which the assignment does not
- bootstrap digest: `b426864ed71888a8`
