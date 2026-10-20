#!/bin/bash
# The yardstick for linetokens (T4-EC stage 2 parent): smoke_criterion_status.sh's checks, over this target's pristine tree.
HERE="$(cd "$(dirname "$0")" && pwd)"
SMOKE_TARGET=linetokens SMOKE_PREPARE="$HERE/prepare_module_change.sh" exec "$HERE/smoke_criterion_status.sh" "$@"
