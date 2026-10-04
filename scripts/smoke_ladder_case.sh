#!/bin/bash
# The yardstick for a ladder case (scripts/ladder_case.py): smoke_criterion_status.sh's checks over the case's
# pristine tree -- every case of every specification class passes, src/test is unmodified, the module's other
# sources are unchanged, the whole suite passes. The target is named by SMOKE_TARGET (preflight sets it).
HERE="$(cd "$(dirname "$0")" && pwd)"
SMOKE_TARGET="${SMOKE_TARGET:-${PREFLIGHT_TARGET:?the ladder case to score}}" SMOKE_PREPARE="$HERE/prepare_module_change.sh" exec "$HERE/smoke_criterion_status.sh" "$@"
