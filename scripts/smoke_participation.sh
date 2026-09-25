#!/bin/bash
# smoke_participation.sh -- the yardstick for the participation target (a module of Autobuilder
# itself, extracted by scripts/extract_participation.sh and adopted as a library).
# Usage: scripts/smoke_participation.sh <artifact-jar>     (preflight's "terminating" mode)
# Last line: SMOKE: <pass>/14
#
# Asserts ONLY what bootstrap/seed.txt states: every listed entry point is covered by a test, the
# library code is not modified, and the project's own suite passes. It does NOT grade what any
# entry point returns -- the seed asks for behaviour to be characterised, not corrected.
JAR="${1:?usage: smoke_participation.sh <artifact-jar>}"
TREE="$(cd "$(dirname "$JAR")/.." && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
n=0; pass=0
chk(){ n=$((n+1)); if [ "$1" = "$2" ]; then pass=$((pass+1)); echo "PASS [$n] $3"; \
       else echo "FAIL [$n] $3 (want $2 got $1)"; fi; }

# An entry point counts as covered when ONE test method calls it ON ITS OWN TYPE and asserts a value.
# Unlike structdiff, most entry points here are instance methods and three names (grant, tick,
# expired) exist on more than one type, so a call is attributed to a type by its receiver: the type
# itself (a static call), a local, field or parameter declared with that type (or `var x = Type.of(..)`
# / `new Type(..)`), or a chain that starts at the type -- read by walking back over the call chain,
# with comments removed first (a comment above the call once hid a covered entry point). A value assertion is an equality, exact
# contents, a size or null (assertNull is assertEquals(null, x)); for a boolean entry point also assertTrue/assertFalse around the call, or
# assertThat(<call>).isTrue()/isFalse(). assertNotNull and "not empty" do not count.
# Methods are split at @Test; a helper method's assertions count only inside the test that holds them.
covered(){
    python3 - "$TREE/src/test" "$1" "$2" <<'PY'
import os, re, sys
root, cls, m = sys.argv[1], sys.argv[2], sys.argv[3]
value = re.compile(r'assertEquals\(|assertNull\(|assertIterableEquals\(|assertArrayEquals\(|assertLinesMatch\(|assertSame\('
                   r'|\.isEqualTo\(|\.isCloseTo\(|\.containsExactly(InAnyOrder)?\(|\.hasSize\(|\.containsOnly\(|\.isSameAs\('
                   r'|\.isNull\(|\.containsEntry\(|\.isEmpty\(')
T = re.escape(cls)
def names_of_type(src):
    ns = set(re.findall(r'(?<![\w.])' + T + r'(?:<[^>]*>)?\s+(\w+)\s*[=;,)]', src))
    ns |= set(re.findall(r'\bvar\s+(\w+)\s*=\s*(?:new\s+' + T + r'\b|' + T + r'\.\w+\()', src))
    return ns
def receiver(chunk, dot):
    # Walk back from the '.' before the method over the call chain it ends: identifiers, dots and
    # balanced (...) groups. Returns the chain's text, e.g. "Type.of(a, b)" or "ticked".
    i = dot
    while i > 0:
        c = chunk[i - 1]
        if c == ')':
            depth, j = 0, i - 1
            while j >= 0:
                depth += chunk[j] == ')'
                depth -= chunk[j] == '('
                if depth == 0: break
                j -= 1
            i = j
        elif c.isalnum() or c in '_.':
            i -= 1
        elif c.isspace() and chunk[:i].rstrip().endswith('new'):
            i = len(chunk[:i].rstrip()) - 3
        else:
            break
    return chunk[i:dot].strip()
def attributed(chunk, names):
    for mt in re.finditer(r'\.' + re.escape(m) + r'\(', chunk):
        recv = receiver(chunk, mt.start())
        head = re.split(r'[.(\s]', recv)[0] if recv else ''
        if head == 'new':
            head = re.split(r'[.(\s]', recv[3:].strip())[0]
        # the type itself (static), a name declared with it, or a chain that starts at it
        if head == cls or head in names:
            return True
    return False
for d, _, fs in os.walk(root):
    for f in fs:
        if not f.endswith('.java'):
            continue
        src = open(os.path.join(d, f), encoding='utf-8', errors='replace').read()
        # comments are prose: a receiver or an assertion named in one is not a call
        src = re.sub(r'/\*.*?\*/', ' ', src, flags=re.S)
        src = re.sub(r'//[^\n]*', '', src)
        if not re.search(r'\b' + T + r'\b', src):
            continue
        names = names_of_type(src)
        truth = re.compile(r'assert(True|False)\([^;]*\.' + re.escape(m) + r'\(|assertThat\([^;]*\.' + re.escape(m)
                           + r'\([^;]*\)\s*\.(isTrue|isFalse)\(')
        for chunk in src.split('@Test')[1:]:
            if attributed(chunk, names) and (value.search(chunk) or truth.search(chunk)):
                print('covered'); sys.exit()
print('uncovered')
PY
}
for e in ParticipationWindow.of ParticipationWindow.grant ParticipationWindow.shrink ParticipationWindow.expired \
         ParticipationWindows.grant ParticipationWindows.revoke ParticipationWindows.tick ParticipationWindows.boost \
         ChannelRequest.horizonFromDecay ChannelRequest.raise ChannelRequest.expired SignalType.of; do
    chk "$(covered "${e%%.*}" "${e#*.}")" covered "$e is covered by a test that asserts a value"
done

# The library code is the operator's: compared against a fresh extraction of the original.
REV=$(cat "$ROOT/data/participation/bootstrap/origin-rev" 2>/dev/null)
SCRATCH=$(mktemp -d)
if [ -n "$REV" ] && "$ROOT/scripts/extract_participation.sh" "$REV" "$SCRATCH/orig" > /dev/null 2>&1; then
    diff -rq "$SCRATCH/orig/src/main" "$TREE/src/main" > /dev/null 2>&1 && u=untouched || u=modified
else
    u=unknown
fi
chk "$u" untouched "src/main is unmodified"

# The project's own suite. Run in a copy: grading must not write into the artifact it grades.
cp -a "$TREE" "$SCRATCH/p" 2>/dev/null; rm -rf "$SCRATCH/p/target"
if (cd "$SCRATCH/p" && timeout 600 mvn -B -q -o test > "$SCRATCH/test.log" 2>&1); then
    chk pass pass "the project's own test suite passes"
else
    chk fail pass "the project's own test suite passes"
    echo "      $(grep -m1 -E 'Tests run|ERROR' "$SCRATCH/test.log" 2>/dev/null | head -c 120)"
fi
rm -rf "$SCRATCH"
echo "SMOKE: $pass/$n"
