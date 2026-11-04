#!/usr/bin/env python3
"""bundle.py -- T4-CR: store a project phase (code + its lineage subgraph) and restore it elsewhere.

  scripts/bundle.py [--neo4j URL] store   <target> <name> [--lineage ID] [--from DIR]
  scripts/bundle.py [--neo4j URL] restore <name> <target> [--replace]
  scripts/bundle.py list

A bundle lives in ~/.autobuilder/bundles/<name>/:
  files/     output/ (sources; build output left out), bootstrap/, meta/ (goal stack, run identity,
             preserved artifact, end report) and checkpoint-archive/ (what the graph's Checkpoint
             nodes point at)
  graph.json every node stamped with the lineage, the nodes one hop outside it that its edges reach
             (e.g. a Blocker first seen by an earlier lineage), and every edge between them
  provenance.json  which lineage, which target, when, from which commit, the root goal and its status

Why the cut is clean: after T1-D + T4-CJ a lineage is a self-contained subgraph -- nearly every node
carries lineageId, and the boot loads goals by the lineage named in meta/run-identity.json. So a
restore that brings the files and the subgraph back together is a lineage the loop resumes as its
own (CONTINUE_LINEAGE; the rewind's lineage check compares exactly these two).

Restore CONTINUES the stored lineage under its own id, so it refuses a database that already holds
that lineage (a second copy would be two roots claiming one id) unless --replace, which first removes
that lineage's nodes. Forking a child lineage from a bundle is not built: it needs every stamped id
re-minted consistently, and the first use -- moving a phase to a clean database -- does not.

Values survive the round trip with their types: the HTTP API renders a datetime as a string, so each
one is tagged on export and turned back into a datetime on import.
"""
import argparse, base64, datetime, json, os, shutil, subprocess, sys, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUNDLES = Path.home() / ".autobuilder" / "bundles"
FILE_PARTS = ["output", "bootstrap", "meta", "checkpoint-archive"]
SKIP_DIRS = {"target", "build", ".gradle", "node_modules"}
BATCH = 500


class Neo4j:
    def __init__(self, url, user, password):
        self.url = url.rstrip("/") + "/db/neo4j/tx/commit"
        self.auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()

    def run(self, statement, **params):
        body = json.dumps({"statements": [{"statement": statement, "parameters": params}]}).encode()
        req = urllib.request.Request(self.url, data=body, headers={
            "Content-Type": "application/json", "Authorization": self.auth})
        with urllib.request.urlopen(req, timeout=300) as r:
            res = json.load(r)
        if res.get("errors"):
            raise RuntimeError(res["errors"][0].get("message", res["errors"]))
        result = res["results"][0]
        cols = result["columns"]
        return [dict(zip(cols, row["row"])) for row in result["data"]]


def serving(target):
    """True when a running Autobuilder JVM has data/<target> as its file.base-path."""
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        try:
            cmd = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
            if "autobuilder" not in cmd.lower() or "java" not in cmd:
                continue
            env = Path(f"/proc/{pid}/environ").read_bytes().split(b"\0")
        except Exception:
            continue
        for e in env:
            if e.startswith(b"FILE_BASE_PATH=") and e.decode(errors="replace").rstrip("/").endswith("data/" + target):
                return True
        if f"data/{target} " in cmd + " " or f"data/{target}/ " in cmd + " ":
            return True
    return False


def git_rev():
    try:
        return subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "--short", "HEAD"], text=True).strip()
    except Exception:
        return None


def copy_tree(src, dst):
    def ignore(d, names):
        return [n for n in names if n in SKIP_DIRS and Path(d, n).is_dir()]
    shutil.copytree(src, dst, ignore=ignore, symlinks=True)


# --- store ------------------------------------------------------------------------------------

EXPORT_NODES = """
MATCH (n) WHERE n.lineageId = $lineage
RETURN elementId(n) AS id, labels(n) AS labels,
       [k IN keys(n) | [k, valueType(n[k]) STARTS WITH 'ZONED DATETIME',
                        CASE WHEN valueType(n[k]) STARTS WITH 'ZONED DATETIME' THEN toString(n[k]) ELSE n[k] END]] AS props
"""

EXPORT_BOUNDARY = """
MATCH (a)-[]-(b) WHERE a.lineageId = $lineage AND (b.lineageId IS NULL OR b.lineageId <> $lineage)
WITH DISTINCT b
RETURN elementId(b) AS id, labels(b) AS labels,
       [k IN keys(b) | [k, valueType(b[k]) STARTS WITH 'ZONED DATETIME',
                        CASE WHEN valueType(b[k]) STARTS WITH 'ZONED DATETIME' THEN toString(b[k]) ELSE b[k] END]] AS props
"""

EXPORT_RELS = """
MATCH (a)-[r]->(b)
WHERE a.lineageId = $lineage OR b.lineageId = $lineage
RETURN elementId(a) AS src, type(r) AS type, properties(r) AS props, elementId(b) AS dst
"""


