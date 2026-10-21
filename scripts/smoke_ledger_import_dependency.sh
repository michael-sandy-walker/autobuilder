#!/bin/bash
# The yardstick for ledger-import-dependency (T4-EC stage 2): smoke_integration.sh over this case's tracked child.
HERE="$(cd "$(dirname "$0")" && pwd)"
SMOKE_TARGET=ledger-import-dependency exec "$HERE/smoke_integration.sh" "$@"
