---
name: feedback-batch-watcher-alarms-on-the-environment
description: A batch watcher must alarm on Neo4j not listening and on low available memory, and a run with almost no cycles is an environment failure, not a score.
metadata:
  type: feedback
---

Every batch watcher checks the environment each minute -- Neo4j listening on 7687 and available memory above about 2 GB -- and a run that completed almost no cycles is reported as lost, not scored.

**Why:** 2026-10-10. Docker Desktop was OOM-killed at 12:21, six minutes into a run, and took Neo4j with it. The run idled for 92 minutes at "1/51" and the next one died at startup; a session restart hid it further, and the batch looked like two measurements. The box was at 30 of 32 GB: llama-server about 9 GB of host memory with the model fully on the GPU, Firefox about 9 GB after 12 days up, the Docker VM (may grow to 8 GB), IntelliJ about 3 GB. A field run adds 2-3 GB at peak.

**How to apply:** in the watcher loop: `mem=$(awk '/MemAvailable/{print int($2/1048576)}' /proc/meminfo); neo=$(ss -tln | grep -c ':7687')` and emit an alert when `neo` is 0 or `mem` is under 2. On an alert stop the batch by PID before the OOM killer picks one of the user's applications, and say what holds the memory; do not close the user's programs. Before launching, read both values. A result with one cycle, zero merges and Neo4j exceptions in the log is an environment failure: check `systemctl --user status docker-desktop` and `docker ps -a`. Restart with `systemctl --user start docker-desktop` then `docker start neo4j`. Related: [[feedback-no-heavy-mvn-beside-a-live-run]], [[feedback-monitor-every-terminal]].
