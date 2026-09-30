#!/bin/bash
# The yardstick for parameterized-rows: smoke_criterion_status.sh's checks, over this target's pristine tree.
HERE="$(cd "$(dirname "$0")" && pwd)"
SMOKE_TARGET=parameterized-rows SMOKE_PREPARE="$HERE/prepare_module_change.sh" exec "$HERE/smoke_criterion_status.sh" "$@"
