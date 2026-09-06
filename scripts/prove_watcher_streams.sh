#!/usr/bin/env bash
# Prove a monitor pipeline STREAMS before arming it against a live run.
#
# Why this exists: on 2026-09-02 a `tail -F launch.log applog | awk -f filter.awk` monitor
# watched a 60-minute run with both processes alive at the end and its output file at ZERO
# BYTES. The pipeline was correct -- replaying it against the finished file printed the
# terminal line instantly. Only the streaming case failed: /usr/bin/awk is mawk, which reads
# its INPUT from a pipe in ~4KB blocks, and the log accumulated 4515 bytes across the whole
# hour, so everything past the first block sat unread in mawk's buffer forever.
#
# The pre-arm check that missed it ran as `printf ... | awk -f filter.awk` -- a closed stdin
# that flushes at EOF. It exercised the pipeline for CONTENT and never for STREAMING. Those
# are different properties, and only the second one is what a monitor does for a living.
#
# So this harness never tests content. It appends ONE SHORT line to a growing file and asks
# whether the pipeline emits within a deadline. The line is deliberately tiny: a large write
# would exceed mawk's block and flush, which is exactly why every after-the-fact test passed.
#
# Usage:
#   scripts/prove_watcher_streams.sh --pipeline 'tail -F {} | awk -f scripts/filter.awk'
#   scripts/prove_watcher_streams.sh --pipeline 'tail -F {} | grep PREFLIGHT:' --timeout 15
#
#   {}          is replaced by the scratch file this harness grows. Required.
#   --marker    the line appended (default: a generated PREFLIGHT: line). Must be something
#               the pipeline under test would report, or the proof tests nothing.
#   --timeout   seconds to wait for the pipeline to emit (default 10).
#
# Exit 0 = the pipeline streams. Exit 1 = it does not, and the diagnostic ladder below has
# already run and named the buffering stage plus its verified fix.
set -u

PIPELINE=""
MARKER=""
TIMEOUT=10

while [ $# -gt 0 ]; do
  case "$1" in
    --pipeline) PIPELINE="${2:-}"; shift 2 ;;
    --marker)   MARKER="${2:-}"; shift 2 ;;
    --timeout)  TIMEOUT="${2:-}"; shift 2 ;;
    -h|--help)  sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$PIPELINE" ]; then
  echo "ERROR: --pipeline is required (use {} where the watched file goes)." >&2
  exit 2
fi
case "$PIPELINE" in
  *"{}"*) ;;
  *) echo "ERROR: --pipeline must contain {} -- the file this harness grows." >&2; exit 2 ;;
esac
case "$TIMEOUT" in
  ''|*[!0-9]*) echo "ERROR: --timeout must be a whole number of seconds." >&2; exit 2 ;;
esac

SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/watcher-stream-proof-XXXXXX")"
[ -n "$MARKER" ] || MARKER="PREFLIGHT: streaming-proof-$$-$(date +%s)"

GROUPS_TO_KILL=""
# Killing the launched shell is NOT enough: `sh -c 'tail -F f | awk ...'` forks both stages
# and waits, so killing sh orphans a live `tail` that then holds the scratch file open
# forever. Observed leaking one tail per probe. The process GROUP is the thing that stops
# them, so each probe runs under `setsid` (making its pid its own pgid) and reports that
# pgid back through a file -- read back rather than taken from `$!`, because setsid forks
# when the caller is not already a group leader and `$!` is then the wrong pid.
#
# Deliberately NOT `pkill -f <scratch-path>`: a pattern that appears in the killing
# command's own command line matches the caller too. That trap is recorded in the runbook
# and it bit this very session (exit 144) while cleaning up after an earlier version of
# this script. Match by pid, never by a string this command contains.
kill_group() {
  [ -n "${1:-}" ] || return 0
  kill -- "-$1" 2>/dev/null || true
}
cleanup() {
  for pgid in $GROUPS_TO_KILL; do
    kill_group "$pgid"
  done
  rm -rf "$SCRATCH"
}
trap cleanup EXIT INT TERM

# run_probe <label> <pipeline-template> -> 0 when the marker reaches the output within
# $TIMEOUT. Each probe gets its own watched file, so a stage that already flushed cannot
# make the next one look healthy.
run_probe() {
  probe_label="$1"
  probe_pipeline="$2"
  probe_dir="$SCRATCH/$probe_label"
  mkdir -p "$probe_dir"
  watched="$probe_dir/watched.log"
  out="$probe_dir/out"
  : > "$out"
  # Pre-existing content, kept well under a 4KB block: `tail -F` attaches at the end by
  # default, so this is never what the pipeline reports -- it only makes the file real
  # enough for tail to open without racing.
  printf 'preamble line, not the marker\n' > "$watched"

  cmd="${probe_pipeline//\{\}/$watched}"
  pgid_file="$probe_dir/pgid"
  # setsid makes the probe a session leader, so its own pid IS its process group id; it
  # writes that back here rather than relying on `$!`.
  setsid sh -c 'echo $$ > "$0"; shift; exec sh -c "$1"' \
    "$pgid_file" -- "$cmd" > "$out" 2>&1 &

  probe_pgid=""
  waited=0
  while [ "$waited" -lt 5 ] && [ -z "$probe_pgid" ]; do
    [ -s "$pgid_file" ] && probe_pgid="$(cat "$pgid_file" 2>/dev/null)"
    [ -n "$probe_pgid" ] || sleep 1
    waited=$((waited + 1))
  done
  GROUPS_TO_KILL="$GROUPS_TO_KILL $probe_pgid"

  # Let the pipeline attach before the marker is written. A marker appended before `tail`
  # opens the file is read from the preamble region and proves nothing about streaming.
  sleep 1

  printf '%s\n' "$MARKER" >> "$watched"

  waited=0
  while [ "$waited" -lt "$TIMEOUT" ]; do
    if grep -qF -- "$MARKER" "$out" 2>/dev/null; then
      echo "  PASS  ${probe_label}: emitted after ${waited}s"
      kill_group "$probe_pgid"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done

  bytes=$(wc -c < "$out" 2>/dev/null || echo 0)
  echo "  FAIL  ${probe_label}: silent for ${TIMEOUT}s (output ${bytes} bytes)"
  kill_group "$probe_pgid"
  return 1
}

