# process/

How the operator and the session work on Autobuilder -- not Autobuilder itself. Nothing here is part of
the product, and nothing in `src/` or `scripts/` depends on it.

- `port_review.py`, `blind_reference_workspace.sh`: the port review stage. A solution the loop built for a
  ticket reaches `src/` only after the operator reviews a packet and approves (meta_process.md, M-E).
- `ports/<target>/`: one review packet per loop-built solution. `ports/stuck-router/` holds the stuck
  router's differential inputs (archived signatures, then labelled probes) and its harness.
- `tools/`: session tooling kept from session 15 (2026-10-19/20), each runnable as a single file.
  - `ReplayInjectorRule.java`: replays the moved-nested-type compile-fix rule over a run's failed candidates
    (`<base-tree> <candidates-dir> <work-dir> [withimports] [norewrite]`; run with
    `java -cp target/classes:<deps> process/tools/ReplayInjectorRule.java ...`). Field88 run 1: 1 -> 7 of 26 compiling.
  - `ReplayEditHunks.java`: applies archived evolution answers to trees with the current `EditHunks`
    (`<answers-dir with *.txt> <tree>...`); compile an old `EditHunks` beside it for before/after.
  - `watch_batch.sh`: a Monitor script for a batch that changes case -- follows the newest launch log, resets
    its counts on a new case, reports results/early_stop lines, closures, grinds and environment alarms
    (`BATCH_DIR=~/.autobuilder/fieldNN process/tools/watch_batch.sh [rearm]`).

