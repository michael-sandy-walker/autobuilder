# labelmaker-pantry -- T4-EJ's toy case (a capability adapted with its source as the only place its rule is written)

pantry (a finished, JDK-only home-inventory library) takes ONE capability from labelmaker (a finished, JDK-only
shipping-label library) through a capability import, adapted, with labelmaker's source kept as a reference. The
description names the scheme and states the API; the rule itself -- zone from the postcode's digit sum, the code's
shape, the weighted check character -- is written only in labelmaker's code (and seed, which is not imported).
Agreed with the user 2026-11-07 (T4-EJ): a new pair, not firestarter/cashbook.

## What is frozen here

- `source/` -- the labelmaker artifact of run `2026-11-07_12-25-17` (GOAL_FULFILLED, smoke 11/11), built by the loop
  from `data/labelmaker/bootstrap/seed.txt` at labelmaker v1: `output/` is byte-identical to that run's
  `artifact-src.tar.gz` (its `src/test` included -- the spec is part of the project). CODE ONLY, deliberately: no
  `meta/GoalStack.json` and no seed. Discovery reads a source's goal tree (`CapabilityImportService.goalsOf`), and
  labelmaker's root goal IS its seed, which states the rule in words -- carried into the capability's summary it
  would make the description more than a name, and the case unfair. No `meta/run-identity.json` either.
- `receiver/` -- pantry, WRITTEN BY HAND and frozen finished (its design is not under test): `output/` (Box,
  Pantry, PantrySpecTest), and a hand-written `meta/GoalStack.json` with the root goal COMPLETED. Its seed,
  protected paths and obligations are this bootstrap's own.
- `withheld/` -- `PantryLabelSpecTest`, the label capability's specification. The loop never sees it; the
  yardstick lays it over a copy of the tree it scores. Its expected codes are labelmaker's own rule.
- `SOURCE-DIGESTS.txt` -- sha256 of every frozen source and receiver file.

## The assignment

| labelmaker capability | choice |
|---|---|
| the label codes (zone from postcode, running number, check character) | adapt, source kept as a reference |
| anything else discovery names | leave out |

Control runs make the same choice with the source NOT kept (`reference: false`).

The description given to the adapted capability (the operator's words, fixed for every run of this version):

> Each box in the pantry has a label code, computed the way labelmaker computes a label code for a postcode, with
> the box's id as the label's running number. Pantry.labelOf(int id) returns the label of the box with that id as
> an Optional<String>, empty when there is none; Pantry.findByLabel(String code) returns the box carrying that label
> as an Optional<Box>, empty when no box carries it (null included). Box and pantry's existing behaviour stay as
> they are, so PantrySpecTest keeps passing.

## Fair-case check (before any field run)

The run's model, given pantry's tree, the receiver goal and the description above WITHOUT the reference, at the
run's request settings, k=5: any run whose label codes are right means the description suffices, and the case is
not fair. Result recorded in `CHANGELOG.md` and on T4-EJ.

## Yardstick (`scripts/smoke_capability_reference.sh`, terminating)

Frozen files unchanged; the import recorded the capability as adapted (and whether the source was kept); evolution
asked the reference and got an answer; each withheld test passes; pantry's spec is unmodified and passes; the whole
suite passes. The decision rule is on T4-EJ (2026-11-07), stated before any run.

## Running it

    scripts/prepare_capability_run.sh labelmaker-pantry
    PREFLIGHT_TARGET=labelmaker-pantry PREFLIGHT_SMOKE_MODE=terminating \
      PREFLIGHT_EXTRA_FLAGS="--autobuilder.start.mode=ASK --autobuilder.goal.sync-from-graph=false --autobuilder.goal.migrate-to-graph=true" \
      setsid nohup scripts/preflight.sh live 45 > <launch log> 2>&1 < /dev/null &
    scripts/capability_import.py discover data/labelmaker-pantry/bootstrap/source
    scripts/capability_import.py choose <selection> <choices.json>      # review the preview
    scripts/capability_import.py import <selection>