echo "Streaming proof"
echo "  pipeline: $PIPELINE"
echo "  marker  : $MARKER"
echo "  deadline: ${TIMEOUT}s"
echo

if run_probe "pipeline-under-test" "$PIPELINE"; then
  echo
  echo "STREAMS. Safe to arm against a live run."
  echo "Re-run this proof whenever the pipeline changes -- content-correct and"
  echo "stream-correct are different properties, and only a change to the second one"
  echo "is invisible to every after-the-fact replay."
  exit 0
fi

echo
echo "DOES NOT STREAM. This pipeline would watch a live run and report nothing."
echo "Walking the diagnostic ladder that found the 2026-09-02 case fast."
echo

run_probe "tail-to-cat"              'tail -F {} | cat'
tail_ok=$?
run_probe "tail-to-grep-linebuffer"  'tail -F {} | grep --line-buffered -F "PREFLIGHT:"'
grep_ok=$?
# Distinguishes "grep fixes it" from "grep fixes it only as the LAST stage" -- the
# distinction the runbook got wrong. If this passes while tail-to-awk fails, the remedy is
# positional, not about grep.
run_probe "grep-linebuffer-then-awk" 'tail -F {} | grep --line-buffered -F "PREFLIGHT:" | awk "{print}"'
grep_then_awk_ok=$?
run_probe "tail-to-awk"              'tail -F {} | awk "/PREFLIGHT:/{print}"'
awk_ok=$?

echo
echo "Verdict"
if [ "$tail_ok" -ne 0 ]; then
  echo "  tail itself did not stream. That is NOT the recorded failure -- check that the"
  echo "  file is appended to rather than rewritten, and that tail -F (not -f) is used so"
  echo "  a rotated file is re-opened."
elif [ "$awk_ok" -ne 0 ] && [ "$grep_ok" -eq 0 ]; then
  awk_real="$(readlink -f "$(command -v awk 2>/dev/null)" 2>/dev/null || echo unknown)"
  echo "  awk is the buffering stage. Resolved awk: ${awk_real}"
  echo
  echo "  FIXES THAT WORK (re-measured by this harness 2026-09-08, not inherited):"
  echo "    - mawk: add -Winteractive        e.g. awk -Winteractive -f filter.awk"
  echo "    - or drop awk from the pipeline and let grep --line-buffered be the last stage"
  echo
  echo "  DOES NOT FIX IT -- each of these was measured failing, and the first two are"
  echo "  actively recommended somewhere:"
  echo "    - fflush() flushes awk's OUTPUT. Here the record is never READ, so there is"
  echo "      nothing to flush. (The Monitor tool guidance names this as the remedy.)"
  echo "    - grep --line-buffered IN FRONT of awk. grep then emits per line, but mawk is"
  echo "      still the last stage reading a pipe in blocks, so the line sits in ITS buffer."
  echo "      Recorded as verified in the runbook until 2026-09-08; it is not. The grep"
  echo "      remedy only works when grep is the LAST stage."
  echo "    - stdbuf -i0 -oL on awk. mawk does not take its input buffering from stdio."
  echo "    - stdbuf -oL on tail. tail was never the buffer -- tail | cat streams fine."
else
  echo "  The stage that buffers is inside the pipeline's own filter, not tail and not awk."
  echo "  Bisect it the same way this ladder does: replace the tail of the pipeline with"
  echo "  cat, confirm it streams, then re-add one stage at a time until it goes silent."
fi
echo
echo "Related recorded hazards -- check these before inventing a ninth variant:"
echo "  - A liveness predicate must never be a string the checking command itself contains."
echo "    pgrep -f / pkill -f self-match your own watcher. Match by PID or a file marker."
echo "  - n=\$(grep -c PAT f || echo 0) assigns the TWO-LINE string \"0\\n0\"; every later"
echo "    numeric test dies with 'integer expression expected' and evaluates FALSE, so the"
echo "    watcher goes inert at exactly the counts where it must report the first match."
echo "    Use c=\$(grep -c ... 2>/dev/null || true); c=\${c:-0}"
echo "  - A monitor filter must carry EVERY terminal state. Quieting a noisy filter throws"
echo "    away the terminal markers it also carried; re-arm a narrower one, never go blind."
echo
echo "Full accounts: .claude-memory/project_run_lifecycle_runbook.md"
exit 1
