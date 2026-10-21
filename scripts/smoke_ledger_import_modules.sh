#!/bin/bash
# The yardstick for ledger-import-modules (T4-EC stage 2): smoke_integration.sh over this case's tracked child.
HERE="$(cd "$(dirname "$0")" && pwd)"
SMOKE_TARGET=ledger-import-modules exec "$HERE/smoke_integration.sh" "$@"
