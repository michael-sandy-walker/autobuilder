#!/bin/bash
# The yardstick for participation-totals: scripts/smoke_participation_change.sh against this target's seed.
SMOKE_TARGET=participation-totals exec "$(dirname "$0")/smoke_participation_change.sh" "$@"
