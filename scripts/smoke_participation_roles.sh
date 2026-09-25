#!/bin/bash
# The yardstick for participation-roles: scripts/smoke_participation_change.sh against this target's seed.
SMOKE_TARGET=participation-roles exec "$(dirname "$0")/smoke_participation_change.sh" "$@"
