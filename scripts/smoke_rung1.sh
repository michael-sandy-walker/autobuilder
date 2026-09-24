#!/bin/bash
# smoke_rung1.sh — the yardstick for the rung-1 target (an adopted REST calculator).
# Usage: scripts/smoke_rung1.sh http://localhost:8085
# Last line: SMOKE: <pass>/15 (was /8 until 2026-09-24: routes tested were not checked)
#
# Asserts ONLY what bootstrap/seed.txt states: every route the application serves answers, every
# route is exercised by a test in the project's own suite, and that suite passes. It does NOT assert what any route should return — the seed says the
# opposite, that behaviour is to be characterised rather than corrected, so grading the answers
# would grade the loop on a requirement nobody wrote.
B="${1:?usage: smoke_rung1.sh <base-url>}/calculator"
n=0; pass=0
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS [$n] $3"; \
       else echo "FAIL [$n] $3 (want $2 got $1)"; fi; }
code(){ curl -s -o /dev/null -m 5 -w '%{http_code}' "$B/$1"; }

# Every route the seed lists must be reachable. 404 means the surface is gone; anything else means
# the route is there and answered, which is all the seed claims.
reachable(){ c=$(code "$1"); [ "$c" = "404" ] && echo missing || echo present; }
chk "$(reachable 'basic/add?augend=1&addend=2')"            present "GET /basic/add is served"
chk "$(reachable 'basic/subtract?minuent=3&subtrahend=1')"  present "GET /basic/subtract is served"
chk "$(reachable 'basic/multiply?multiplier=2&multiplicand=3')" present "GET /basic/multiply is served"
chk "$(reachable 'basic/divide?dividend=6&divisor=3')"      present "GET /basic/divide is served"
chk "$(reachable 'scientific/square/4')"                    present "GET /scientific/square is served"
chk "$(reachable 'scientific/factorial/5')"                 present "GET /scientific/factorial is served"
chk "$(reachable 'scientific/prime/7')"                     present "GET /scientific/prime is served"

# Every route is covered by a test: the seed's central obligation, and until 2026-09-24 this
# yardstick did not look at it -- run 2026-09-24_18-13-42 scored 8/8 with the three scientific
# routes untested. A route counts as covered when a test source names its path. That is a
# reference check, not proof the test exercises it; it only ever misses coverage written with a
# path assembled from pieces, which reads as not covered (the conservative direction).
TREE="${PREFLIGHT_TARGET_TREE:-data/rung1/output}"
tested(){ grep -rqsF "$1" "$TREE/src/test" && echo tested || echo untested; }
for r in basic/add basic/subtract basic/multiply basic/divide scientific/square scientific/factorial scientific/prime; do
    chk "$(tested "/$r")" tested "GET /$r is covered by a test"
done

# The project's own suite, which is the half of the objective that measures the tests themselves.
# Run in a copy: grading must not write into the artifact it is grading.
if [ -d "$TREE" ]; then
    SCRATCH=$(mktemp -d)
    cp -a "$TREE" "$SCRATCH/p" 2>/dev/null
    rm -rf "$SCRATCH/p/target"
    if (cd "$SCRATCH/p" && timeout 600 mvn -B -q test > "$SCRATCH/test.log" 2>&1); then
        chk pass pass "the project's own test suite passes"
    else
        chk fail pass "the project's own test suite passes"
        echo "      $(grep -m1 -E 'Tests run|ERROR' "$SCRATCH/test.log" 2>/dev/null | head -c 120)"
    fi
    rm -rf "$SCRATCH"
else
    chk absent pass "the project's own test suite passes"
fi

echo "SMOKE: $pass/$n"
