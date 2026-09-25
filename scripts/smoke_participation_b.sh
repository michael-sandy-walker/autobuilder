#!/bin/bash
# smoke_participation_b.sh -- the yardstick for participation-b: the same module as participation
# under a DIFFERENT seed (another set of entry points, two shared), so the two lineages form the
# different-seed pair T4-CS's re-entry condition asks for. Same instrument, this target's seed.
SMOKE_TARGET=participation-b exec "$(dirname "$0")/smoke_participation.sh" "$@"
