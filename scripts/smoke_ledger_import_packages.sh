#!/bin/bash
# The yardstick for ledger-import-packages (T4-EC stage 2): smoke_integration.sh over this case's tracked child.
HERE="$(cd "$(dirname "$0")" && pwd)"
SMOKE_TARGET=ledger-import-packages exec "$HERE/smoke_integration.sh" "$@"