def node_row(rec, boundary):
    props, dts = {}, []
    for k, is_dt, v in rec["props"]:
        props[k] = v
        if is_dt:
            dts.append(k)
    return {"key": rec["id"], "labels": rec["labels"], "props": props, "dt": dts, "boundary": boundary}


def store(args):
    target_dir = Path(args.source).expanduser() if args.source else ROOT / "data" / args.target
    if not target_dir.is_dir():
        sys.exit(f"bundle: no target at {target_dir}")
    if not args.source and serving(args.target):
        sys.exit(f"bundle: a running Autobuilder serves data/{args.target} -- stop it first, so the files "
                 f"and the graph describe the same moment")
    lineage = args.lineage
    identity = target_dir / "meta" / "run-identity.json"
    if not lineage:
        if not identity.is_file():
            sys.exit("bundle: the target has no meta/run-identity.json; name the lineage with --lineage")
        lineage = json.loads(identity.read_text())["lineageId"]
    dest = BUNDLES / args.name
    if dest.exists():
        sys.exit(f"bundle: {dest} exists -- pick another name or remove it")

    db = Neo4j(args.neo4j, args.user, args.password)
    nodes = [node_row(r, False) for r in db.run(EXPORT_NODES, lineage=lineage)]
    if not nodes:
        sys.exit(f"bundle: the graph holds no node of {lineage}")
    boundary = [node_row(r, True) for r in db.run(EXPORT_BOUNDARY, lineage=lineage)]
    rels = db.run(EXPORT_RELS, lineage=lineage)

    stack = target_dir / "meta" / "GoalStack.json"
    pointers = {}
    if stack.is_file():
        g = json.loads(stack.read_text())
        pointers = {"rootGoalId": g.get("currentGoalId"), "currentSubGoalId": g.get("currentSubGoalId")}
    root = next((n for n in nodes if "Goal" in n["labels"] and n["props"].get("id") == pointers.get("rootGoalId")), None)

    tmp = BUNDLES / (args.name + ".partial")
    shutil.rmtree(tmp, ignore_errors=True)
    (tmp / "files").mkdir(parents=True)
    bootstrap_from = None
    for part in FILE_PARTS:
        if (target_dir / part).exists():
            copy_tree(target_dir / part, tmp / "files" / part)
        elif part == "bootstrap" and args.source and (ROOT / "data" / args.target / "bootstrap").is_dir():
            # A run's state moved aside leaves bootstrap/ behind in data/<target>: it is the target's.
            copy_tree(ROOT / "data" / args.target / "bootstrap", tmp / "files" / part)
            bootstrap_from = f"data/{args.target}/bootstrap"
    (tmp / "graph.json").write_text(json.dumps({"nodes": nodes + boundary, "rels": rels}))
    prov = {
        "name": args.name, "target": args.target, "lineageId": lineage,
        "storedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "gitRev": git_rev(), "pointers": pointers,
        "rootStatus": root["props"].get("status") if root else None,
        "nodes": len(nodes), "boundaryNodes": len(boundary), "relationships": len(rels),
        "files": [p for p in FILE_PARTS if (tmp / "files" / p).exists()],
        "bootstrapFrom": bootstrap_from,
    }
    (tmp / "provenance.json").write_text(json.dumps(prov, indent=2) + "\n")
    tmp.rename(dest)
    print(f"bundle: stored {args.name}: {lineage}, {len(nodes)} nodes + {len(boundary)} boundary, "
          f"{len(rels)} relationships, files {prov['files']} -> {dest}")


# --- restore ----------------------------------------------------------------------------------

CREATE_NODES = """
UNWIND $rows AS row
CREATE (n) SET n = row.props, n._bundleKey = row.key, n:$(row.labels)
FOREACH (k IN row.dt | SET n[k] = datetime(n[k]))
"""

# A boundary node already present (the same labels and exactly the same properties) is reused, not
# duplicated: it belongs to another lineage that may still be in this database.
MERGE_BOUNDARY = """
UNWIND $rows AS row
OPTIONAL MATCH (e:$(row.labels)) WHERE properties(e) = row.props
WITH row, head(collect(e)) AS existing
CALL (row, existing) {
  WITH row, existing WHERE existing IS NOT NULL
  SET existing._bundleKey = row.key, existing:BundleKeyed
  RETURN 1 AS reused
UNION
  WITH row, existing WHERE existing IS NULL
  CREATE (n) SET n = row.props, n._bundleKey = row.key, n:$(row.labels), n:BundleKeyed
  FOREACH (k IN row.dt | SET n[k] = datetime(n[k]))
  RETURN 0 AS reused
}
RETURN sum(reused) AS reused
"""

CREATE_RELS = """
UNWIND $rows AS row
MATCH (a:BundleKeyed {_bundleKey: row.src}), (b:BundleKeyed {_bundleKey: row.dst})
CREATE (a)-[r:$(row.type)]->(b) SET r = row.props
"""


