#!/bin/bash
# capability_reference_run.sh <case-name> <source-dir> -- one capability-reference run on a scratch copy of
# data/labelmaker-pantry (T4-EJ's case): lays the copy out as data/<case-name>, starts the application held, imports
# the label capability from <source-dir> as adapted with its source kept (scripts/capability_reference_choice.json),
# waits for the LOOP'S OWN terminal line ("complete. Result: GOAL_FULFILLED|GOAL_EXHAUSTED" -- not the word
# GOAL_EXHAUSTED anywhere, which the signal name SUBGOAL_EXHAUSTED matches) or 25 minutes, stops the application
# and scores the tree. Output files go to $CAPRUN_OUT (default: a new temporary directory). Needs a packaged jar,
# ports 8080/8081 free, Neo4j up. The scratch copy carries no run identity of another project.
set -u
cd "$(dirname "$0")/.."
S="${CAPRUN_OUT:-$(mktemp -d)}"; echo "output files in $S"
C=$1; SRC=$2
mkdir -p data/$C && cp -a data/labelmaker-pantry/bootstrap data/$C/bootstrap && scripts/prepare_capability_run.sh $C >/dev/null || exit 1
(FILE_BASE_PATH=data/$C setsid nohup java -jar target/autobuilder-1.0.1-SNAPSHOT.jar --spring.profiles.active=dev-auto --autobuilder.start.mode=ASK --autobuilder.goal.sync-from-graph=false --autobuilder.goal.migrate-to-graph=true > $S/$C-stdout.log 2>&1 < /dev/null & disown)
n=0; until curl -s -m 2 localhost:8081/autobuilder/status >/dev/null || [ $n -gt 60 ]; do sleep 3; n=$((n+1)); done
python3 scripts/capability_import.py discover $SRC --label labelmaker > $S/$C-discover.txt 2>&1
sel=$(grep -o "cap-[0-9a-f]\{8\}" $S/$C-discover.txt | head -1)
# the capability that generates the label: the one declaring Labeler.next
cap=$(python3 - "$S/$C-discover.txt" <<'P'
import re,sys
t=open(sys.argv[1]).read()
best=None
for m in re.finditer(r'\n(c\d+): (.*?)\n   declarations: (.*?)\n', t):
    if 'Labeler.next' in m.group(3): best=m.group(1)
print(best or '')
P
)
echo "selection $sel label capability $cap: $(grep "^$cap:" $S/$C-discover.txt | cut -c1-120)"
[ -z "$cap" ] && { echo "no capability declares Labeler.next"; exit 2; }
python3 - scripts/capability_reference_choice.json "$S/$C-choices.json" "$cap" <<'P'
import json,sys
d=json.load(open(sys.argv[1])); v=list(d.values())[0]
json.dump({sys.argv[3]:v},open(sys.argv[2],'w'))
P
python3 scripts/capability_import.py choose $sel $S/$C-choices.json > $S/$C-choose.txt 2>&1
python3 scripts/capability_import.py import $sel 2>&1 | tail -3
L=data/$C/logs/autobuilder.log
n=0; until grep -qE "complete\. Result: (GOAL_FULFILLED|GOAL_EXHAUSTED)|SYSTEM_PAUSED=" $L || [ $n -gt 500 ]; do sleep 3; n=$((n+1)); done
echo "cycles $(grep -c 'CYCLE cycle-' $L) terminal: $(grep -oE 'complete\. Result: (GOAL_FULFILLED|GOAL_EXHAUSTED)' $L | tail -1) (none = stopped by this script's cap) asked: $(grep -c 'evolution asked the reference' $L)"
pid=$(ss -ltnp 2>/dev/null | grep ":8081 " | grep -o "pid=[0-9]*" | head -1 | cut -d= -f2); [ -n "$pid" ] && kill $pid
n=0; until ! ss -ltn | grep -q ":8081 " || [ $n -gt 20 ]; do sleep 2; n=$((n+1)); done
for c in $(ls data/$C/mutations); do f=data/$C/mutations/$c/src/main/java/org/pantry/store; echo "  $c: $(find data/$C/mutations/$c -name '*.java' | sed 's|.*/||' | tr '\n' ' ') right-rule-marks=$(cat $(find data/$C/mutations/$c -name '*.java') 2>/dev/null | grep -c 'ABCDEFGHJKLMNPRTVWX\|% 19')"; done
SMOKE_TARGET=$C scripts/smoke_capability_reference.sh data/$C/output 2>&1 | grep "FAIL\|SMOKE"
