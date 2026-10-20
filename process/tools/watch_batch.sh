#!/bin/bash
# batch watcher (from field90): polls each minute; follows the live case (newest launch log), emits new results/early_stop
# lines, closure and grind markers of the live case, environment alarms. Counts reset when the case changes.
D=${BATCH_DIR:?set BATCH_DIR to the batch dir, e.g. ~/.autobuilder/field91}
R=/home/anugeshtu/IdeaProjects/autobuilder
declare -A seen m
KEYS=("T6-T: GRIND" "T6-T: OBJECTIVE MET" "marked COMPLETED" "marked GIVEN_UP" "Result: GOAL_EXHAUSTED" "moved-nested-type --" "second reading")
cnt() { local c; c=$(grep -c -- "$1" "$APP" 2>/dev/null || true); echo "${c:-0}"; }
cur_target() { ls -t $D/launch-*.log 2>/dev/null | head -1 | sed -E 's/.*launch-(.*)-[0-9]+\.log/\1/'; }
T=""; APP=""; ino=""; envbad=0; silent=0
if [ "$1" = rearm ]; then
  for f in $D/results.txt $D/early-*.log $D/driver.log; do [ -f "$f" ] && seen[$f]=$(wc -l < "$f"); done
  T=$(cur_target); APP=$R/data/$T/logs/autobuilder.log; ino=$(stat -c %i "$APP" 2>/dev/null || true)
  for k in "${KEYS[@]}"; do m[$k]=$(cnt "$k"); done
fi
echo "watch_batch armed $(date +%H:%M) case=${T:-none}"
while true; do
  for f in $D/results.txt $D/early-*.log $D/driver.log; do
    [ -f "$f" ] || continue
    n=$(wc -l < "$f"); p=${seen[$f]:-0}
    if [ "$n" -gt "$p" ]; then tail -n +"$((p+1))" "$f" | sed "s|^|$(basename $f): |"; seen[$f]=$n; fi
  done
  t=$(cur_target)
  if [ -n "$t" ] && [ "$t" != "$T" ]; then T=$t; APP=$R/data/$T/logs/autobuilder.log; ino=""; echo "case now: $T"; fi
  if [ -n "$APP" ]; then
    c=$(stat -c %i "$APP" 2>/dev/null || true)
    if [ "$c" != "$ino" ]; then ino=$c; for k in "${KEYS[@]}"; do m[$k]=0; done; fi
    for k in "${KEYS[@]}"; do
      v=$(cnt "$k"); if [ "$v" != "${m[$k]:-0}" ]; then echo "$(date +%H:%M) $T [$k] x$v: $(grep -- "$k" "$APP" | tail -1 | cut -c1-220)"; m[$k]=$v; fi
    done
    if [ -f "$APP" ]; then age=$(( $(date +%s) - $(stat -c %Y "$APP") ))
      if [ $age -gt 900 ] && [ $silent -eq 0 ]; then echo "SILENCE: $T applog quiet ${age}s, load $(cut -d' ' -f1-3 /proc/loadavg)"; silent=1; fi
      [ $age -lt 300 ] && silent=0; fi
  fi
  avail=$(awk '/MemAvailable/{print int($2/1048576)}' /proc/meminfo)
  if ! ss -tln | grep -q ':7687 ' || [ "$avail" -lt 2 ]; then
    [ $envbad -eq 0 ] && echo "ENV ALARM: neo4j=$(ss -tln | grep -c ':7687 ') availGB=$avail"; envbad=1; else envbad=0; fi
  grep -q '^DONE' $D/results.txt 2>/dev/null && { echo "BATCH DONE"; exit 0; }
  sleep 60
done
