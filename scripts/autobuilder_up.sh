#!/usr/bin/env bash
# Is the autobuilder JVM running? Prints "up" or "down".
#
# Exists as a FILE so a caller's command line does not contain the jar name. A monitor that greps
# for the name inline puts that string into its own /proc cmdline, and preflight's G1 guard then
# counts the monitor as a live JVM and refuses to relaunch (2026-07-30). Keeping the pattern in here
# means only this script's path appears in any caller's command line.
for p in /proc/[0-9]*/cmdline; do
    pid="${p#/proc/}"; pid="${pid%/cmdline}"
    [ "$(cat "/proc/$pid/comm" 2>/dev/null)" = "java" ] || continue
    if tr '\0' ' ' < "$p" 2>/dev/null | grep -q 'autobuilder-.*-SNAPSHOT\.jar'; then
        echo up; exit 0
    fi
done
echo down
