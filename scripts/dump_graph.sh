#!/bin/bash
# dump_graph.sh <output_dir> — T5-BD capture step (roadmap.md).
#
# Preserves the Neo4j graph's CURRENT content alongside a preflight run_dir's
# logs/GoalStack/output, so a run's full memory (not just the derived GoalStack
# snapshot) survives past the next run overwriting the live database.
#
# ONLINE, non-disruptive: uses cypher-shell MATCH queries (no APOC required,
# no database stop/restart). Deliberately NOT a neo4j-admin dump — that command
# refuses to run against a mounted/live database, and stopping the shared Neo4j
# container to get one is a separate, larger decision this script does not make.
# Fidelity is forensic (readable, diffable, greppable), not reload-into-a-fresh-
# graph — that harness-level capability is the rest of T5-BD, still note-level.
set -u

OUT="${1:?usage: dump_graph.sh <output_dir>}"
mkdir -p "$OUT"

CONTAINER="${NEO4J_CONTAINER:-neo4j}"
NEO4J_USER="${NEO4J_USER:-neo4j}"
NEO4J_PASS="${NEO4J_PASS:-your_password}"

cypher() {
    docker exec "$CONTAINER" cypher-shell -u "$NEO4J_USER" -p "$NEO4J_PASS" \
        --format plain --non-interactive "$1"
}

if ! docker exec "$CONTAINER" true 2>/dev/null; then
    echo "dump_graph.sh: container '$CONTAINER' not reachable — skipping graph capture" >&2
    exit 0
fi

cypher "MATCH (n) RETURN elementId(n) AS id, labels(n) AS labels, properties(n) AS props;" \
    > "$OUT/graph_nodes.txt" 2>"$OUT/graph_nodes.err" || \
    echo "dump_graph.sh: node dump failed, see graph_nodes.err" >&2

cypher "MATCH (a)-[r]->(b) RETURN elementId(a) AS from, type(r) AS type, properties(r) AS props, elementId(b) AS to;" \
    > "$OUT/graph_relationships.txt" 2>"$OUT/graph_relationships.err" || \
    echo "dump_graph.sh: relationship dump failed, see graph_relationships.err" >&2

{
    echo "captured: $(date -Iseconds)"
    echo "nodes: $(grep -c '.' "$OUT/graph_nodes.txt" 2>/dev/null || echo 0)"
    echo "relationships: $(grep -c '.' "$OUT/graph_relationships.txt" 2>/dev/null || echo 0)"
} > "$OUT/graph_dump_meta.txt"

echo "dump_graph.sh: graph captured to $OUT/graph_nodes.txt + graph_relationships.txt"
