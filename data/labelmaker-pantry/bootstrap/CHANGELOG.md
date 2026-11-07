# labelmaker-pantry -- toy-case versions


Versions v1..v0 are reconstructed from the git history of `data/labelmaker-pantry/bootstrap/` (one per commit that changed it); each names that commit. Later versions are recorded as they are made (`toycase_version.py bump`).


## v1 -- 2026-11-07

- reason: the bootstrap as it stands, with changes not yet committed
- bootstrap digest: `67f4bc74b3c4f852`

### v1 fair-case check -- 2026-11-07

`scripts/t4ej_fair_case_probe.py`, the run's model (qwen3.8, local), the real evolution system prompt and the logged
prompt layout, pantry's files and the description, NO reference offer; k=5 at temperature 0 (the run's setting).
All five answers byte-identical (sha 422ce03f47d9 -- temperature 0 repeats itself here, measured n=5); each compiles
and fails 3 of 4 withheld tests (the one passing, noBoxNoLabel, needs no rule). The model invents a label
"<postcode>-<id>" and never names labelmaker. FAIR: the description alone does not yield the codes.
