#!/bin/bash
# SessionStart hook for Claude Code on the web: bring up everything autobuilder needs to run.
#   1. Docker daemon (not running in a fresh container)
#   2. Neo4j 5 on bolt://localhost:7687, password matching application.yml
#   3. com.walpro:semantic-memory-substrate installed into ~/.m2 (sibling repo, not on Maven Central)
#   4. autobuilder jar built (tests skipped)
#   5. LLM endpoint pointed at Anthropic's OpenAI-compatible API with Haiku
#
# The API key is NOT set here. Store it in the cloud environment settings as OPENAI_API_KEY;
# application.yml reads it from there. Never commit a key to this file.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
LOG_DIR="${TMPDIR:-/tmp}/autobuilder-session"
mkdir -p "$LOG_DIR"

log() { echo "[session-start] $*" >&2; }

# --- 1. Docker daemon ------------------------------------------------------------------------
if ! docker info >/dev/null 2>&1; then
  log "starting dockerd"
  setsid nohup dockerd >"$LOG_DIR/dockerd.log" 2>&1 < /dev/null &
  for _ in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 1; done
fi

# --- 2. Neo4j --------------------------------------------------------------------------------
# Docker Hub rate-limits anonymous pulls from here (429), so pull from Google's mirror.
if docker info >/dev/null 2>&1; then
  if docker container inspect neo4j >/dev/null 2>&1; then
    docker start neo4j >/dev/null || log "WARN: could not start neo4j container"
  else
    log "starting neo4j"
    docker run -d --name neo4j --network host \
      -e NEO4J_AUTH=neo4j/your_password \
      mirror.gcr.io/library/neo4j:5 >/dev/null || log "WARN: could not run neo4j"
  fi
else
  log "WARN: Docker daemon unavailable; Neo4j not started"
fi

# --- 3. semantic-memory-substrate ------------------------------------------------------------
SUBSTRATE_JAR="$HOME/.m2/repository/com/walpro/semantic-memory-substrate/0.0.1-SNAPSHOT/semantic-memory-substrate-0.0.1-SNAPSHOT.jar"
SUBSTRATE_DIR="$(dirname "$PROJECT_DIR")/semantic-memory-substrate"
if [ ! -f "$SUBSTRATE_JAR" ]; then
  if [ ! -d "$SUBSTRATE_DIR/.git" ]; then
    log "cloning semantic-memory-substrate"
    git clone --depth 1 https://github.com/michael-sandy-walker/semantic-memory-substrate "$SUBSTRATE_DIR" \
      || log "WARN: clone failed (is the repo attached to this session?)"
  fi
  if [ -d "$SUBSTRATE_DIR/.git" ]; then
    log "installing semantic-memory-substrate"
    (cd "$SUBSTRATE_DIR" && mvn -q -B -DskipTests install)
  fi
fi

# --- 4. Build autobuilder ----------------------------------------------------------------------
if [ -f "$SUBSTRATE_JAR" ]; then
  log "building autobuilder"
  (cd "$PROJECT_DIR" && mvn -q -B -DskipTests package)
else
  log "WARN: substrate not installed; skipping autobuilder build"
fi

# --- 5. LLM endpoint (Spring relaxed binding: LLM_API_URL -> llm.api.url) ---------------------
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo 'export LLM_API_URL=https://api.anthropic.com/v1/chat/completions'
    echo 'export LLM_API_MODEL=claude-haiku-4-5-20251001'
  } >> "$CLAUDE_ENV_FILE"
fi
if [ -z "${OPENAI_API_KEY:-}" ]; then
  log "WARN: OPENAI_API_KEY is not set; add your Anthropic key to the environment settings"
fi

log "done"