def restore(args):
    src = BUNDLES / args.name
    if not (src / "provenance.json").is_file():
        sys.exit(f"bundle: no bundle named {args.name} in {BUNDLES}")
    if serving(args.target):
        sys.exit(f"bundle: a running Autobuilder serves data/{args.target} -- stop it before restoring under it")
    prov = json.loads((src / "provenance.json").read_text())
    lineage = prov["lineageId"]
    graph = json.loads((src / "graph.json").read_text())
    db = Neo4j(args.neo4j, args.user, args.password)

    present = db.run("MATCH (n) WHERE n.lineageId = $l RETURN count(n) AS c", l=lineage)[0]["c"]
    if present and not args.replace:
        sys.exit(f"bundle: this database already holds {present} nodes of {lineage}; restoring would "
                 f"give one lineage two copies. Use --replace to remove them first.")
    target_dir = ROOT / "data" / args.target
    occupied = [p for p in (target_dir.iterdir() if target_dir.is_dir() else []) if p.name != "bootstrap"]
    if occupied and not args.replace:
        sys.exit(f"bundle: data/{args.target} holds {[p.name for p in occupied]}; use --replace to move them aside")

    if present:
        db.run("MATCH (n) WHERE n.lineageId = $l DETACH DELETE n", l=lineage)
    if occupied:
        aside = Path.home() / ".autobuilder" / f"{args.target}-before-restore-{datetime.datetime.now():%Y-%m-%d_%H-%M-%S}"
        aside.mkdir(parents=True)
        for p in occupied:
            shutil.move(str(p), aside / p.name)
        print(f"bundle: moved {[p.name for p in occupied]} aside to {aside}")

    db.run("CREATE INDEX bundle_key IF NOT EXISTS FOR (n:BundleKeyed) ON (n._bundleKey)")
    own = [n for n in graph["nodes"] if not n["boundary"]]
    edge = [n for n in graph["nodes"] if n["boundary"]]
    reused = 0
    try:
        for i in range(0, len(own), BATCH):
            db.run(CREATE_NODES, rows=[dict(n, labels=n["labels"] + ["BundleKeyed"]) for n in own[i:i + BATCH]])
        for i in range(0, len(edge), BATCH):
            rows = [dict(n, labels=n["labels"]) for n in edge[i:i + BATCH]]
            reused += db.run(MERGE_BOUNDARY, rows=rows)[0]["reused"] or 0
        for i in range(0, len(graph["rels"]), BATCH):
            db.run(CREATE_RELS, rows=graph["rels"][i:i + BATCH])
    finally:
        db.run("MATCH (n:BundleKeyed) REMOVE n._bundleKey, n:BundleKeyed")

    p = prov.get("pointers") or {}
    if p.get("rootGoalId"):
        db.run("MERGE (s:SystemState {id: 'autobuilder'}) SET s.rootGoalId = $r, s.currentSubGoalId = $c, "
               "s.lineageId = $l", r=p["rootGoalId"], c=p.get("currentSubGoalId"), l=lineage)

    target_dir.mkdir(parents=True, exist_ok=True)
    for part in prov["files"]:
        dst = target_dir / part
        if part == "bootstrap" and dst.exists():
            shutil.rmtree(dst)
        copy_tree(src / "files" / part, dst)

    got = db.run("MATCH (n) WHERE n.lineageId = $l AND NOT n:SystemState RETURN count(n) AS c", l=lineage)[0]["c"]
    print(f"bundle: restored {args.name} into data/{args.target}: {got}/{prov['nodes']} lineage nodes, "
          f"{len(edge)} boundary ({reused} already present), {len(graph['rels'])} relationships")
    print(f"bundle: continue it with PREFLIGHT_TARGET={args.target} PREFLIGHT_EXTRA_FLAGS=\""
          f"--autobuilder.start.mode=CONTINUE_LINEAGE\"")
    if got != prov["nodes"]:
        sys.exit("bundle: node count differs from the stored bundle")


def list_bundles(_args):
    if not BUNDLES.is_dir():
        return
    for d in sorted(BUNDLES.iterdir()):
        f = d / "provenance.json"
        if f.is_file():
            p = json.loads(f.read_text())
            print(f"{p['name']:30} {p['target']:12} {p['lineageId']} root={p.get('rootStatus')} "
                  f"nodes={p['nodes']} stored={p['storedAt']} rev={p.get('gitRev')}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--neo4j", default=os.environ.get("NEO4J_HTTP", "http://localhost:7474"))
    ap.add_argument("--user", default=os.environ.get("NEO4J_USER", "neo4j"))
    ap.add_argument("--password", default=os.environ.get("NEO4J_PASS", "your_password"))
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("store"); s.add_argument("target"); s.add_argument("name"); s.add_argument("--lineage")
    s.add_argument("--from", dest="source", help="the target's directory, when it is not data/<target> "
                   "(e.g. a run's state moved aside)")
    s.set_defaults(fn=store)
    r = sub.add_parser("restore"); r.add_argument("name"); r.add_argument("target")
    r.add_argument("--replace", action="store_true"); r.set_defaults(fn=restore)
    l = sub.add_parser("list"); l.set_defaults(fn=list_bundles)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
