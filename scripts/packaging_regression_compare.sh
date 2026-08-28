#!/bin/bash
# T4-DN comparison: did relaying the launcher's refusal reach a packaging repair?
#
# One row per run, same columns for every run, so A / A' / B are compared on the
# same measurements rather than on whatever grep was to hand. Counts EVENTS
# (injector firings, gate blocks) not prompt re-renders — the fact that a work
# item's text is echoed into every later prompt inflated three counts this session.
#
# Usage: packaging_regression_compare.sh <run-dir-or-archive> [more...]
printf "%-22s %-13s %7s %7s %9s %9s %8s %s\n" RUN SMOKE CYCLES MERGES NAMED-CAUSE NO-DIAG PKG-PLUGIN TERMINAL
for d in "$@"; do
    [ -d "$d" ] || continue
    name=$(basename "$d")
    log="$d/autobuilder.log"; cat="cat"
    [ -f "$log" ] || { log="$d/autobuilder.log.gz"; cat="zcat"; }
    [ -f "$log" ] || continue
    sum="$d/summary.txt"
    smoke=$(grep -oE "^smoke=.*" "$sum" 2>/dev/null | head -1 | cut -d= -f2 | tr -d ' ')
    term=$(grep -oE "^terminal=.*" "$sum" 2>/dev/null | head -1 | cut -d= -f2)
    cycles=$($cat "$log" | grep -c "CYCLE cycle-")
    merges=$($cat "$log" | grep -c "Merged: true")
    # the diagnostic the work item carried: the cause named, or the claim that none was logged
    named=$($cat "$log" | grep -cF "no main manifest attribute")
    nodiag=$($cat "$log" | grep -cF "logged nothing diagnostic")
    # THE ARTIFACT'S OWN POM IS THE AUTHORITY, not the log. Run A mentioned a packaging plugin
    # 27 times in prompts and analysis while its pom carried none — the same event-vs-rendering
    # conflation that inflated three counts this session. Ask the file.
    pom="$d/artifact/pom.xml"
    if [ -f "$pom" ]; then
        pkg=$(grep -cE "spring-boot-maven-plugin|maven-shade-plugin|maven-assembly-plugin" "$pom")
        pkg="pom:$pkg"
    else
        pkg="no-pom"
    fi
    printf "%-22s %-13s %7s %7s %9s %9s %8s %s\n" \
        "$name" "${smoke:-?}" "$cycles" "$merges" "$named" "$nodiag" "$pkg" "${term:-?}"
done
