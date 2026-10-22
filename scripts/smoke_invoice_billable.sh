#!/bin/bash
# The yardstick for invoice-billable (T4-EC stage 4 slice 4): smoke_integration.sh over this case's tracked child.
HERE="$(cd "$(dirname "$0")" && pwd)"
SMOKE_TARGET=invoice-billable exec "$HERE/smoke_integration.sh" "$@"
