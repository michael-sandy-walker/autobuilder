#!/bin/bash
# The yardstick for labelmaker (T4-EJ: the capability source, built by the loop): smoke_criterion_status.sh's checks, over this target's pristine tree.
HERE="$(cd "$(dirname "$0")" && pwd)"
SMOKE_TARGET=labelmaker SMOKE_PREPARE="$HERE/prepare_module_change.sh" exec "$HERE/smoke_criterion_status.sh" "$@"
